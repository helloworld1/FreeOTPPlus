package org.fedorahosted.freeotp.data.util

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TokenImageBackupUtilTest {

    private val gson = Gson()
    private lateinit var oldFilesDir: File
    private lateinit var newFilesDir: File

    companion object {
        // Minimal valid 1x1 PNG header + chunks
        private val SAMPLE_PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte()
        )
        private val SAMPLE_SVG_BYTES =
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10" fill="#e20074"/></svg>"""
                .toByteArray(StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        oldFilesDir = Files.createTempDirectory("freeotp_old_files").toFile()
        newFilesDir = Files.createTempDirectory("freeotp_new_files").toFile()
    }

    @After
    fun tearDown() {
        oldFilesDir.deleteRecursively()
        newFilesDir.deleteRecursively()
    }

    @Test
    fun exportAndImportZip_restoresCrossDevicePathsWithoutLeakingSecrets() {
        val pngFile = File(oldFilesDir, "image_github.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val svgFile = File(oldFilesDir, "image_vpn.svg").apply { writeBytes(SAMPLE_SVG_BYTES) }

        val topSecret = "JBSWY3DPEHPK3PXP"
        val sourceTokens = listOf(
            makeToken(
                id = 1L,
                issuer = "GitHub",
                label = "alice@github.com",
                secret = topSecret,
                imagePath = TokenImageBackupUtil.toFileUriString(pngFile)
            ),
            makeToken(
                id = 2L,
                issuer = null,
                label = "Corp VPN",
                secret = topSecret,
                type = OtpTokenType.HOTP,
                imagePath = TokenImageBackupUtil.toFileUriString(svgFile)
            ),
            makeToken(
                id = 3L,
                issuer = "Remote",
                label = "remote@example.com",
                secret = topSecret,
                imagePath = "https://example.com/logo.png"
            ),
            makeToken(
                id = 4L,
                issuer = "Google",
                label = "plain@gmail.com",
                secret = topSecret,
                imagePath = null
            )
        )

        val zipOut = ByteArrayOutputStream()
        TokenImageBackupUtil.exportTokenImagesToZip(
            tokens = sourceTokens,
            filesDir = oldFilesDir,
            gson = gson,
            zipOutputStream = zipOut
        )
        val zipBytes = zipOut.toByteArray()

        // Ensure secret is never present in the exported ZIP bytes
        assertThat(String(zipBytes, StandardCharsets.ISO_8859_1)).doesNotContain(topSecret)

        // Simulate tokens restored onto a new device (still holding oldFilesDir URIs that don't exist in newFilesDir)
        oldFilesDir.deleteRecursively()
        val updated = TokenImageBackupUtil.importTokenImagesFromZip(
            currentTokens = sourceTokens,
            filesDir = newFilesDir,
            gson = gson,
            zipInputStream = ByteArrayInputStream(zipBytes)
        )

        assertThat(updated).hasSize(2)
        val expectedGithubUri = TokenImageBackupUtil.toFileUriString(File(newFilesDir, "image_github.png"))
        val expectedVpnUri = TokenImageBackupUtil.toFileUriString(File(newFilesDir, "image_vpn.svg"))
        assertThat(updated[0].id).isEqualTo(1L)
        assertThat(updated[0].imagePath).isEqualTo(expectedGithubUri)
        assertThat(updated[1].id).isEqualTo(2L)
        assertThat(updated[1].imagePath).isEqualTo(expectedVpnUri)

        assertThat(File(newFilesDir, "image_github.png").readBytes()).isEqualTo(SAMPLE_PNG_BYTES)
        assertThat(File(newFilesDir, "image_vpn.svg").readBytes()).isEqualTo(SAMPLE_SVG_BYTES)
    }

    @Test
    fun importZip_restoresLinksEvenWhenBackupHadNullImagePath_andSupportsImagesFirstOrder() {
        val pngFile = File(oldFilesDir, "image_custom.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val tokenWithImage = makeToken(
            id = 1L,
            issuer = "GitLab",
            label = "dev@gitlab.com",
            secret = "JBSWY3DPEHPK3PXP",
            imagePath = TokenImageBackupUtil.toFileUriString(pngFile)
        )

        val zipOut = ByteArrayOutputStream()
        TokenImageBackupUtil.exportTokenImagesToZip(
            tokens = listOf(tokenWithImage),
            filesDir = oldFilesDir,
            gson = gson,
            zipOutputStream = zipOut
        )
        val zipBytes = zipOut.toByteArray()
        oldFilesDir.deleteRecursively()

        // Case A: User imports token images ZIP first (empty DB), then restores an old backup lacking imagePath
        val initialUpdates = TokenImageBackupUtil.importTokenImagesFromZip(
            currentTokens = emptyList(),
            filesDir = newFilesDir,
            gson = gson,
            zipInputStream = ByteArrayInputStream(zipBytes)
        )
        assertThat(initialUpdates).isEmpty()

        val restoredWithoutImagePath = listOf(tokenWithImage.copy(imagePath = null))
        val cachedManifest = TokenImageBackupUtil.loadCachedManifest(newFilesDir, gson)
        val relinked = TokenImageBackupUtil.resolveRestoredTokenImageLinks(
            tokens = restoredWithoutImagePath,
            filesDir = newFilesDir,
            manifest = cachedManifest
        )
        assertThat(relinked.single().imagePath)
            .isEqualTo(TokenImageBackupUtil.toFileUriString(File(newFilesDir, "image_custom.png")))
    }

    @Test
    fun importZip_rejectsNonZipZipSlipAndNonImagePayloads() {
        // 1. Plain non-ZIP bytes
        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream("not a zip".toByteArray())
            )
        }

        // 2. Zip Slip path traversal
        val zipSlipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("../../escape.png"))
                zos.write(SAMPLE_PNG_BYTES)
                zos.closeEntry()
            }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(zipSlipBytes)
            )
        }

        // 3. Non-image executable/script entry inside ZIP
        val badEntryZip = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("payload.sh"))
                zos.write("#!/bin/sh\necho nope".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()
        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(badEntryZip)
            )
        }
    }

    @Test
    fun importZip_handlesEmptyOrPartialManifestWithoutNpe_andRejectsInvalidManifestWithoutPoisoning() {
        // 1. Empty JSON object "{}" and explicit null entries "{\"entries\":null}" succeed without NPE
        for (manifestJson in listOf("{}", """{"entries":null}""")) {
            val zipBytes = buildZip(
                "image_custom.png" to SAMPLE_PNG_BYTES,
                TokenImageBackupUtil.MANIFEST_ENTRY_NAME to manifestJson.toByteArray(StandardCharsets.UTF_8)
            )
            val updates = TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(zipBytes)
            )
            assertThat(updates).isEmpty()
            assertThat(File(newFilesDir, "image_custom.png").isFile).isTrue()
        }

        // 2. Entry omitting optional fields ("algorithm", "tokenType", "digits", "period") defaults cleanly
        val minimalManifest = """{"entries":[{"fileName":"minimal.png","label":"alice@example.com"}]}"""
        val minimalZip = buildZip(
            "minimal.png" to SAMPLE_PNG_BYTES,
            TokenImageBackupUtil.MANIFEST_ENTRY_NAME to minimalManifest.toByteArray(StandardCharsets.UTF_8)
        )
        val targetToken = makeToken(
            id = 10L,
            issuer = null,
            label = "alice@example.com",
            secret = "JBSWY3DPEHPK3PXP",
            imagePath = null
        )
        val relinkedUpdates = TokenImageBackupUtil.importTokenImagesFromZip(
            currentTokens = listOf(targetToken),
            filesDir = newFilesDir,
            gson = gson,
            zipInputStream = ByteArrayInputStream(minimalZip)
        )
        assertThat(relinkedUpdates).hasSize(1)
        assertThat(relinkedUpdates.single().imagePath)
            .isEqualTo(TokenImageBackupUtil.toFileUriString(File(newFilesDir, "minimal.png")))

        // 3. Invalid manifests throw IOException (never NPE) and do NOT poison cached manifest on disk
        val invalidManifests = listOf(
            "null",
            "[]",
            """{"entries":[null]}""",
            """{"entries":[{"label":"missing_filename"}]}""",
            """{"entries":[{"fileName":"../evil.png","label":"alice"}]}""",
            """{"entries":[{"fileName":"a.png"}]}""",
            """{"entries":[{"fileName":"a.png","label":"alice","tokenType":"UNKNOWN_TYPE"}]}"""
        )
        for (badManifest in invalidManifests) {
            val badZip = buildZip(
                "should_not_persist.png" to SAMPLE_PNG_BYTES,
                TokenImageBackupUtil.MANIFEST_ENTRY_NAME to badManifest.toByteArray(StandardCharsets.UTF_8)
            )
            assertThrows("Expected IOException for manifest: $badManifest", IOException::class.java) {
                TokenImageBackupUtil.importTokenImagesFromZip(
                    currentTokens = listOf(targetToken),
                    filesDir = newFilesDir,
                    gson = gson,
                    zipInputStream = ByteArrayInputStream(badZip)
                )
            }
            assertThat(File(newFilesDir, "should_not_persist.png").exists()).isFalse()
        }

        // Cached manifest from step 2 still works cleanly for subsequent imports
        val cachedAfterRejected = TokenImageBackupUtil.loadCachedManifest(newFilesDir, gson)
        assertThat(cachedAfterRejected).isNotNull()
        assertThat(cachedAfterRejected!!.entries.map { it.fileName }).contains("minimal.png")
    }

    @Test
    fun loadCachedManifest_selfHealsPreExistingPoisonedManifestOnDisk() {
        val validImg = File(newFilesDir, "valid.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val manifestDir = File(newFilesDir, ".image_manifest").apply { mkdirs() }
        // Simulate a poisoned manifest written by an older version with nulls & missing fields
        File(manifestDir, "manifest.json").writeText(
            """
            {
              "version": 0,
              "entries": [
                null,
                {"fileName": null, "label": "broken1"},
                {"fileName": "../escape.png", "label": "broken2"},
                {"fileName": "valid.png", "label": null},
                {"fileName": "valid.png", "issuer": "GitHub", "label": "bob@github.com", "tokenType": null, "algorithm": null, "digits": 0, "period": 0}
              ]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8
        )

        val loaded = TokenImageBackupUtil.loadCachedManifest(newFilesDir, gson)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.entries).hasSize(1)

        val token = makeToken(
            id = 99L,
            issuer = "GitHub",
            label = "bob@github.com",
            secret = "JBSWY3DPEHPK3PXP",
            imagePath = null
        )
        val resolved = TokenImageBackupUtil.resolveRestoredTokenImageLinks(
            tokens = listOf(token),
            filesDir = newFilesDir,
            manifest = loaded
        )
        assertThat(resolved.single().imagePath)
            .isEqualTo(TokenImageBackupUtil.toFileUriString(validImg))
    }

    @Test
    fun importZip_isTransactionalWhenLaterEntryFailsValidation() {
        val originalBytes = SAMPLE_PNG_BYTES + byteArrayOf(0x01, 0x02, 0x03)
        val overwrittenBytes = SAMPLE_PNG_BYTES + byteArrayOf(0x09, 0x09, 0x09)
        val existingIcon = File(newFilesDir, "existing.png").apply { writeBytes(originalBytes) }

        // First entry tries to overwrite existing.png, second adds extra.png, third fails validation
        val failingZip = buildZip(
            "existing.png" to overwrittenBytes,
            "extra.png" to SAMPLE_PNG_BYTES,
            "corrupt.bin" to "not an image".toByteArray(StandardCharsets.UTF_8)
        )

        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(failingZip)
            )
        }

        // Existing file must retain exact original bytes, extra.png must not exist, no staging dirs left
        assertThat(existingIcon.readBytes()).isEqualTo(originalBytes)
        assertThat(File(newFilesDir, "extra.png").exists()).isFalse()
        assertThat(newFilesDir.listFiles().orEmpty().map { it.name })
            .containsExactly("existing.png")
    }

    @Test
    fun importZip_rejectsSvgAndSvgzExceeding2MbRenderLimit() {
        // 1. Plain SVG > 2 MB (2.1 MB)
        val padding = " ".repeat(TokenImageBackupUtil.MAX_SVG_ENTRY_BYTES + 100_000)
        val hugeSvgBytes = "<svg xmlns=\"http://www.w3.org/2000/svg\">$padding</svg>"
            .toByteArray(StandardCharsets.UTF_8)

        val hugeSvgZip = buildZip("huge.svg" to hugeSvgBytes)
        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(hugeSvgZip)
            )
        }
        assertThat(File(newFilesDir, "huge.svg").exists()).isFalse()

        // 2. Gzipped SVGZ whose compressed size is tiny (< 10 KB) but decompresses to > 2 MB
        val svgzBytes = ByteArrayOutputStream().also { bos ->
            GZIPOutputStream(bos).use { gz -> gz.write(hugeSvgBytes) }
        }.toByteArray()
        assertThat(svgzBytes.size).isLessThan(100_000)

        val hugeSvgzZip = buildZip("huge.svgz" to svgzBytes)
        assertThrows(IOException::class.java) {
            TokenImageBackupUtil.importTokenImagesFromZip(
                currentTokens = emptyList(),
                filesDir = newFilesDir,
                gson = gson,
                zipInputStream = ByteArrayInputStream(hugeSvgzZip)
            )
        }
        assertThat(File(newFilesDir, "huge.svgz").exists()).isFalse()
    }

    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        return ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zos ->
                for ((name, bytes) in entries) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }.toByteArray()
    }

    @Test
    fun safeLocalImageConfinement_blocksRemoteContentAndOutOfFilesDirPaths() {
        val safePng = File(newFilesDir, "icon_safe.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val outsideDbDir = File(newFilesDir.parentFile, "databases").apply { mkdirs() }
        val fakeDbFile = File(outsideDbDir, "otp-token-db").apply { writeBytes("SECRET_DB".toByteArray()) }
        val fakeOutsidePng = File(outsideDbDir, "outside.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val hiddenDir = File(newFilesDir, ".image_manifest").apply { mkdirs() }
        val hiddenFile = File(hiddenDir, "hidden.png").apply { writeBytes(SAMPLE_PNG_BYTES) }
        val nonImageInFilesDir = File(newFilesDir, "notes.txt").apply { writeBytes("text".toByteArray()) }

        assertThat(
            TokenImageBackupUtil.resolveSafeLocalImageFile(
                TokenImageBackupUtil.toFileUriString(safePng),
                newFilesDir
            )?.canonicalFile
        ).isEqualTo(safePng.canonicalFile)

        // Rejects remote HTTP(S), content://, DB files, outside directories, hidden directories, traversal, and non-image extensions
        val rejectedPaths = listOf(
            "https://evil.example/pixel.png",
            "http://192.168.1.1/probe.svg",
            "content://com.evil.provider/icon.png",
            TokenImageBackupUtil.toFileUriString(fakeDbFile),
            TokenImageBackupUtil.toFileUriString(fakeOutsidePng),
            TokenImageBackupUtil.toFileUriString(hiddenFile),
            TokenImageBackupUtil.toFileUriString(nonImageInFilesDir),
            "file://${newFilesDir.absolutePath}/../databases/outside.png"
        )
        for (badPath in rejectedPaths) {
            assertThat(TokenImageBackupUtil.resolveSafeLocalImageFile(badPath, newFilesDir)).isNull()
        }

        // sanitizeImportedImagePath strips remote/content/non-image paths and re-roots valid image filenames into filesDir
        assertThat(TokenImageBackupUtil.sanitizeImportedImagePath("https://evil.example/a.png", newFilesDir)).isNull()
        assertThat(TokenImageBackupUtil.sanitizeImportedImagePath("content://media/external/images/1", newFilesDir)).isNull()
        assertThat(TokenImageBackupUtil.sanitizeImportedImagePath(TokenImageBackupUtil.toFileUriString(fakeDbFile), newFilesDir)).isNull()
        assertThat(
            TokenImageBackupUtil.sanitizeImportedImagePath(
                "file:///data/user/0/org.liberty.android.freeotpplus/files/icon_safe.png",
                newFilesDir
            )
        ).isEqualTo(TokenImageBackupUtil.toFileUriString(safePng))

        // exportTokenImagesToZip never packages files outside filesDir even if referenced in token.imagePath
        val zipOut = ByteArrayOutputStream()
        TokenImageBackupUtil.exportTokenImagesToZip(
            tokens = listOf(
                makeToken(1L, "EvilDb", "db", "JBSWY3DPEHPK3PXP", imagePath = TokenImageBackupUtil.toFileUriString(fakeDbFile)),
                makeToken(2L, "EvilOut", "out", "JBSWY3DPEHPK3PXP", imagePath = TokenImageBackupUtil.toFileUriString(fakeOutsidePng))
            ),
            filesDir = newFilesDir,
            gson = gson,
            zipOutputStream = zipOut
        )
        assertThat(String(zipOut.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("SECRET_DB")
        assertThat(String(zipOut.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("outside.png")
    }

    private fun makeToken(
        id: Long,
        issuer: String?,
        label: String,
        secret: String,
        type: OtpTokenType = OtpTokenType.TOTP,
        imagePath: String?
    ): OtpToken {
        return OtpToken(
            id = id,
            ordinal = id,
            issuer = issuer,
            label = label,
            imagePath = imagePath,
            tokenType = type,
            algorithm = "SHA1",
            secret = secret,
            digits = 6,
            counter = 0L,
            period = 30,
            encryptionType = EncryptionType.PLAIN_TEXT
        )
    }
}
