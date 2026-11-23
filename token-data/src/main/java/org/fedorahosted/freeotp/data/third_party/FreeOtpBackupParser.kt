package org.fedorahosted.freeotp.data.third_party

import com.google.android.apps.authenticator.Base32String
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses and decrypts backup files exported by upstream FreeOTP Android
 * (https://github.com/freeotp/freeotp-android):
 *
 * 1. FreeOTP v2+ `externalBackup.xml`: Java [ObjectOutputStream] `HashMap<String, Object>`
 *    containing a PBKDF2-wrapped `masterKey` JSON and AES/GCM/NoPadding `EncryptedKey` +
 *    `Token` JSON pairs (`<uuid>` and `<uuid>-token`).
 * 2. FreeOTP v2+ `tokenBackup.xml`: Android SharedPreferences `<map>` XML with the same
 *    `masterKey` + `<uuid>` / `<uuid>-token` entries.
 * 3. Legacy FreeOTP v1 `tokens.xml` (SharedPreferences XML or serialized `Map`): unencrypted
 *    `tokenOrder` + legacy `Token` JSON or `otpauth://` URI entries.
 */
object FreeOtpBackupParser {

    internal const val MAX_BACKUP_BYTES = 16 * 1024 * 1024 // 16 MB safety cap
    private const val MAX_MAP_ENTRIES = 10_000
    private const val MASTER_KEY_ENTRY = "masterKey"
    private const val RESTORE_COMPLETE_ENTRY = "restoreComplete"
    private const val TOKEN_ORDER_ENTRY = "tokenOrder"
    private const val TOKEN_SUFFIX = "-token"
    private const val AES_GCM_CIPHER = "AES/GCM/NoPadding"
    private const val DEFAULT_GCM_TAG_BITS = 128

    private val ALLOWED_SERIALIZED_CLASSES = setOf(
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "java.util.TreeMap",
        "java.util.Collections\$UnmodifiableMap",
        "java.util.Collections\$EmptyMap",
        "java.util.Collections\$SingletonMap",
        "java.lang.String",
        "java.lang.Boolean",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
        "java.lang.Number"
    )

    internal class UpstreamEncryptedKey(
        @SerializedName("mCipherText")
        val mCipherText: ByteArray? = null,
        @SerializedName("mParameters")
        val mParameters: ByteArray? = null,
        @SerializedName("mCipher")
        val mCipher: String? = null,
        @SerializedName("mToken")
        val mToken: String? = null
    )

    internal class UpstreamMasterKey(
        @SerializedName("mEncryptedKey")
        val mEncryptedKey: UpstreamEncryptedKey? = null,
        @SerializedName("mAlgorithm")
        val mAlgorithm: String? = null,
        @SerializedName("mIterations")
        val mIterations: Int = 0,
        @SerializedName("mSalt")
        val mSalt: ByteArray? = null
    )

    internal class UpstreamTokenJson(
        @SerializedName("algo")
        val algo: String? = null,
        @SerializedName("issuerExt")
        val issuerExt: String? = null,
        @SerializedName("issuerInt")
        val issuerInt: String? = null,
        @SerializedName("issuerAlt")
        val issuerAlt: String? = null,
        @SerializedName("label")
        val label: String? = null,
        @SerializedName("labelAlt")
        val labelAlt: String? = null,
        @SerializedName("image")
        val image: String? = null,
        @SerializedName("imageAlt")
        val imageAlt: String? = null,
        @SerializedName("period")
        val period: Int? = null,
        @SerializedName("digits")
        val digits: Int? = null,
        @SerializedName("type")
        val type: String? = null,
        @SerializedName("counter")
        val counter: Long? = null,
        @SerializedName("secret")
        val secret: ByteArray? = null
    )

    data class BackupInspection(
        val requiresPassword: Boolean
    )

    fun inspectBackup(bytes: ByteArray): BackupInspection {
        val entries = parseBackupMap(bytes)
        val masterKeyJson = entries[MASTER_KEY_ENTRY] as? String
        if (!masterKeyJson.isNullOrBlank()) {
            return BackupInspection(requiresPassword = true)
        }
        val tokenOrderJson = entries[TOKEN_ORDER_ENTRY] as? String
        if (!tokenOrderJson.isNullOrBlank()) {
            return BackupInspection(requiresPassword = false)
        }
        throw IllegalArgumentException("Unrecognized FreeOTP backup contents")
    }

    fun parseBackup(
        bytes: ByteArray,
        passphrase: CharArray?,
        gson: Gson = Gson()
    ): List<OtpToken> {
        val entries = parseBackupMap(bytes)
        val masterKeyJson = (entries[MASTER_KEY_ENTRY] as? String)?.takeIf { it.isNotBlank() }
        if (masterKeyJson != null) {
            if (passphrase == null || passphrase.isEmpty()) {
                throw InvalidEncryptionKeyException("Backup password is required")
            }
            return decryptV2BackupEntries(entries, masterKeyJson, passphrase, gson)
        }

        val tokenOrderJson = (entries[TOKEN_ORDER_ENTRY] as? String)?.takeIf { it.isNotBlank() }
        if (tokenOrderJson != null) {
            return parseLegacyV1Entries(entries, tokenOrderJson, gson)
        }

        throw IllegalArgumentException("Unrecognized FreeOTP backup contents")
    }

    internal fun readBoundedBytes(input: InputStream, maxBytes: Int = MAX_BACKUP_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Backup file exceeds maximum supported size")
            }
            out.write(buffer, 0, read)
        }
        if (total == 0) {
            throw IllegalArgumentException("Backup file is empty")
        }
        return out.toByteArray()
    }

    internal fun parseBackupMap(bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty() || bytes.size > MAX_BACKUP_BYTES) {
            throw IllegalArgumentException("Invalid FreeOTP backup size")
        }
        // 1. Java ObjectOutputStream stream magic: 0xAC 0xED
        if (bytes.size >= 4 && bytes[0] == 0xAC.toByte() && bytes[1] == 0xED.toByte()) {
            return readAllowlistedObjectMap(bytes)
        }

        // 2. SharedPreferences XML `<map>` format (`tokenBackup.xml` or `tokens.xml`)
        val text = bytes.toString(StandardCharsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.startsWith("<?xml", ignoreCase = true) || text.startsWith("<map", ignoreCase = true)) {
            return parseSharedPreferencesXmlMap(text)
        }

        throw IllegalArgumentException("File is not a valid FreeOTP backup")
    }

    private fun readAllowlistedObjectMap(bytes: ByteArray): Map<String, Any?> {
        val rawObj = try {
            SafeMapObjectInputStream(ByteArrayInputStream(bytes)).use { ois ->
                ois.readObject()
            }
        } catch (e: InvalidClassException) {
            throw IllegalArgumentException("Unsupported serialized class in FreeOTP backup", e)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException("Unknown serialized class in FreeOTP backup", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("Malformed serialized FreeOTP backup", e)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("Corrupted serialized FreeOTP backup", e)
        }

        val rawMap = rawObj as? Map<*, *>
            ?: throw IllegalArgumentException("FreeOTP backup root object is not a Map")
        if (rawMap.size > MAX_MAP_ENTRIES) {
            throw IllegalArgumentException("FreeOTP backup contains too many entries")
        }

        val result = LinkedHashMap<String, Any?>(rawMap.size)
        for ((k, v) in rawMap) {
            if (k is String) {
                result[k] = v
            }
        }
        return result
    }

    private class SafeMapObjectInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            val name = desc.name
            if (name !in ALLOWED_SERIALIZED_CLASSES) {
                throw InvalidClassException("Disallowed class in FreeOTP backup", name)
            }
            return super.resolveClass(desc)
        }

        override fun resolveProxyClass(interfaces: Array<out String>): Class<*> {
            throw InvalidClassException("Proxy classes are disallowed in FreeOTP backup")
        }
    }

    private fun parseSharedPreferencesXmlMap(xmlText: String): Map<String, Any?> {
        val upperCheck = xmlText.uppercase(Locale.ROOT)
        if (upperCheck.contains("<!DOCTYPE") || upperCheck.contains("<!ENTITY")) {
            throw IllegalArgumentException("DOCTYPE and XML entities are disallowed in FreeOTP backup")
        }

        val doc = try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                isValidating = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val builder = dbf.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> InputSource(StringReader("")) }
            }
            builder.parse(InputSource(StringReader(xmlText)))
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed XML in FreeOTP backup", e)
        }

        val root = doc.documentElement
        if (root == null || root.tagName != "map") {
            throw IllegalArgumentException("XML root element is not <map>")
        }

        val result = LinkedHashMap<String, Any?>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            if (result.size >= MAX_MAP_ENTRIES) {
                throw IllegalArgumentException("FreeOTP backup XML contains too many entries")
            }
            val el = node as Element
            val key = el.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
            when (el.tagName) {
                "string" -> result[key] = el.textContent ?: ""
                "boolean" -> result[key] = el.getAttribute("value").toBoolean()
                "int" -> result[key] = el.getAttribute("value").toIntOrNull()
                "long" -> result[key] = el.getAttribute("value").toLongOrNull()
                "float" -> result[key] = el.getAttribute("value").toFloatOrNull()
            }
        }
        return result
    }

    private fun decryptV2BackupEntries(
        entries: Map<String, Any?>,
        masterKeyJson: String,
        passphrase: CharArray,
        gson: Gson
    ): List<OtpToken> {
        val masterKeyModel = try {
            gson.fromJson(masterKeyJson, UpstreamMasterKey::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed masterKey JSON in FreeOTP backup", e)
        } ?: throw IllegalArgumentException("Missing masterKey in FreeOTP backup")

        val salt = masterKeyModel.mSalt
        val iterations = masterKeyModel.mIterations
        val kdfAlgo = masterKeyModel.mAlgorithm
        val wrappedMasterKey = masterKeyModel.mEncryptedKey
        if (salt == null || salt.isEmpty() || iterations <= 0 || kdfAlgo.isNullOrBlank() || wrappedMasterKey == null) {
            throw IllegalArgumentException("Incomplete masterKey parameters in FreeOTP backup")
        }

        val passwordKeyBytes = derivePbkdf2Key(
            passphrase = passphrase,
            salt = salt,
            iterations = iterations,
            keyLengthBits = salt.size * 8,
            algorithm = kdfAlgo
        )
        var masterKeyBytes: ByteArray? = null
        try {
            val passwordAesKey = SecretKeySpec(passwordKeyBytes, "AES")
            masterKeyBytes = try {
                decryptEncryptedKeyBytes(wrappedMasterKey, passwordAesKey)
            } catch (e: GeneralSecurityException) {
                throw InvalidEncryptionKeyException("Incorrect FreeOTP backup password").apply { initCause(e) }
            }

            val masterSecretKey = SecretKeySpec(masterKeyBytes, "AES")
            val restoredByKey = LinkedHashMap<String, OtpToken>()

            for ((entryKey, rawValue) in entries) {
                if (entryKey == MASTER_KEY_ENTRY ||
                    entryKey == RESTORE_COMPLETE_ENTRY ||
                    entryKey == TOKEN_ORDER_ENTRY ||
                    entryKey.endsWith(TOKEN_SUFFIX)
                ) {
                    continue
                }
                val keyContainerStr = rawValue as? String ?: continue
                val tokenMetaStr = (entries[entryKey + TOKEN_SUFFIX] as? String)?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing token metadata for entry $entryKey")

                val encryptedTokenKey = parseEncryptedTokenWrapper(keyContainerStr, gson)
                val rawSecretBytes = try {
                    decryptEncryptedKeyBytes(encryptedTokenKey, masterSecretKey)
                } catch (e: GeneralSecurityException) {
                    throw IllegalArgumentException("Corrupted encrypted token secret for entry $entryKey", e)
                }

                try {
                    val base32Secret = Base32String.encode(rawSecretBytes)
                    val defaultHmacAlgo = encryptedTokenKey.mToken?.removePrefix("Hmac")?.ifBlank { "SHA1" } ?: "SHA1"
                    restoredByKey[entryKey] = buildOtpTokenFromMetadata(
                        tokenMetaStr = tokenMetaStr,
                        base32Secret = base32Secret,
                        fallbackAlgorithm = defaultHmacAlgo,
                        gson = gson
                    )
                } finally {
                    rawSecretBytes.fill(0)
                }
            }

            return orderRestoredTokens(restoredByKey, entries[TOKEN_ORDER_ENTRY] as? String, gson)
        } finally {
            passwordKeyBytes.fill(0)
            masterKeyBytes?.fill(0)
        }
    }

    private fun parseEncryptedTokenWrapper(wrapperJson: String, gson: Gson): UpstreamEncryptedKey {
        val rootObj = try {
            JsonParser.parseString(wrapperJson).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed token key wrapper JSON in FreeOTP backup", e)
        }
        val keyElement: JsonElement = rootObj.get("key")
            ?: throw IllegalArgumentException("Missing 'key' field in FreeOTP token wrapper")

        val parsed = try {
            when {
                keyElement.isJsonPrimitive && keyElement.asJsonPrimitive.isString ->
                    gson.fromJson(keyElement.asString, UpstreamEncryptedKey::class.java)
                keyElement.isJsonObject ->
                    gson.fromJson(keyElement, UpstreamEncryptedKey::class.java)
                else -> null
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed EncryptedKey JSON in FreeOTP backup", e)
        }
        return parsed ?: throw IllegalArgumentException("Null EncryptedKey in FreeOTP backup")
    }

    private fun buildOtpTokenFromMetadata(
        tokenMetaStr: String,
        base32Secret: String,
        fallbackAlgorithm: String,
        gson: Gson
    ): OtpToken {
        val trimmed = tokenMetaStr.trim()
        if (trimmed.startsWith("otpauth://", ignoreCase = true)) {
            return parseOtpAuthUriWithSecretOverride(trimmed, base32Secret)
        }

        val meta = try {
            gson.fromJson(trimmed, UpstreamTokenJson::class.java)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed Token JSON in FreeOTP backup", e)
        } ?: throw IllegalArgumentException("Empty Token JSON in FreeOTP backup")

        val resolvedIssuer = meta.issuerAlt?.takeIf { it.isNotBlank() }
            ?: meta.issuerExt?.takeIf { it.isNotBlank() }
            ?: meta.issuerInt?.takeIf { it.isNotBlank() }

        val resolvedLabel = meta.labelAlt ?: meta.label.orEmpty()
        val resolvedType = if (meta.type.equals("HOTP", ignoreCase = true)) {
            OtpTokenType.HOTP
        } else {
            OtpTokenType.TOTP
        }
        val resolvedAlgorithm = (meta.algo?.takeIf { it.isNotBlank() } ?: fallbackAlgorithm).uppercase(Locale.US)
        val isSteam = resolvedIssuer == "Steam" || meta.issuerExt == "Steam" || meta.issuerInt == "Steam"
        val resolvedDigits = when {
            meta.digits != null && meta.digits > 0 -> meta.digits
            isSteam -> 5
            else -> 6
        }
        val resolvedPeriod = if (meta.period != null && meta.period > 0) meta.period else 30
        val resolvedCounter = if (resolvedType == OtpTokenType.HOTP) {
            (meta.counter ?: 0L).coerceAtLeast(0L)
        } else {
            0L
        }
        val rawImage = meta.imageAlt?.takeIf { it.isNotBlank() } ?: meta.image?.takeIf { it.isNotBlank() }

        return OtpTokenFactory.create(
            type = resolvedType,
            issuer = resolvedIssuer,
            label = resolvedLabel,
            secret = base32Secret,
            algorithm = resolvedAlgorithm,
            digits = resolvedDigits,
            period = resolvedPeriod,
            initialCounter = resolvedCounter,
            imagePath = rawImage,
            isSteamIssuerOverride = isSteam
        )
    }

    private fun parseLegacyV1Entries(
        entries: Map<String, Any?>,
        tokenOrderJson: String,
        gson: Gson
    ): List<OtpToken> {
        val listType = object : TypeToken<List<String>>() {}.type
        val orderedKeys: List<String> = try {
            gson.fromJson<List<String>>(tokenOrderJson, listType).orEmpty()
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed tokenOrder in FreeOTP v1 backup", e)
        }

        val tokens = ArrayList<OtpToken>(orderedKeys.size)
        for (tokenKey in orderedKeys) {
            val rawVal = (entries[tokenKey] as? String)?.takeIf { it.isNotBlank() } ?: continue
            val trimmed = rawVal.trim()
            val token = if (trimmed.startsWith("{")) {
                val meta = try {
                    gson.fromJson(trimmed, UpstreamTokenJson::class.java)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Malformed legacy token JSON for $tokenKey", e)
                } ?: continue
                val secretBytes = meta.secret ?: throw IllegalArgumentException("Missing secret in legacy token $tokenKey")
                try {
                    if (secretBytes.isEmpty()) {
                        throw IllegalArgumentException("Empty secret in legacy token $tokenKey")
                    }
                    val base32Secret = Base32String.encode(secretBytes)
                    buildOtpTokenFromMetadata(trimmed, base32Secret, meta.algo ?: "SHA1", gson)
                } finally {
                    secretBytes.fill(0)
                }
            } else {
                parseOtpAuthUriWithSecretOverride(trimmed, secretOverride = null)
            }
            tokens.add(token)
        }
        return tokens
    }

    private fun parseOtpAuthUriWithSecretOverride(uriStr: String, secretOverride: String?): OtpToken {
        val parsed = try {
            URI(uriStr)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid otpauth URI in FreeOTP backup", e)
        }
        if (!parsed.scheme.equals("otpauth", ignoreCase = true)) {
            throw IllegalArgumentException("URI scheme is not otpauth")
        }
        val type = when (parsed.host?.lowercase(Locale.US)) {
            "totp" -> OtpTokenType.TOTP
            "hotp" -> OtpTokenType.HOTP
            else -> throw IllegalArgumentException("Unsupported OTP type in URI: ${parsed.host}")
        }
        val rawPath = parsed.rawPath?.trimStart('/') ?: throw IllegalArgumentException("Missing path in otpauth URI")
        val decodedPath = urlDecode(rawPath)
        if (decodedPath.isEmpty()) {
            throw IllegalArgumentException("Empty path in otpauth URI")
        }

        val queryParams = parseQueryParams(parsed.rawQuery)
        val issuerInt = queryParams["issuer"]
        val (issuerFromPath, labelFromPath, issuerExt) = OtpTokenFactory.splitIssuerAndLabel(decodedPath, issuerInt)
        val resolvedIssuer = queryParams["issuerAlt"]?.takeIf { it.isNotBlank() }
            ?: queryParams["issueralt"]?.takeIf { it.isNotBlank() }
            ?: issuerFromPath
        val resolvedLabel = queryParams["mLabelAlt"]?.takeIf { it.isNotEmpty() }
            ?: queryParams["labelalt"]?.takeIf { it.isNotEmpty() }
            ?: labelFromPath

        val rawSecret = secretOverride
            ?: queryParams["secret"]?.uppercase(Locale.US)
            ?: throw IllegalArgumentException("Missing secret in otpauth URI")

        val isSteam = resolvedIssuer == "Steam" || issuerExt == "Steam"
        val digits = (queryParams["digits"] ?: if (isSteam) "5" else "6").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid digits in otpauth URI")
        val period = (queryParams["period"] ?: "30").toIntOrNull()
            ?: throw IllegalArgumentException("Invalid period in otpauth URI")
        val counter = if (type == OtpTokenType.HOTP) {
            (queryParams["counter"] ?: "0").toLongOrNull()
                ?: throw IllegalArgumentException("Invalid counter in otpauth URI")
        } else {
            0L
        }

        return OtpTokenFactory.create(
            type = type,
            issuer = resolvedIssuer,
            label = resolvedLabel,
            secret = rawSecret,
            algorithm = queryParams["algorithm"] ?: "SHA1",
            digits = digits,
            period = period,
            initialCounter = counter,
            imagePath = queryParams["image"],
            isSteamIssuerOverride = isSteam
        )
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                map.putIfAbsent(urlDecode(pair), "")
            } else {
                val k = urlDecode(pair.substring(0, eq))
                val v = urlDecode(pair.substring(eq + 1))
                map.putIfAbsent(k, v)
            }
        }
        return map
    }

    private fun urlDecode(value: String): String {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }

    private fun orderRestoredTokens(
        restoredByKey: Map<String, OtpToken>,
        tokenOrderJson: String?,
        gson: Gson
    ): List<OtpToken> {
        if (restoredByKey.isEmpty()) return emptyList()
        val remaining = LinkedHashMap(restoredByKey)
        val ordered = ArrayList<OtpToken>(restoredByKey.size)

        if (!tokenOrderJson.isNullOrBlank()) {
            val listType = object : TypeToken<List<String>>() {}.type
            val orderKeys = runCatching { gson.fromJson<List<String>>(tokenOrderJson, listType) }.getOrNull().orEmpty()
            for (k in orderKeys) {
                remaining.remove(k)?.let { ordered.add(it) }
            }
        }

        val sortedRemaining = remaining.entries.sortedWith(
            compareBy<Map.Entry<String, OtpToken>>(
                { it.value.issuer.orEmpty().lowercase(Locale.ROOT) },
                { it.value.label.lowercase(Locale.ROOT) },
                { it.key }
            )
        )
        for (entry in sortedRemaining) {
            ordered.add(entry.value)
        }
        return ordered
    }

    @Throws(GeneralSecurityException::class)
    internal fun decryptEncryptedKeyBytes(
        encryptedKey: UpstreamEncryptedKey,
        wrappingKey: SecretKey
    ): ByteArray {
        val cipherText = encryptedKey.mCipherText
            ?: throw IllegalArgumentException("Missing mCipherText in FreeOTP EncryptedKey")
        val paramsBytes = encryptedKey.mParameters
            ?: throw IllegalArgumentException("Missing mParameters in FreeOTP EncryptedKey")
        val cipherName = encryptedKey.mCipher ?: AES_GCM_CIPHER
        if (!cipherName.equals(AES_GCM_CIPHER, ignoreCase = true)) {
            throw IllegalArgumentException("Unsupported cipher in FreeOTP backup: $cipherName")
        }
        val tokenAlgo = encryptedKey.mToken
            ?: throw IllegalArgumentException("Missing mToken AAD in FreeOTP EncryptedKey")
        val aadBytes = tokenAlgo.toByteArray(StandardCharsets.UTF_8)

        // 1. Primary path: mirror upstream EncryptedKey.decrypt via JCA AlgorithmParameters
        var primarySecurityError: GeneralSecurityException? = null
        try {
            val cipher = Cipher.getInstance(AES_GCM_CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val paramAlgo = cipher.parameters.algorithm
            val ap = AlgorithmParameters.getInstance(paramAlgo)
            ap.init(paramsBytes)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey, ap)
            cipher.updateAAD(aadBytes)
            return cipher.doFinal(cipherText)
        } catch (e: GeneralSecurityException) {
            primarySecurityError = e
        } catch (_: IOException) {
            // Fall through to explicit ASN.1 DER / raw IV GCMParameterSpec parser
        }

        // 2. Cross-provider fallback: parse GCMParameterSpec directly from ASN.1 DER / raw IV bytes
        val gcmSpec = try {
            parseGcmParameterSpec(paramsBytes)
        } catch (e: IllegalArgumentException) {
            if (primarySecurityError != null) throw primarySecurityError
            throw e
        }
        val fallbackCipher = Cipher.getInstance(AES_GCM_CIPHER)
        fallbackCipher.init(Cipher.DECRYPT_MODE, wrappingKey, gcmSpec)
        fallbackCipher.updateAAD(aadBytes)
        return fallbackCipher.doFinal(cipherText)
    }

    /**
     * Parses [GCMParameterSpec] from JCE `AlgorithmParameters.getEncoded()` formats produced
     * across Android / Conscrypt / BouncyCastle / OpenJDK versions:
     * - RFC 5084 ASN.1 DER `GCMParameters ::= SEQUENCE { aes-nonce OCTET STRING, aes-ICVlen INTEGER OPTIONAL }`
     * - ASN.1 DER `OCTET STRING` (`0x04 <len> <iv>`)
     * - Raw 12-byte IV (`<12-byte iv>`)
     */
    internal fun parseGcmParameterSpec(encoded: ByteArray): GCMParameterSpec {
        if (encoded.isEmpty()) {
            throw IllegalArgumentException("Empty GCM parameters")
        }
        if (encoded.size == 12) {
            return GCMParameterSpec(DEFAULT_GCM_TAG_BITS, encoded)
        }
        if (encoded[0] == 0x04.toByte()) {
            val (ivLen, ivOffset) = readDerLength(encoded, 1)
            if (ivLen > 0 && ivOffset + ivLen == encoded.size) {
                return GCMParameterSpec(DEFAULT_GCM_TAG_BITS, encoded.copyOfRange(ivOffset, ivOffset + ivLen))
            }
        }
        if (encoded[0] == 0x30.toByte()) {
            val (seqLen, seqStart) = readDerLength(encoded, 1)
            val seqEnd = seqStart + seqLen
            if (seqLen <= 0 || seqEnd > encoded.size) {
                throw IllegalArgumentException("Invalid DER SEQUENCE length in GCM parameters")
            }
            if (encoded[seqStart] != 0x04.toByte()) {
                throw IllegalArgumentException("Expected OCTET STRING nonce in DER GCM parameters")
            }
            val (ivLen, ivStart) = readDerLength(encoded, seqStart + 1)
            val afterIv = ivStart + ivLen
            if (ivLen <= 0 || afterIv > seqEnd) {
                throw IllegalArgumentException("Invalid DER nonce length in GCM parameters")
            }
            val iv = encoded.copyOfRange(ivStart, afterIv)
            var tagBits = DEFAULT_GCM_TAG_BITS
            if (afterIv < seqEnd && encoded[afterIv] == 0x02.toByte()) {
                val (intLen, intStart) = readDerLength(encoded, afterIv + 1)
                if (intLen in 1..2 && intStart + intLen <= seqEnd) {
                    var value = 0
                    for (i in 0 until intLen) {
                        value = (value shl 8) or (encoded[intStart + i].toInt() and 0xFF)
                    }
                    tagBits = when {
                        value in 12..16 -> value * 8
                        value in 96..128 && value % 8 == 0 -> value
                        else -> throw IllegalArgumentException("Unsupported GCM tag length: $value")
                    }
                }
            }
            return GCMParameterSpec(tagBits, iv)
        }
        throw IllegalArgumentException("Unrecognized GCM parameter encoding")
    }

    private fun readDerLength(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        if (offset >= bytes.size) {
            throw IllegalArgumentException("Truncated DER length")
        }
        val first = bytes[offset].toInt() and 0xFF
        if ((first and 0x80) == 0) {
            return first to (offset + 1)
        }
        val numBytes = first and 0x7F
        if (numBytes == 0 || numBytes > 2 || offset + 1 + numBytes > bytes.size) {
            throw IllegalArgumentException("Unsupported DER length encoding")
        }
        var length = 0
        for (i in 0 until numBytes) {
            length = (length shl 8) or (bytes[offset + 1 + i].toInt() and 0xFF)
        }
        return length to (offset + 1 + numBytes)
    }

    internal fun derivePbkdf2Key(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
        algorithm: String
    ): ByteArray {
        val candidateNames = buildList {
            add(algorithm)
            if (algorithm.equals("PBKDF2withHmacSHA512", ignoreCase = true)) {
                add("PBKDF2WithHmacSHA512")
            } else if (algorithm.equals("PBKDF2withHmacSHA1", ignoreCase = true)) {
                add("PBKDF2WithHmacSHA1")
            }
        }.distinct()

        for (candidate in candidateNames) {
            try {
                val skf = SecretKeyFactory.getInstance(candidate)
                val spec = PBEKeySpec(passphrase, salt, iterations, keyLengthBits)
                return try {
                    skf.generateSecret(spec).encoded
                } finally {
                    spec.clearPassword()
                }
            } catch (_: NoSuchAlgorithmException) {
                // Try next alias or fallback to pure Mac-based RFC 2898 PBKDF2 (e.g. SHA-512 on API 24-25)
            }
        }

        val hmacAlgo = when {
            algorithm.endsWith("SHA512", ignoreCase = true) -> "HmacSHA512"
            algorithm.endsWith("SHA256", ignoreCase = true) -> "HmacSHA256"
            algorithm.endsWith("SHA1", ignoreCase = true) -> "HmacSHA1"
            else -> throw IllegalArgumentException("Unsupported PBKDF2 algorithm: $algorithm")
        }
        return pbkdf2HmacFallback(
            passphrase = passphrase,
            salt = salt,
            iterations = iterations,
            derivedKeyBytes = keyLengthBits / 8,
            hmacAlgorithm = hmacAlgo
        )
    }

    /**
     * Pure RFC 8018 Section 5.2 PBKDF2-HMAC implementation using [Mac] (available since API 1),
     * used when a device's JCA [SecretKeyFactory] lacks `PBKDF2WithHmacSHA512` (Android API 24–25).
     */
    internal fun pbkdf2HmacFallback(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        derivedKeyBytes: Int,
        hmacAlgorithm: String
    ): ByteArray {
        require(iterations > 0 && derivedKeyBytes > 0) { "Invalid PBKDF2 parameters" }
        val encodedBuf: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val passwordBytes = ByteArray(encodedBuf.remaining())
        encodedBuf.get(passwordBytes)
        if (encodedBuf.hasArray()) {
            encodedBuf.array().fill(0)
        }

        val macKeyBytes = if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes
        try {
            val mac = Mac.getInstance(hmacAlgorithm)
            mac.init(SecretKeySpec(macKeyBytes, hmacAlgorithm))
            val hLen = mac.macLength
            val blocks = (derivedKeyBytes + hLen - 1) / hLen
            val derived = ByteArray(derivedKeyBytes)
            val blockIndexBytes = ByteArray(4)
            var outOffset = 0

            for (block in 1..blocks) {
                blockIndexBytes[0] = (block ushr 24).toByte()
                blockIndexBytes[1] = (block ushr 16).toByte()
                blockIndexBytes[2] = (block ushr 8).toByte()
                blockIndexBytes[3] = block.toByte()

                mac.update(salt)
                var u = mac.doFinal(blockIndexBytes)
                val t = u.copyOf()

                for (i in 2..iterations) {
                    u = mac.doFinal(u)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
                    }
                }

                val copyLen = minOf(hLen, derivedKeyBytes - outOffset)
                System.arraycopy(t, 0, derived, outOffset, copyLen)
                outOffset += copyLen
                u.fill(0)
                t.fill(0)
            }
            return derived
        } finally {
            passwordBytes.fill(0)
            macKeyBytes.fill(0)
        }
    }

    /**
     * Generates a byte-identical upstream FreeOTP v2 `externalBackup.xml` serialized `HashMap`
     * payload for testing (mirroring `MasterKey.generate`, `EncryptedKey.encrypt`, and
     * `TokenPersistence.copyBackupToExternal`).
     */
    fun createV2ExternalBackupBytesForTesting(
        password: String,
        tokens: List<OtpToken>,
        iterations: Int = 1_000,
        pbkdf2Algorithm: String = "PBKDF2WithHmacSHA512",
        gson: Gson = Gson()
    ): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(32).also { random.nextBytes(it) }
        val passwordKeyBytes = derivePbkdf2Key(
            passphrase = password.toCharArray(),
            salt = salt,
            iterations = iterations,
            keyLengthBits = salt.size * 8,
            algorithm = pbkdf2Algorithm
        )
        val rawMasterBytes = ByteArray(32).also { random.nextBytes(it) }
        val passwordAesKey = SecretKeySpec(passwordKeyBytes, "AES")
        val masterAesKey = SecretKeySpec(rawMasterBytes, "AES")

        val wrappedMaster = encryptUpstreamKeyForTesting(passwordAesKey, masterAesKey)
        val masterKeyObj = UpstreamMasterKey(
            mEncryptedKey = wrappedMaster,
            mAlgorithm = pbkdf2Algorithm,
            mIterations = iterations,
            mSalt = salt
        )

        val backupMap = HashMap<String, Any>()
        backupMap[MASTER_KEY_ENTRY] = gson.toJson(masterKeyObj)

        tokens.forEachIndexed { idx, token ->
            val uuid = String.format(Locale.US, "00000000-0000-4000-8000-%012d", idx + 1)
            val secretBytes = Base32String.decode(token.secret)
            val tokenSecretKey = SecretKeySpec(secretBytes, "Hmac${token.algorithm}")
            val encryptedTokenKey = encryptUpstreamKeyForTesting(masterAesKey, tokenSecretKey)
            val encryptedCodeKeyJson = gson.toJson(encryptedTokenKey)
            backupMap[uuid] = gson.toJson(mapOf("key" to encryptedCodeKeyJson))

            val upstreamTokenJson = UpstreamTokenJson(
                algo = token.algorithm,
                issuerExt = token.issuer,
                issuerInt = token.issuer,
                label = token.label,
                image = token.imagePath,
                period = token.period,
                digits = token.digits,
                type = token.tokenType.name,
                counter = if (token.tokenType == OtpTokenType.HOTP) token.counter else null
            )
            backupMap[uuid + TOKEN_SUFFIX] = gson.toJson(upstreamTokenJson)
        }

        passwordKeyBytes.fill(0)
        rawMasterBytes.fill(0)

        return ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { oos ->
                oos.writeObject(backupMap)
            }
            bos.toByteArray()
        }
    }

    private fun encryptUpstreamKeyForTesting(encryptionKey: SecretKey, plaintextKey: SecretKey): UpstreamEncryptedKey {
        val cipher = Cipher.getInstance(AES_GCM_CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val tokenAlgo = plaintextKey.algorithm
        cipher.updateAAD(tokenAlgo.toByteArray(StandardCharsets.UTF_8))
        val cipherText = cipher.doFinal(plaintextKey.encoded)
        val params = cipher.parameters.encoded
        return UpstreamEncryptedKey(
            mCipherText = cipherText,
            mParameters = params,
            mCipher = cipher.algorithm,
            mToken = tokenAlgo
        )
    }
}
