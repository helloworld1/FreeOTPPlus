package org.fedorahosted.freeotp.data.legacy

import com.google.android.apps.authenticator.Base32String
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Parses and decrypts JSON exports/backups from Aegis Authenticator
 * (https://github.com/beemdevelopment/Aegis):
 *
 * - Plaintext Aegis JSON (`header.slots == null && header.params == null`, `db` JSON object)
 * - Password-encrypted Aegis JSON (`header.slots` containing `type == 1` scrypt + AES-256-GCM
 *   master-key slots, `header.params` nonce/tag, and Base64-encoded AES-256-GCM `db` ciphertext)
 * - Decrypted `db`-only JSON (`{ "version": <= 3, "entries": [...], "groups": [...] }`, e.g. from `decrypt.py`)
 */
object AegisBackupParser {

    internal const val MAX_AEGIS_JSON_BYTES = 32 * 1024 * 1024 // 32 MB (Aegis vaults may embed Base64 icons)
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024 // 2 MB per embedded icon
    private const val MAX_SCRYPT_N = 1 shl 20 // 1,048,576 max CPU/memory cost
    private const val MAX_SCRYPT_R = 32
    private const val MAX_SCRYPT_P = 16
    private const val MAX_SCRYPT_MEM_BYTES = 256L * 1024L * 1024L // 256 MB cap against DoS

    private const val VAULT_FILE_MAX_VERSION = 1
    private const val VAULT_DB_MAX_VERSION = 3
    private const val SLOT_TYPE_PASSWORD = 1

    private const val AES_GCM_CIPHER = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BYTES = 32

    internal class AegisCryptParams(
        @SerializedName("nonce")
        val nonce: String? = null,
        @SerializedName("tag")
        val tag: String? = null
    )

    internal class AegisSlot(
        @SerializedName("type")
        val type: Int = -1,
        @SerializedName("uuid")
        val uuid: String? = null,
        @SerializedName("key")
        val key: String? = null,
        @SerializedName("key_params")
        val keyParams: AegisCryptParams? = null,
        @SerializedName("n")
        val n: Int = 0,
        @SerializedName("r")
        val r: Int = 0,
        @SerializedName("p")
        val p: Int = 0,
        @SerializedName("salt")
        val salt: String? = null,
        @SerializedName("repaired")
        val repaired: Boolean? = null
    )

    internal class AegisHeader(
        @SerializedName("slots")
        val slots: List<AegisSlot>? = null,
        @SerializedName("params")
        val params: AegisCryptParams? = null
    )

    internal class AegisGroup(
        @SerializedName("uuid")
        val uuid: String? = null,
        @SerializedName("name")
        val name: String? = null
    )

    internal class AegisEntryInfo(
        @SerializedName("secret")
        val secret: String? = null,
        @SerializedName("algo")
        val algo: String? = null,
        @SerializedName("digits")
        val digits: Int? = null,
        @SerializedName("period")
        val period: Int? = null,
        @SerializedName("counter")
        val counter: Long? = null
    )

    internal class AegisEntry(
        @SerializedName("type")
        val type: String? = null,
        @SerializedName("uuid")
        val uuid: String? = null,
        @SerializedName("name")
        val name: String? = null,
        @SerializedName("issuer")
        val issuer: String? = null,
        @SerializedName("icon")
        val icon: String? = null,
        @SerializedName("icon_mime")
        val iconMime: String? = null,
        @SerializedName("group")
        val legacyGroup: String? = null,
        @SerializedName("groups")
        val groupUuids: List<String>? = null,
        @SerializedName("info")
        val info: AegisEntryInfo? = null
    )

    internal class AegisDb(
        @SerializedName("version")
        val version: Int = 0,
        @SerializedName("entries")
        val entries: List<AegisEntry>? = null,
        @SerializedName("groups")
        val groups: List<AegisGroup>? = null
    )

    data class BackupInspection(
        val requiresPassword: Boolean
    )

    fun inspectBackup(bytes: ByteArray, gson: Gson = Gson()): BackupInspection {
        val root = parseRootJsonObject(bytes)
        val encrypted = isEncryptedVaultRoot(root, gson)
        return BackupInspection(requiresPassword = encrypted)
    }

    fun parseBackup(
        bytes: ByteArray,
        passphrase: CharArray? = null,
        filesDir: File? = null,
        gson: Gson = Gson()
    ): List<OtpToken> {
        val root = parseRootJsonObject(bytes)
        val dbObj: JsonObject = if (isEncryptedVaultRoot(root, gson)) {
            if (passphrase == null || passphrase.isEmpty()) {
                throw InvalidEncryptionKeyException("Aegis backup password is required")
            }
            decryptVaultDbObject(root, passphrase, gson)
        } else if (root.has("db") && root.get("db").isJsonObject) {
            root.getAsJsonObject("db")
        } else if (root.has("entries") && root.get("entries").isJsonArray) {
            // Raw decrypted `db` JSON (e.g. produced by Aegis's `docs/decrypt.py`)
            root
        } else {
            throw IllegalArgumentException("Invalid Aegis JSON structure")
        }

        val db = try {
            gson.fromJson(dbObj, AegisDb::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Aegis db JSON", e)
        } ?: throw IllegalArgumentException("Empty Aegis db JSON")

        if (db.version !in 1..VAULT_DB_MAX_VERSION) {
            throw IllegalArgumentException("Unsupported Aegis db version: ${db.version}")
        }
        val rawEntries = db.entries ?: throw IllegalArgumentException("Missing entries array in Aegis db")
        if (rawEntries.isEmpty()) {
            return emptyList()
        }

        val groupNameByUuid = LinkedHashMap<String, String>()
        for (group in db.groups.orEmpty()) {
            val uuid = group.uuid?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: continue
            val name = group.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            groupNameByUuid[uuid] = name
        }

        val savedIconPathBySha256 = HashMap<String, String>()
        val tokens = ArrayList<OtpToken>(rawEntries.size)

        for (entry in rawEntries) {
            val token = convertEntryToOtpToken(
                entry = entry,
                groupNameByUuid = groupNameByUuid,
                filesDir = filesDir,
                savedIconPathBySha256 = savedIconPathBySha256
            )
            if (token != null) {
                tokens.add(token)
            }
        }

        if (tokens.isEmpty()) {
            throw IllegalArgumentException("No supported TOTP, HOTP, or Steam entries found in Aegis JSON")
        }
        return tokens
    }

    private fun parseRootJsonObject(bytes: ByteArray): JsonObject {
        if (bytes.isEmpty() || bytes.size > MAX_AEGIS_JSON_BYTES) {
            throw IllegalArgumentException("Invalid Aegis JSON size")
        }
        val text = bytes.toString(StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!text.startsWith("{")) {
            throw IllegalArgumentException("File is not a JSON object")
        }
        val element: JsonElement = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed JSON in Aegis backup", e)
        }
        if (!element.isJsonObject) {
            throw IllegalArgumentException("Aegis backup root is not a JSON object")
        }
        return element.asJsonObject
    }

    private fun isEncryptedVaultRoot(root: JsonObject, gson: Gson): Boolean {
        // Direct decrypted `db` object (`{ "version": 3, "entries": [...] }`)
        if (!root.has("header") && root.has("entries") && root.get("entries").isJsonArray) {
            val dbVer = root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (dbVer !in 1..VAULT_DB_MAX_VERSION) {
                throw IllegalArgumentException("Unsupported Aegis db version: $dbVer")
            }
            return false
        }

        val fileVersion = root.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw IllegalArgumentException("Missing version in Aegis JSON")
        if (fileVersion !in 1..VAULT_FILE_MAX_VERSION) {
            throw IllegalArgumentException("Unsupported Aegis vault file version: $fileVersion")
        }

        val headerEl = root.get("header")
            ?: throw IllegalArgumentException("Missing header in Aegis JSON")
        if (!headerEl.isJsonObject) {
            throw IllegalArgumentException("Invalid header in Aegis JSON")
        }
        val headerObj = headerEl.asJsonObject
        val slotsEl = headerObj.get("slots")
        val paramsEl = headerObj.get("params")
        val slotsNull = slotsEl == null || slotsEl.isJsonNull
        val paramsNull = paramsEl == null || paramsEl.isJsonNull

        val dbEl = root.get("db") ?: throw IllegalArgumentException("Missing db in Aegis JSON")

        if (slotsNull && paramsNull) {
            if (!dbEl.isJsonObject) {
                throw IllegalArgumentException("Plaintext Aegis vault must have a JSON object 'db'")
            }
            val entriesEl = dbEl.asJsonObject.get("entries")
            if (entriesEl == null || !entriesEl.isJsonArray) {
                throw IllegalArgumentException("Plaintext Aegis vault 'db' is missing 'entries'")
            }
            return false
        }

        if (slotsNull || paramsNull || !slotsEl.isJsonArray || !paramsEl.isJsonObject || !dbEl.isJsonPrimitive) {
            throw IllegalArgumentException("Malformed encrypted Aegis vault header or db")
        }
        val header = try {
            gson.fromJson(headerObj, AegisHeader::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Aegis header JSON", e)
        }
        val hasPasswordSlot = header?.slots.orEmpty().any { it.type == SLOT_TYPE_PASSWORD }
        if (!hasPasswordSlot) {
            throw IllegalArgumentException("Encrypted Aegis vault contains no password slot")
        }
        return true
    }

    private fun decryptVaultDbObject(
        root: JsonObject,
        passphrase: CharArray,
        gson: Gson
    ): JsonObject {
        val header = gson.fromJson(root.getAsJsonObject("header"), AegisHeader::class.java)
            ?: throw IllegalArgumentException("Missing Aegis header")
        val dbParams = header.params ?: throw IllegalArgumentException("Missing Aegis db params")
        val dbCiphertextB64 = root.get("db")?.asString?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing encrypted Aegis db payload")

        val passwordSlots = header.slots.orEmpty().filter { it.type == SLOT_TYPE_PASSWORD }
        if (passwordSlots.isEmpty()) {
            throw IllegalArgumentException("Encrypted Aegis vault contains no password slot")
        }

        val utf8PassBytes = encodeUtf8Exact(passphrase)
        var masterKeyBytes: ByteArray? = null
        try {
            for (slot in passwordSlots) {
                masterKeyBytes = tryUnlockPasswordSlot(slot, passphrase, utf8PassBytes)
                if (masterKeyBytes != null) break
            }
            val unwrappedMasterKey = masterKeyBytes
                ?: throw InvalidEncryptionKeyException("Incorrect Aegis backup password")

            val dbCipherBytes = try {
                Base64.Mime.decode(dbCiphertextB64)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid Base64 in encrypted Aegis db", e)
            }
            val dbNonce = decodeHex(dbParams.nonce, expectedBytes = 12, fieldName = "header.params.nonce")
            val dbTag = decodeHex(dbParams.tag, expectedBytes = 16, fieldName = "header.params.tag")

            val decryptedDbBytes = try {
                aesGcmDecrypt(
                    keyBytes = unwrappedMasterKey,
                    nonce = dbNonce,
                    ciphertext = dbCipherBytes,
                    tag = dbTag
                )
            } catch (e: GeneralSecurityException) {
                throw IllegalArgumentException("Encrypted Aegis db integrity check failed", e)
            }

            try {
                val dbJsonStr = decryptedDbBytes.toString(StandardCharsets.UTF_8)
                val parsed = JsonParser.parseString(dbJsonStr)
                if (!parsed.isJsonObject) {
                    throw IllegalArgumentException("Decrypted Aegis db is not a JSON object")
                }
                return parsed.asJsonObject
            } catch (e: Exception) {
                if (e is IllegalArgumentException) throw e
                throw IllegalArgumentException("Decrypted Aegis db contains invalid JSON", e)
            } finally {
                decryptedDbBytes.fill(0)
            }
        } finally {
            utf8PassBytes.fill(0)
            masterKeyBytes?.fill(0)
        }
    }

    private fun tryUnlockPasswordSlot(
        slot: AegisSlot,
        passphrase: CharArray,
        utf8PassBytes: ByteArray
    ): ByteArray? {
        val saltBytes = decodeHex(slot.salt, expectedBytes = null, fieldName = "slot.salt")
        val encryptedKey = decodeHex(slot.key, expectedBytes = null, fieldName = "slot.key")
        val keyParams = slot.keyParams ?: throw IllegalArgumentException("Missing slot.key_params")
        val nonce = decodeHex(keyParams.nonce, expectedBytes = 12, fieldName = "slot.key_params.nonce")
        val tag = decodeHex(keyParams.tag, expectedBytes = 16, fieldName = "slot.key_params.tag")

        validateScryptParams(slot.n, slot.r, slot.p)

        // 1. Standard UTF-8 password encoding (`CryptoUtils.toBytes`)
        val derived = scrypt(utf8PassBytes, saltBytes, slot.n, slot.r, slot.p, AES_KEY_BYTES)
        try {
            return aesGcmDecrypt(derived, nonce, encryptedKey, tag)
        } catch (_: GeneralSecurityException) {
            // Try legacy un-repaired encoding below if applicable, or next password slot
        } finally {
            derived.fill(0)
        }

        // 2. Legacy Aegis issue #95 fallback (`CryptoUtils.toBytesOld`) when `repaired == false`
        if (slot.repaired == false) {
            val legacyPassBytes = encodeUtf8LegacyPadded(passphrase)
            if (!legacyPassBytes.contentEquals(utf8PassBytes)) {
                val legacyDerived = try {
                    scrypt(legacyPassBytes, saltBytes, slot.n, slot.r, slot.p, AES_KEY_BYTES)
                } finally {
                    legacyPassBytes.fill(0)
                }
                try {
                    return aesGcmDecrypt(legacyDerived, nonce, encryptedKey, tag)
                } catch (_: GeneralSecurityException) {
                    return null
                } finally {
                    legacyDerived.fill(0)
                }
            } else {
                legacyPassBytes.fill(0)
            }
        }
        return null
    }

    private fun convertEntryToOtpToken(
        entry: AegisEntry,
        groupNameByUuid: Map<String, String>,
        filesDir: File?,
        savedIconPathBySha256: MutableMap<String, String>
    ): OtpToken? {
        val rawType = entry.type?.trim()?.lowercase(Locale.ROOT)
            ?: throw IllegalArgumentException("Missing entry type in Aegis JSON")

        // Skip non-OATH PIN-based proprietary types unsupported by FreeOTP+
        if (rawType == "motp" || rawType == "yandex") {
            return null
        }
        if (rawType != "totp" && rawType != "hotp" && rawType != "steam") {
            throw IllegalArgumentException("Unsupported entry type in Aegis JSON: $rawType")
        }

        val info = entry.info ?: throw IllegalArgumentException("Missing entry info in Aegis JSON")
        val rawSecret = info.secret?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Missing secret in Aegis entry")

        val decodedSecret = try {
            Base32String.decode(rawSecret)
        } catch (e: Base32String.DecodingException) {
            throw IllegalArgumentException("Invalid Base32 secret in Aegis entry", e)
        }
        if (decodedSecret.isEmpty()) {
            throw IllegalArgumentException("Empty secret in Aegis entry")
        }
        val normalizedSecret = try {
            Base32String.encode(decodedSecret)
        } finally {
            decodedSecret.fill(0)
        }

        val entryIssuer = entry.issuer?.trim()?.takeIf { it.isNotEmpty() }
        val entryName = entry.name?.trim().orEmpty()

        val resolvedType: OtpTokenType
        val resolvedIssuer: String?
        val resolvedLabel: String
        val resolvedDigits: Int
        val resolvedPeriod: Int
        val resolvedCounter: Long
        val isSteam: Boolean

        when (rawType) {
            "steam" -> {
                resolvedType = OtpTokenType.TOTP
                resolvedIssuer = "Steam"
                resolvedLabel = when {
                    entryIssuer != null && !entryIssuer.equals("Steam", ignoreCase = true) ->
                        if (entryName.isNotEmpty()) "$entryIssuer ($entryName)" else entryIssuer
                    else -> entryName
                }
                resolvedDigits = if (info.digits != null && info.digits > 0) info.digits else 5
                resolvedPeriod = if (info.period != null && info.period > 0) info.period else 30
                resolvedCounter = 0L
                isSteam = true
            }

            "hotp" -> {
                resolvedType = OtpTokenType.HOTP
                resolvedIssuer = entryIssuer
                resolvedLabel = entryName
                resolvedDigits = if (info.digits != null && info.digits > 0) info.digits else 6
                resolvedPeriod = 30
                resolvedCounter = (info.counter ?: 0L).coerceAtLeast(0L)
                isSteam = resolvedIssuer == "Steam"
            }

            else -> {
                resolvedType = OtpTokenType.TOTP
                resolvedIssuer = entryIssuer
                resolvedLabel = entryName
                resolvedDigits = if (info.digits != null && info.digits > 0) info.digits else 6
                resolvedPeriod = if (info.period != null && info.period > 0) info.period else 30
                resolvedCounter = 0L
                isSteam = resolvedIssuer == "Steam"
            }
        }

        val finalLabel = if (resolvedIssuer == null && resolvedLabel.isEmpty()) {
            "Account"
        } else {
            resolvedLabel
        }

        val categories = if (entry.groupUuids != null) {
            CategoryUtil.normalizeCategories(
                entry.groupUuids.mapNotNull { uuid ->
                    groupNameByUuid[uuid.trim().lowercase(Locale.ROOT)]
                }
            )
        } else {
            CategoryUtil.normalizeCategories(listOf(entry.legacyGroup))
        }

        val savedImagePath = if (filesDir != null && !entry.icon.isNullOrBlank()) {
            saveEmbeddedAegisIconIfValid(entry.icon, entry.iconMime, filesDir, savedIconPathBySha256)
        } else {
            null
        }

        return OtpTokenFactory.create(
            type = resolvedType,
            issuer = resolvedIssuer,
            label = finalLabel,
            secret = normalizedSecret,
            algorithm = info.algo ?: "SHA1",
            digits = resolvedDigits,
            period = resolvedPeriod,
            initialCounter = resolvedCounter,
            imagePath = savedImagePath,
            categories = categories,
            isSteamIssuerOverride = isSteam
        )
    }

    private fun saveEmbeddedAegisIconIfValid(
        iconBase64: String,
        iconMime: String?,
        filesDir: File,
        savedIconPathBySha256: MutableMap<String, String>
    ): String? {
        // Quick length check before Base64 decode (~4/3 expansion)
        if (iconBase64.length > (MAX_ICON_BYTES * 4) / 3 + 4096) {
            return null
        }
        val iconBytes = runCatching { Base64.Mime.decode(iconBase64) }.getOrNull() ?: return null
        if (iconBytes.isEmpty() || iconBytes.size > MAX_ICON_BYTES) {
            return null
        }
        if (!TokenImageBackupUtil.isSupportedImageBytes(iconBytes)) {
            return null
        }

        val sha256Hex = toHex(MessageDigest.getInstance("SHA-256").digest(iconBytes))
        savedIconPathBySha256[sha256Hex]?.let { return it }

        val ext = inferSafeImageExtension(iconBytes, iconMime)
        return runCatching {
            filesDir.mkdirs()
            val outFile = File(filesDir, "image_${UUID.randomUUID()}.$ext")
            outFile.outputStream().use { it.write(iconBytes) }
            val uriStr = TokenImageBackupUtil.toFileUriString(outFile)
            savedIconPathBySha256[sha256Hex] = uriStr
            uriStr
        }.getOrNull()
    }

    private fun inferSafeImageExtension(bytes: ByteArray, mime: String?): String {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()
        ) return "png"
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) return "jpg"
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte()
        ) return "webp"
        if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) return "svgz"
        return when (mime?.trim()?.lowercase(Locale.ROOT)) {
            "image/svg+xml" -> "svg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> {
                val head = String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.UTF_8).lowercase(Locale.ROOT)
                if ("<svg" in head) "svg" else "png"
            }
        }
    }

    @Throws(GeneralSecurityException::class)
    private fun aesGcmDecrypt(
        keyBytes: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray
    ): ByteArray {
        val combined = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)
        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(combined)
    }

    private fun validateScryptParams(n: Int, r: Int, p: Int) {
        if (n <= 1 || (n and (n - 1)) != 0 || n > MAX_SCRYPT_N) {
            throw IllegalArgumentException("Unsupported or unsafe scrypt N parameter: $n")
        }
        if (r !in 1..MAX_SCRYPT_R) {
            throw IllegalArgumentException("Unsupported scrypt r parameter: $r")
        }
        if (p !in 1..MAX_SCRYPT_P) {
            throw IllegalArgumentException("Unsupported scrypt p parameter: $p")
        }
        if (r == 1 && n >= 65536) {
            throw IllegalArgumentException("Invalid scrypt N >= 65536 with r == 1")
        }
        val estimatedVBytes = 128L * r.toLong() * n.toLong()
        val estimatedBBytes = 128L * r.toLong() * p.toLong()
        if (estimatedVBytes > MAX_SCRYPT_MEM_BYTES || estimatedBBytes > MAX_SCRYPT_MEM_BYTES) {
            throw IllegalArgumentException("scrypt memory cost exceeds safety limit")
        }
    }

    /**
     * Pure Kotlin implementation of RFC 7914 `scrypt(P, S, N, r, p, dkLen)` matching BouncyCastle /
     * Aegis `SCrypt.generate` (including chunked `V` allocation to avoid large contiguous heap
     * allocations on Android).
     */
    internal fun scrypt(
        passphraseBytes: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        dkLen: Int
    ): ByteArray {
        validateScryptParams(n, r, p)
        require(dkLen > 0) { "dkLen must be positive" }

        val mfLenBytes = r * 128
        val bytes = pbkdf2HmacSha256SingleIteration(passphraseBytes, salt, p * mfLenBytes)
        val bLen = bytes.size ushr 2
        val b = IntArray(bLen)
        try {
            littleEndianToInt(bytes, b)

            var d = 0
            var total = n * r
            while ((n - d) > 2 && total > (1 shl 10)) {
                d++
                total = total ushr 1
            }

            val mfLenWords = mfLenBytes ushr 2
            var bOff = 0
            while (bOff < bLen) {
                sMix(b, bOff, n, d, r)
                bOff += mfLenWords
            }

            intToLittleEndian(b, bytes)
            return pbkdf2HmacSha256SingleIteration(passphraseBytes, bytes, dkLen)
        } finally {
            bytes.fill(0)
            b.fill(0)
        }
    }

    private fun sMix(b: IntArray, bOff: Int, n: Int, d: Int, r: Int) {
        val powN = Integer.numberOfTrailingZeros(n)
        val blocksPerChunk = n ushr d
        val chunkCount = 1 shl d
        val chunkMask = blocksPerChunk - 1
        val chunkPow = powN - d
        val bCount = r * 32

        val blockX1 = IntArray(16)
        val blockX2 = IntArray(16)
        val blockY = IntArray(bCount)
        val x = IntArray(bCount)
        val vv = arrayOfNulls<IntArray>(chunkCount)

        try {
            System.arraycopy(b, bOff, x, 0, bCount)

            for (c in 0 until chunkCount) {
                val v = IntArray(blocksPerChunk * bCount)
                vv[c] = v
                var off = 0
                var i = 0
                while (i < blocksPerChunk) {
                    System.arraycopy(x, 0, v, off, bCount)
                    off += bCount
                    blockMix(x, blockX1, blockX2, blockY, r)
                    System.arraycopy(blockY, 0, v, off, bCount)
                    off += bCount
                    blockMix(blockY, blockX1, blockX2, x, r)
                    i += 2
                }
            }

            val mask = n - 1
            for (i in 0 until n) {
                val j = x[bCount - 16] and mask
                val v = vv[j ushr chunkPow]!!
                val vOff = (j and chunkMask) * bCount
                for (k in 0 until bCount) {
                    blockY[k] = v[vOff + k] xor x[k]
                }
                blockMix(blockY, blockX1, blockX2, x, r)
            }

            System.arraycopy(x, 0, b, bOff, bCount)
        } finally {
            for (chunk in vv) chunk?.fill(0)
            x.fill(0)
            blockX1.fill(0)
            blockX2.fill(0)
            blockY.fill(0)
        }
    }

    private fun blockMix(b: IntArray, x1: IntArray, x2: IntArray, y: IntArray, r: Int) {
        System.arraycopy(b, b.size - 16, x1, 0, 16)
        var bOff = 0
        var yOff = 0
        val halfLen = b.size ushr 1

        for (i in 2 * r downTo 1) {
            for (k in 0 until 16) {
                x2[k] = x1[k] xor b[bOff + k]
            }
            salsa208Core(x2, x1)
            System.arraycopy(x1, 0, y, yOff, 16)
            yOff = halfLen + bOff - yOff
            bOff += 16
        }
    }

    private fun salsa208Core(input: IntArray, out: IntArray) {
        var x00 = input[0]
        var x01 = input[1]
        var x02 = input[2]
        var x03 = input[3]
        var x04 = input[4]
        var x05 = input[5]
        var x06 = input[6]
        var x07 = input[7]
        var x08 = input[8]
        var x09 = input[9]
        var x10 = input[10]
        var x11 = input[11]
        var x12 = input[12]
        var x13 = input[13]
        var x14 = input[14]
        var x15 = input[15]

        var i = 8
        while (i > 0) {
            x04 = x04 xor Integer.rotateLeft(x00 + x12, 7)
            x08 = x08 xor Integer.rotateLeft(x04 + x00, 9)
            x12 = x12 xor Integer.rotateLeft(x08 + x04, 13)
            x00 = x00 xor Integer.rotateLeft(x12 + x08, 18)
            x09 = x09 xor Integer.rotateLeft(x05 + x01, 7)
            x13 = x13 xor Integer.rotateLeft(x09 + x05, 9)
            x01 = x01 xor Integer.rotateLeft(x13 + x09, 13)
            x05 = x05 xor Integer.rotateLeft(x01 + x13, 18)
            x14 = x14 xor Integer.rotateLeft(x10 + x06, 7)
            x02 = x02 xor Integer.rotateLeft(x14 + x10, 9)
            x06 = x06 xor Integer.rotateLeft(x02 + x14, 13)
            x10 = x10 xor Integer.rotateLeft(x06 + x02, 18)
            x03 = x03 xor Integer.rotateLeft(x15 + x11, 7)
            x07 = x07 xor Integer.rotateLeft(x03 + x15, 9)
            x11 = x11 xor Integer.rotateLeft(x07 + x03, 13)
            x15 = x15 xor Integer.rotateLeft(x11 + x07, 18)

            x01 = x01 xor Integer.rotateLeft(x00 + x03, 7)
            x02 = x02 xor Integer.rotateLeft(x01 + x00, 9)
            x03 = x03 xor Integer.rotateLeft(x02 + x01, 13)
            x00 = x00 xor Integer.rotateLeft(x03 + x02, 18)
            x06 = x06 xor Integer.rotateLeft(x05 + x04, 7)
            x07 = x07 xor Integer.rotateLeft(x06 + x05, 9)
            x04 = x04 xor Integer.rotateLeft(x07 + x06, 13)
            x05 = x05 xor Integer.rotateLeft(x04 + x07, 18)
            x11 = x11 xor Integer.rotateLeft(x10 + x09, 7)
            x08 = x08 xor Integer.rotateLeft(x11 + x10, 9)
            x09 = x09 xor Integer.rotateLeft(x08 + x11, 13)
            x10 = x10 xor Integer.rotateLeft(x09 + x08, 18)
            x12 = x12 xor Integer.rotateLeft(x15 + x14, 7)
            x13 = x13 xor Integer.rotateLeft(x12 + x15, 9)
            x14 = x14 xor Integer.rotateLeft(x13 + x12, 13)
            x15 = x15 xor Integer.rotateLeft(x14 + x13, 18)
            i -= 2
        }

        out[0] = x00 + input[0]
        out[1] = x01 + input[1]
        out[2] = x02 + input[2]
        out[3] = x03 + input[3]
        out[4] = x04 + input[4]
        out[5] = x05 + input[5]
        out[6] = x06 + input[6]
        out[7] = x07 + input[7]
        out[8] = x08 + input[8]
        out[9] = x09 + input[9]
        out[10] = x10 + input[10]
        out[11] = x11 + input[11]
        out[12] = x12 + input[12]
        out[13] = x13 + input[13]
        out[14] = x14 + input[14]
        out[15] = x15 + input[15]
    }

    /**
     * Single-iteration PBKDF2-HMAC-SHA256 (`c = 1`) matching BouncyCastle's
     * `PKCS5S2ParametersGenerator(SHA256Digest())` for any key length (including empty 0-byte keys).
     */
    private fun pbkdf2HmacSha256SingleIteration(
        keyBytes: ByteArray,
        salt: ByteArray,
        dkLen: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val hmacKey = if (keyBytes.isEmpty()) ByteArray(64) else keyBytes
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))

        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(dkLen)
        val blockIdx = ByteArray(4)
        var outOff = 0

        for (block in 1..blocks) {
            blockIdx[0] = (block ushr 24).toByte()
            blockIdx[1] = (block ushr 16).toByte()
            blockIdx[2] = (block ushr 8).toByte()
            blockIdx[3] = block.toByte()

            mac.update(salt)
            val digest = mac.doFinal(blockIdx)
            val copyLen = minOf(hLen, dkLen - outOff)
            System.arraycopy(digest, 0, out, outOff, copyLen)
            outOff += copyLen
            digest.fill(0)
        }
        return out
    }

    private fun littleEndianToInt(bs: ByteArray, ns: IntArray) {
        var off = 0
        for (i in ns.indices) {
            ns[i] = (bs[off].toInt() and 0xFF) or
                ((bs[off + 1].toInt() and 0xFF) shl 8) or
                ((bs[off + 2].toInt() and 0xFF) shl 16) or
                (bs[off + 3].toInt() shl 24)
            off += 4
        }
    }

    private fun intToLittleEndian(ns: IntArray, bs: ByteArray) {
        var off = 0
        for (n in ns) {
            bs[off] = n.toByte()
            bs[off + 1] = (n ushr 8).toByte()
            bs[off + 2] = (n ushr 16).toByte()
            bs[off + 3] = (n ushr 24).toByte()
            off += 4
        }
    }

    private fun encodeUtf8Exact(chars: CharArray): ByteArray {
        if (chars.isEmpty()) return ByteArray(0)
        val buf: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(buf.limit()).also { buf.get(it) }
        } finally {
            if (buf.hasArray()) buf.array().fill(0)
        }
    }

    internal fun encodeUtf8LegacyPadded(chars: CharArray): ByteArray {
        val buf: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        return if (buf.hasArray()) {
            val copy = buf.array().copyOf()
            buf.array().fill(0)
            copy
        } else {
            ByteArray(buf.limit()).also { buf.get(it) }
        }
    }

    internal fun decodeHex(hex: String?, expectedBytes: Int? = null, fieldName: String = "hex"): ByteArray {
        if (hex == null) {
            throw IllegalArgumentException("Missing $fieldName")
        }
        val clean = hex.trim()
        if (clean.length % 2 != 0) {
            throw IllegalArgumentException("Odd-length hex string in $fieldName")
        }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                throw IllegalArgumentException("Invalid hex character in $fieldName")
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        if (expectedBytes != null && out.size != expectedBytes) {
            throw IllegalArgumentException("Expected $expectedBytes bytes in $fieldName, got ${out.size}")
        }
        return out
    }

    internal fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val hexDigits = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hexDigits[v ushr 4]
            chars[i * 2 + 1] = hexDigits[v and 0x0F]
        }
        return String(chars)
    }

    /**
     * Generates an authentic encrypted Aegis v1/v3 vault JSON payload for unit and UI testing.
     */
    fun createEncryptedAegisVaultBytesForTesting(
        password: String,
        dbJsonObject: String,
        scryptN: Int = 1024,
        scryptR: Int = 8,
        scryptP: Int = 1
    ): ByteArray {
        val random = SecureRandom()
        val masterKey = ByteArray(AES_KEY_BYTES).also { random.nextBytes(it) }
        val salt = ByteArray(32).also { random.nextBytes(it) }

        val passBytes = password.toByteArray(StandardCharsets.UTF_8)
        val slotKey = scrypt(passBytes, salt, scryptN, scryptR, scryptP, AES_KEY_BYTES)

        val slotCipher = Cipher.getInstance(AES_GCM_CIPHER)
        slotCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(slotKey, "AES"))
        val wrappedWithTag = slotCipher.doFinal(masterKey)
        val wrappedMaster = wrappedWithTag.copyOfRange(0, wrappedWithTag.size - 16)
        val slotTag = wrappedWithTag.copyOfRange(wrappedWithTag.size - 16, wrappedWithTag.size)
        val slotNonce = slotCipher.iv

        val dbCipher = Cipher.getInstance(AES_GCM_CIPHER)
        dbCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
        val dbWithTag = dbCipher.doFinal(dbJsonObject.toByteArray(StandardCharsets.UTF_8))
        val dbCiphertext = dbWithTag.copyOfRange(0, dbWithTag.size - 16)
        val dbTag = dbWithTag.copyOfRange(dbWithTag.size - 16, dbWithTag.size)
        val dbNonce = dbCipher.iv

        masterKey.fill(0)
        slotKey.fill(0)

        val root = JsonObject().apply {
            addProperty("version", 1)
            add("header", JsonObject().apply {
                add("slots", com.google.gson.JsonArray().apply {
                    // Include a dummy type=2 biometric slot too, just like real Aegis vaults!
                    add(JsonObject().apply {
                        addProperty("type", 2)
                        addProperty("uuid", UUID.randomUUID().toString())
                        addProperty("key", toHex(ByteArray(32)))
                        add("key_params", JsonObject().apply {
                            addProperty("nonce", toHex(ByteArray(12)))
                            addProperty("tag", toHex(ByteArray(16)))
                        })
                    })
                    add(JsonObject().apply {
                        addProperty("type", SLOT_TYPE_PASSWORD)
                        addProperty("uuid", UUID.randomUUID().toString())
                        addProperty("key", toHex(wrappedMaster))
                        add("key_params", JsonObject().apply {
                            addProperty("nonce", toHex(slotNonce))
                            addProperty("tag", toHex(slotTag))
                        })
                        addProperty("n", scryptN)
                        addProperty("r", scryptR)
                        addProperty("p", scryptP)
                        addProperty("salt", toHex(salt))
                        addProperty("repaired", true)
                    })
                })
                add("params", JsonObject().apply {
                    addProperty("nonce", toHex(dbNonce))
                    addProperty("tag", toHex(dbTag))
                })
            })
            addProperty("db", Base64.Default.encode(dbCiphertext))
        }
        return Gson().toJson(root).toByteArray(StandardCharsets.UTF_8)
    }
}
