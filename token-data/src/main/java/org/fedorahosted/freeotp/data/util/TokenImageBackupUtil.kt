package org.fedorahosted.freeotp.data.util

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenImageManifest
import org.fedorahosted.freeotp.data.TokenImageManifestEntry
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object TokenImageBackupUtil {
    const val MANIFEST_ENTRY_NAME = "manifest.json"
    private const val CACHED_MANIFEST_DIR = ".image_manifest"
    private const val CACHED_MANIFEST_FILE = "manifest.json"
    private const val MAX_ZIP_ENTRIES = 2000
    private const val MAX_SINGLE_ENTRY_BYTES = 10 * 1024 * 1024 // 10 MB (raster images)
    const val MAX_SVG_ENTRY_BYTES = 2 * 1024 * 1024 // 2 MB (shared with SvgUtil.MAX_SVG_BYTES)
    private const val MAX_TOTAL_EXTRACTED_BYTES = 200L * 1024L * 1024L // 200 MB

    /**
     * Exports all custom local token images referenced by [tokens] into [zipOutputStream],
     * alongside a secret-free `manifest.json` so restored tokens can relink their images.
     */
    @Throws(IOException::class)
    fun exportTokenImagesToZip(
        tokens: List<OtpToken>,
        filesDir: File,
        gson: Gson,
        zipOutputStream: OutputStream,
        fallbackStreamOpener: ((String) -> InputStream?)? = null
    ) {
        val writtenEntryNames = mutableSetOf<String>()
        val sourcePathToZipName = mutableMapOf<String, String>()
        val manifestEntries = mutableListOf<TokenImageManifestEntry>()

        ZipOutputStream(BufferedOutputStream(zipOutputStream)).use { zos ->
            for (token in tokens) {
                val rawPath = token.imagePath?.takeIf { it.isNotBlank() } ?: continue
                if (isRemoteHttpUrl(rawPath)) {
                    continue
                }

                val existingZipName = sourcePathToZipName[rawPath]
                if (existingZipName != null) {
                    manifestEntries.add(buildManifestEntry(token, existingZipName, rawPath))
                    continue
                }

                val localFile = resolveExistingLocalImageFile(rawPath, filesDir)
                if (localFile != null && localFile.isFile) {
                    val zipFileName = allocateUniqueSafeFileName(localFile.name, writtenEntryNames)
                    zos.putNextEntry(ZipEntry(zipFileName))
                    FileInputStream(localFile).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                    sourcePathToZipName[rawPath] = zipFileName
                    manifestEntries.add(buildManifestEntry(token, zipFileName, rawPath))
                } else if (fallbackStreamOpener != null) {
                    val bytes = runCatching {
                        fallbackStreamOpener(rawPath)?.use { stream ->
                            readBoundedBytes(stream, MAX_SINGLE_ENTRY_BYTES)
                        }
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty() && isSupportedImageBytes(bytes)) {
                        val candidateName = extractSafeFileName(rawPath) ?: "image_${rawPath.hashCode()}.png"
                        val zipFileName = allocateUniqueSafeFileName(candidateName, writtenEntryNames)
                        zos.putNextEntry(ZipEntry(zipFileName))
                        zos.write(bytes)
                        zos.closeEntry()
                        sourcePathToZipName[rawPath] = zipFileName
                        manifestEntries.add(buildManifestEntry(token, zipFileName, rawPath))
                    }
                }
            }

            val manifestJson = gson.toJson(TokenImageManifest(version = 1, entries = manifestEntries))
            zos.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zos.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
            zos.finish()
        }
    }

    /**
     * Imports custom token images from [zipInputStream] into [filesDir] transactionally, caches
     * the sanitized manifest, and returns the updated list of [OtpToken]s whose `imagePath` changed.
     */
    @Throws(IOException::class)
    fun importTokenImagesFromZip(
        currentTokens: List<OtpToken>,
        filesDir: File,
        gson: Gson,
        zipInputStream: InputStream
    ): List<OtpToken> {
        if (!filesDir.exists() && !filesDir.mkdirs()) {
            throw IOException("Unable to create files directory: $filesDir")
        }
        val canonicalFilesDir = filesDir.canonicalPath

        val buffered = BufferedInputStream(zipInputStream, 16 * 1024)
        buffered.mark(8)
        val magic = ByteArray(4)
        val magicRead = readUpTo(buffered, magic, 4)
        buffered.reset()
        if (!hasZipMagic(magic, magicRead)) {
            throw IOException("Input is not a valid ZIP archive")
        }

        val stagingDir = File(filesDir, ".tmp_zip_stage_${UUID.randomUUID()}")
        val backupDir = File(stagingDir, ".backup")
        val stagedFileNames = LinkedHashSet<String>()
        var parsedManifest: TokenImageManifest? = null
        var seenManifest = false
        var entryCount = 0
        var totalExtractedBytes = 0L

        try {
            ZipInputStream(buffered).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        throw IOException("ZIP archive exceeds maximum entry count ($MAX_ZIP_ENTRIES)")
                    }
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }

                    val safeName = validateAndExtractZipEntryFileName(entry.name)
                    val entryBytes = readBoundedBytes(zis, MAX_SINGLE_ENTRY_BYTES)
                    zis.closeEntry()

                    totalExtractedBytes += entryBytes.size
                    if (totalExtractedBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                        throw IOException("ZIP archive exceeds maximum total extracted size")
                    }

                    if (safeName == MANIFEST_ENTRY_NAME) {
                        if (seenManifest) {
                            throw IOException("Duplicate manifest.json entry in token images ZIP")
                        }
                        seenManifest = true
                        val text = String(entryBytes, StandardCharsets.UTF_8)
                        parsedManifest = parseAndValidateZipManifest(text, gson)
                        continue
                    }

                    if (!isSupportedImageBytes(entryBytes)) {
                        throw IOException("Unsupported or invalid image entry in ZIP: $safeName")
                    }

                    val targetFile = File(filesDir, safeName)
                    val canonicalTarget = targetFile.canonicalPath
                    if (!canonicalTarget.startsWith(canonicalFilesDir + File.separator)) {
                        throw IOException("Blocked path traversal entry: ${entry.name}")
                    }

                    if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                        throw IOException("Unable to create staging directory")
                    }
                    val stagedFile = File(stagingDir, safeName)
                    FileOutputStream(stagedFile).use { out -> out.write(entryBytes) }
                    stagedFileNames.add(safeName)
                }
            }

            commitStagedFilesAtomically(
                filesDir = filesDir,
                stagingDir = stagingDir,
                backupDir = backupDir,
                stagedFileNames = stagedFileNames
            )
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }

        val mergedManifest = mergeAndSaveCachedManifest(filesDir, gson, parsedManifest)
        val resolvedTokens = resolveRestoredTokenImageLinks(currentTokens, filesDir, mergedManifest)
        return resolvedTokens.zip(currentTokens).mapNotNull { (updated, original) ->
            if (updated.imagePath != original.imagePath) updated else null
        }
    }

    @Throws(IOException::class)
    private fun commitStagedFilesAtomically(
        filesDir: File,
        stagingDir: File,
        backupDir: File,
        stagedFileNames: Set<String>
    ) {
        if (stagedFileNames.isEmpty()) return

        val replacedOriginals = mutableListOf<String>()
        val newlyCreated = mutableListOf<String>()
        try {
            for (name in stagedFileNames) {
                val stagedFile = File(stagingDir, name)
                val targetFile = File(filesDir, name)
                if (targetFile.exists()) {
                    if (!backupDir.exists() && !backupDir.mkdirs()) {
                        throw IOException("Unable to create rollback directory")
                    }
                    val backupFile = File(backupDir, name)
                    if (!targetFile.renameTo(backupFile)) {
                        targetFile.copyTo(backupFile, overwrite = true)
                        if (!targetFile.delete()) {
                            throw IOException("Unable to replace existing image file: $targetFile")
                        }
                    }
                    replacedOriginals.add(name)
                } else {
                    newlyCreated.add(name)
                }

                if (!stagedFile.renameTo(targetFile)) {
                    stagedFile.copyTo(targetFile, overwrite = true)
                }
            }
        } catch (e: Exception) {
            // Roll back any partial filesystem changes before rethrowing
            for (name in newlyCreated) {
                runCatching { File(filesDir, name).delete() }
            }
            for (name in replacedOriginals) {
                runCatching {
                    val targetFile = File(filesDir, name)
                    val backupFile = File(backupDir, name)
                    if (backupFile.exists()) {
                        if (targetFile.exists()) targetFile.delete()
                        if (!backupFile.renameTo(targetFile)) {
                            backupFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                }
            }
            if (e is IOException) throw e
            throw IOException("Failed to commit imported token images", e)
        }
    }

    /**
     * Restores or normalizes local custom `imagePath` links on [tokens] using extracted image
     * files in [filesDir] and optional [manifest] metadata.
     */
    fun resolveRestoredTokenImageLinks(
        tokens: List<OtpToken>,
        filesDir: File,
        manifest: TokenImageManifest? = null
    ): List<OtpToken> {
        val sanitizedManifest = sanitizeManifest(manifest)
        val manifestEntries = sanitizedManifest?.entries.orEmpty()
        val usedManifestIndices = mutableSetOf<Int>()

        return tokens.map { token ->
            val currentPath = token.imagePath
            if (!currentPath.isNullOrBlank()) {
                if (isRemoteHttpUrl(currentPath)) {
                    return@map token
                }

                // 1. Exact originalImagePath match in manifest
                val exactPathIndex = manifestEntries.indexOfFirst { entry ->
                    entry.originalImagePath == currentPath &&
                        isSafeSimpleFileName(entry.fileName) &&
                        File(filesDir, entry.fileName).isFile
                }
                if (exactPathIndex >= 0) {
                    usedManifestIndices.add(exactPathIndex)
                    val target = File(filesDir, manifestEntries[exactPathIndex].fileName)
                    return@map token.copy(imagePath = toFileUriString(target))
                }

                // 2. Match by filename inside filesDir (handles cross-device / cross-profile paths)
                val extractedName = extractSafeFileName(currentPath)
                if (extractedName != null) {
                    val candidateInFilesDir = File(filesDir, extractedName)
                    if (candidateInFilesDir.isFile) {
                        val matchingIdx = manifestEntries.indexOfFirst { it.fileName == extractedName }
                        if (matchingIdx >= 0) usedManifestIndices.add(matchingIdx)
                        return@map token.copy(imagePath = toFileUriString(candidateInFilesDir))
                    }
                }

                // 3. If currentPath already resolves to an existing readable file elsewhere on disk, keep it
                val existingOtherFile = resolveExistingLocalImageFile(currentPath, filesDir)
                if (existingOtherFile != null && existingOtherFile.isFile) {
                    return@map token
                }
            }

            // 4. Fallback to manifest token metadata match (supports older backups without imagePath,
            //    or cases where the exported filename was deduplicated)
            val matchedIndex = findMatchingManifestEntryIndex(
                token = token,
                entries = manifestEntries,
                filesDir = filesDir,
                usedIndices = usedManifestIndices
            )
            if (matchedIndex >= 0) {
                usedManifestIndices.add(matchedIndex)
                val target = File(filesDir, manifestEntries[matchedIndex].fileName)
                token.copy(imagePath = toFileUriString(target))
            } else {
                token
            }
        }
    }

    fun loadCachedManifest(filesDir: File, gson: Gson): TokenImageManifest? {
        val manifestFile = File(File(filesDir, CACHED_MANIFEST_DIR), CACHED_MANIFEST_FILE)
        if (!manifestFile.isFile) return null
        return runCatching {
            val json = manifestFile.readText(StandardCharsets.UTF_8)
            val raw = gson.fromJson(json, TokenImageManifest::class.java)
            sanitizeManifest(raw)
        }.getOrNull()
    }

    @Throws(IOException::class)
    internal fun parseAndValidateZipManifest(jsonText: String, gson: Gson): TokenImageManifest {
        if (jsonText.isBlank()) {
            throw IOException("Empty manifest.json in token images ZIP")
        }
        val rootElement = try {
            JsonParser.parseString(jsonText)
        } catch (e: Exception) {
            throw IOException("Malformed manifest.json in token images ZIP", e)
        }
        if (!rootElement.isJsonObject) {
            throw IOException("Malformed manifest.json in token images ZIP: root must be a JSON object")
        }

        val rootObj = rootElement.asJsonObject
        val entriesEl = rootObj.get("entries")
        if (entriesEl != null && !entriesEl.isJsonNull) {
            if (!entriesEl.isJsonArray) {
                throw IOException("Malformed manifest.json in token images ZIP: 'entries' must be an array")
            }
            for (item in entriesEl.asJsonArray) {
                if (item == null || !item.isJsonObject) {
                    throw IOException("Malformed manifest.json in token images ZIP: entry must be an object")
                }
                val itemObj = item.asJsonObject
                val fileNameEl = itemObj.get("fileName")
                if (fileNameEl == null || !fileNameEl.isJsonPrimitive || !fileNameEl.asJsonPrimitive.isString) {
                    throw IOException("Malformed manifest.json in token images ZIP: entry missing string 'fileName'")
                }
                val fileName = fileNameEl.asString
                if (!isSafeSimpleFileName(fileName) || fileName.equals(MANIFEST_ENTRY_NAME, ignoreCase = true)) {
                    throw IOException("Malformed manifest.json in token images ZIP: unsafe 'fileName' ($fileName)")
                }

                val labelEl = itemObj.get("label")
                if (labelEl == null || !labelEl.isJsonPrimitive || !labelEl.asJsonPrimitive.isString) {
                    throw IOException("Malformed manifest.json in token images ZIP: entry missing string 'label'")
                }

                val tokenTypeEl = itemObj.get("tokenType")
                if (tokenTypeEl != null && !tokenTypeEl.isJsonNull) {
                    if (!tokenTypeEl.isJsonPrimitive || !tokenTypeEl.asJsonPrimitive.isString) {
                        throw IOException("Malformed manifest.json in token images ZIP: invalid 'tokenType'")
                    }
                    val validType = OtpTokenType.entries.any { it.name == tokenTypeEl.asString }
                    if (!validType) {
                        throw IOException("Malformed manifest.json in token images ZIP: unknown 'tokenType'")
                    }
                }
            }
        }

        val raw = try {
            gson.fromJson(rootObj, TokenImageManifest::class.java)
        } catch (e: Exception) {
            throw IOException("Malformed manifest.json in token images ZIP", e)
        } ?: throw IOException("Malformed manifest.json in token images ZIP")

        return sanitizeManifest(raw) ?: TokenImageManifest(version = 1, entries = emptyList())
    }

    @Suppress("USELESS_CAST", "UNCHECKED_CAST")
    internal fun sanitizeManifest(raw: TokenImageManifest?): TokenImageManifest? {
        if (raw == null) return null
        val rawEntries = (raw.entries as List<Any?>?).orEmpty()
        val cleanEntries = rawEntries.mapNotNull { item ->
            val entry = item as? TokenImageManifestEntry ?: return@mapNotNull null
            val safeFileName = (entry.fileName as String?)
                ?.takeIf { isSafeSimpleFileName(it) && !it.equals(MANIFEST_ENTRY_NAME, ignoreCase = true) }
                ?: return@mapNotNull null
            val safeLabel = (entry.label as String?) ?: return@mapNotNull null
            val safeOriginalPath = (entry.originalImagePath as String?)?.takeIf { it.isNotBlank() }
            val safeIssuer = (entry.issuer as String?)?.takeIf { it.isNotBlank() }
            val safeTokenType = (entry.tokenType as OtpTokenType?) ?: OtpTokenType.TOTP
            val safeAlgorithm = (entry.algorithm as String?)?.takeIf { it.isNotBlank() } ?: "SHA1"
            val safeDigits = entry.digits.takeIf { it > 0 } ?: 6
            val safePeriod = entry.period.takeIf { it > 0 } ?: 30

            TokenImageManifestEntry(
                fileName = safeFileName,
                originalImagePath = safeOriginalPath,
                issuer = safeIssuer,
                label = safeLabel,
                tokenType = safeTokenType,
                algorithm = safeAlgorithm,
                digits = safeDigits,
                period = safePeriod
            )
        }
        val safeVersion = if (raw.version > 0) raw.version else 1
        return TokenImageManifest(version = safeVersion, entries = cleanEntries)
    }

    private fun mergeAndSaveCachedManifest(
        filesDir: File,
        gson: Gson,
        incoming: TokenImageManifest?
    ): TokenImageManifest? {
        val existing = loadCachedManifest(filesDir, gson)
        val sanitizedIncoming = sanitizeManifest(incoming) ?: return existing

        val combined = LinkedHashMap<String, TokenImageManifestEntry>()
        for (entry in existing?.entries.orEmpty() + sanitizedIncoming.entries) {
            val key = "${entry.issuer.orEmpty()}|${entry.label}|${entry.tokenType}|${entry.fileName}"
            combined[key] = entry
        }
        val merged = TokenImageManifest(version = 1, entries = combined.values.toList())
        runCatching {
            val dir = File(filesDir, CACHED_MANIFEST_DIR)
            if (dir.exists() || dir.mkdirs()) {
                val target = File(dir, CACHED_MANIFEST_FILE)
                val temp = File(dir, ".tmp_manifest_${UUID.randomUUID()}.json")
                try {
                    temp.writeText(gson.toJson(merged), StandardCharsets.UTF_8)
                    if (target.exists() && !target.delete()) {
                        throw IOException("Unable to replace cached manifest")
                    }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                    }
                } finally {
                    if (temp.exists()) temp.delete()
                }
            }
        }
        return merged
    }

    private fun findMatchingManifestEntryIndex(
        token: OtpToken,
        entries: List<TokenImageManifestEntry>,
        filesDir: File,
        usedIndices: Set<Int>
    ): Int {
        val tokenIssuer = token.issuer?.takeIf { it.isNotBlank() }
        val tokenAlgo = token.algorithm.uppercase(Locale.US)

        // Pass 1: Strict match on issuer + label + type + algorithm + digits (unused entry first)
        entries.indices.firstOrNull { idx ->
            idx !in usedIndices &&
                matchesStrict(token, tokenIssuer, tokenAlgo, entries[idx], filesDir)
        }?.let { return it }

        // Pass 2: Strict match allowing reuse if multiple identical tokens share one manifest entry
        entries.indices.firstOrNull { idx ->
            matchesStrict(token, tokenIssuer, tokenAlgo, entries[idx], filesDir)
        }?.let { return it }

        // Pass 3: Match on issuer + label + tokenType (unused entry first)
        entries.indices.firstOrNull { idx ->
            val e = entries[idx]
            idx !in usedIndices &&
                isSafeSimpleFileName(e.fileName) &&
                File(filesDir, e.fileName).isFile &&
                e.issuer?.takeIf { it.isNotBlank() } == tokenIssuer &&
                e.label == token.label &&
                e.tokenType == token.tokenType
        }?.let { return it }

        return -1
    }

    private fun matchesStrict(
        token: OtpToken,
        tokenIssuer: String?,
        tokenAlgo: String,
        entry: TokenImageManifestEntry,
        filesDir: File
    ): Boolean {
        if (!isSafeSimpleFileName(entry.fileName) || !File(filesDir, entry.fileName).isFile) {
            return false
        }
        val entryIssuer = entry.issuer?.takeIf { it.isNotBlank() }
        return entryIssuer == tokenIssuer &&
            entry.label == token.label &&
            entry.tokenType == token.tokenType &&
            entry.algorithm.uppercase(Locale.US) == tokenAlgo &&
            entry.digits == token.digits
    }

    private fun buildManifestEntry(
        token: OtpToken,
        fileName: String,
        originalImagePath: String
    ): TokenImageManifestEntry {
        return TokenImageManifestEntry(
            fileName = fileName,
            originalImagePath = originalImagePath,
            issuer = token.issuer?.takeIf { it.isNotBlank() },
            label = token.label,
            tokenType = token.tokenType,
            algorithm = token.algorithm,
            digits = token.digits,
            period = token.period
        )
    }

    private fun resolveExistingLocalImageFile(imagePath: String, filesDir: File): File? {
        if (imagePath.startsWith("file:", ignoreCase = true)) {
            val parsedFile = runCatching { File(URI(imagePath)) }.getOrNull()
                ?: Uri.parse(imagePath)?.path?.let(::File)
            if (parsedFile != null && parsedFile.isFile) {
                return parsedFile
            }
        } else if (imagePath.startsWith("/")) {
            val direct = File(imagePath)
            if (direct.isFile) return direct
        }

        val name = extractSafeFileName(imagePath)
        if (name != null) {
            val inFilesDir = File(filesDir, name)
            if (inFilesDir.isFile) return inFilesDir
        }
        return null
    }

    internal fun extractSafeFileName(pathOrUri: String): String? {
        val withoutQuery = pathOrUri.substringBefore('?').substringBefore('#')
        val decoded = runCatching {
            if (withoutQuery.startsWith("file:", ignoreCase = true)) {
                URI(withoutQuery).path ?: withoutQuery
            } else {
                Uri.decode(withoutQuery) ?: withoutQuery
            }
        }.getOrDefault(withoutQuery)

        val candidate = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        return candidate.takeIf { isSafeSimpleFileName(it) }
    }

    @Throws(IOException::class)
    private fun validateAndExtractZipEntryFileName(rawEntryName: String): String {
        val normalized = rawEntryName.replace('\\', '/')
        if (normalized.startsWith("/") ||
            normalized.split('/').any { it == ".." || it == "." || it.isEmpty() }
        ) {
            throw IOException("Invalid ZIP entry path: $rawEntryName")
        }
        val baseName = normalized.substringAfterLast('/')
        if (!isSafeSimpleFileName(baseName)) {
            throw IOException("Invalid ZIP entry file name: $rawEntryName")
        }
        return baseName
    }

    private fun isSafeSimpleFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200) return false
        if (name == "." || name == "..") return false
        if (name.startsWith(".")) return false
        if ('/' in name || '\\' in name || '\u0000' in name) return false
        return true
    }

    private fun allocateUniqueSafeFileName(desiredName: String, usedLowerNames: MutableSet<String>): String {
        val baseSafe = if (isSafeSimpleFileName(desiredName) && !desiredName.equals(MANIFEST_ENTRY_NAME, ignoreCase = true)) {
            desiredName
        } else {
            "image_${UUID.randomUUID()}.png"
        }
        if (usedLowerNames.add(baseSafe.lowercase(Locale.ROOT))) {
            return baseSafe
        }
        val dot = baseSafe.lastIndexOf('.')
        val stem = if (dot > 0) baseSafe.substring(0, dot) else baseSafe
        val ext = if (dot > 0) baseSafe.substring(dot) else ""
        var suffix = 2
        while (true) {
            val candidate = "${stem}_$suffix$ext"
            if (usedLowerNames.add(candidate.lowercase(Locale.ROOT))) {
                return candidate
            }
            suffix++
        }
    }

    internal fun toFileUriString(file: File): String {
        val androidUri = runCatching { Uri.fromFile(file)?.toString() }.getOrNull()
        if (!androidUri.isNullOrBlank()) {
            return androidUri
        }
        val path = file.absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    private fun isRemoteHttpUrl(path: String): Boolean {
        return path.startsWith("http://", ignoreCase = true) ||
            path.startsWith("https://", ignoreCase = true)
    }

    private fun hasZipMagic(bytes: ByteArray, readLen: Int): Boolean {
        if (readLen < 4) return false
        if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        // PK\x03\x04 (standard local file header) or PK\x05\x06 (empty archive EOCD)
        return (bytes[2] == 3.toByte() && bytes[3] == 4.toByte()) ||
            (bytes[2] == 5.toByte() && bytes[3] == 6.toByte())
    }

    internal fun isSupportedImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // PNG
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return true
        // JPEG
        if (bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return true
        // GIF
        if (bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte()
        ) return true
        // WEBP (RIFF....WEBP)
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) return true
        // BMP
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) return true
        // ICO
        if (bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() &&
            bytes[2] == 1.toByte() &&
            bytes[3] == 0.toByte()
        ) return true
        // HEIF / AVIF (....ftyp)
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) return true
        // Gzipped SVGZ (must fit within MAX_SVG_ENTRY_BYTES = 2 MB both compressed and decompressed)
        if (bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            if (bytes.size > MAX_SVG_ENTRY_BYTES) return false
            val decompressed = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                    readBoundedBytes(gz, MAX_SVG_ENTRY_BYTES)
                }
            }.getOrNull() ?: return false
            return isSafeSvgText(decompressed)
        }
        // Plain XML SVG (must fit within MAX_SVG_ENTRY_BYTES = 2 MB to match SvgUtil.MAX_SVG_BYTES)
        if (bytes.size > MAX_SVG_ENTRY_BYTES) return false
        return isSafeSvgText(bytes)
    }

    private fun isSafeSvgText(bytes: ByteArray): Boolean {
        val sampleLen = minOf(bytes.size, 8192)
        val fullLower = String(bytes, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        if ("<!entity" in fullLower) return false
        val headLower = String(bytes, 0, sampleLen, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        return "<svg" in headLower
    }

    private fun readUpTo(source: InputStream, buffer: ByteArray, maxLen: Int): Int {
        var offset = 0
        while (offset < maxLen) {
            val n = source.read(buffer, offset, maxLen - offset)
            if (n <= 0) break
            offset += n
        }
        return offset
    }

    @Throws(IOException::class)
    private fun readBoundedBytes(source: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IOException("Entry exceeds maximum allowed size of $maxBytes bytes")
            }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    @Suppress("unused")
    internal fun openUriFallback(context: Context, rawUri: String): InputStream? {
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(rawUri))
        }.getOrNull()
    }
}
