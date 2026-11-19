package org.fedorahosted.freeotp.data

import android.net.Uri
import com.google.android.apps.authenticator.Base32String
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import java.security.NoSuchAlgorithmException
import java.util.Locale
import javax.crypto.Mac

object OtpTokenFactory {
    const val QUERY_ENCRYPTION = "encryption"
    const val ENCRYPTION_AES_GCM = "AES_GCM"
    const val QUERY_KDF_SALT = "kdfSalt"
    const val QUERY_KDF_ITERATIONS = "kdfIterations"
    const val QUERY_KEY_VERIFIER = "keyVerifier"
    const val QUERY_CATEGORY = "category"

    fun isEncryptedUri(uri: Uri): Boolean {
        return uri.getQueryParameter(QUERY_ENCRYPTION) == ENCRYPTION_AES_GCM ||
            CryptoEngine.isEncryptedPayload(uri.getQueryParameter("secret"))
    }

    fun create(
        type: OtpTokenType,
        issuer: String?,
        label: String,
        secret: String,
        algorithm: String = "SHA1",
        digits: Int = 6,
        period: Int = 30,
        initialCounter: Long = 0L,
        imagePath: String? = null,
        categories: List<String> = emptyList(),
        isSteamIssuerOverride: Boolean = false
    ): OtpToken {
        val decodedSecret = try {
            Base32String.decode(secret)
        } catch (e: Base32String.DecodingException) {
            throw IllegalArgumentException("Secret is not valid Base32", e)
        }
        if (decodedSecret.isEmpty()) {
            throw IllegalArgumentException("Decoded Base32 secret is empty")
        }

        val normalizedIssuer = issuer?.takeIf { it.isNotBlank() }
        if (normalizedIssuer == null && label.isEmpty()) {
            throw IllegalArgumentException("Token label is empty")
        }

        val normalizedAlgorithm = algorithm.ifBlank { "SHA1" }.uppercase(Locale.US)
        try {
            Mac.getInstance("Hmac$normalizedAlgorithm")
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalArgumentException("Unsupported algorithm: $normalizedAlgorithm", e)
        }

        val isSteam = normalizedIssuer == "Steam" || isSteamIssuerOverride
        if (!isSteam && digits !in 5..8) {
            throw IllegalArgumentException("Digits must be 5 to 8")
        }
        if (period <= 0) {
            throw IllegalArgumentException("Period must be positive")
        }

        val storedCounter = if (type == OtpTokenType.HOTP) initialCounter - 1 else 0L

        return OtpToken(
            id = 0,
            ordinal = -System.currentTimeMillis(), // Places newly created token at the top of the list
            issuer = normalizedIssuer,
            label = label,
            imagePath = imagePath,
            tokenType = type,
            algorithm = normalizedAlgorithm,
            secret = secret,
            digits = digits,
            counter = storedCounter,
            period = period,
            encryptionType = EncryptionType.PLAIN_TEXT,
            categories = CategoryUtil.normalizeCategories(categories)
        )
    }

    fun createFromUri(uri: Uri): OtpToken {
        if (isEncryptedUri(uri)) {
            throw IllegalArgumentException("Encrypted token URI requires decryption key")
        }
        val secret = uri.getQueryParameter("secret") ?: throw IllegalArgumentException("Secret is null")
        return parseTokenUri(uri, secret, includeCategoryFromQuery = false)
    }

    fun createFromEncryptedUri(uri: Uri, decryptedSecret: String): OtpToken {
        return parseTokenUri(uri, decryptedSecret, includeCategoryFromQuery = true)
    }

    private fun parseTokenUri(
        uri: Uri,
        plaintextSecret: String,
        includeCategoryFromQuery: Boolean
    ): OtpToken {
        if (uri.scheme != "otpauth") {
            throw IllegalArgumentException("URI does not starts with otpauth")
        }

        val type = when (uri.authority) {
            "totp" -> OtpTokenType.TOTP
            "hotp" -> OtpTokenType.HOTP
            else -> throw IllegalArgumentException("URI does not contain totp or hotp type")
        }

        val path = uri.path?.trimStart('/') ?: throw IllegalArgumentException("Token path is null")
        if (path.isEmpty()) {
            throw IllegalArgumentException("Token path is empty")
        }

        val colonIndex = path.indexOf(':')
        val issuerExt = if (colonIndex < 0) "" else path.substring(0, colonIndex)
        val issuerInt = uri.getQueryParameter("issuer")
        val issuer = when {
            !issuerInt.isNullOrBlank() -> issuerInt
            issuerExt.isNotBlank() -> issuerExt
            else -> null
        }
        val label = path.substring(if (colonIndex >= 0) colonIndex + 1 else 0)

        val isSteam = issuer == "Steam" || issuerExt == "Steam"
        val digits = (uri.getQueryParameter("digits") ?: if (isSteam) "5" else "6").toInt()
        val period = (uri.getQueryParameter("period") ?: "30").toInt()
        val initialCounter = if (type == OtpTokenType.HOTP) {
            (uri.getQueryParameter("counter") ?: "0").toLong()
        } else {
            0L
        }
        val categories = if (includeCategoryFromQuery) {
            CategoryUtil.normalizeCategories(uri.getQueryParameters(QUERY_CATEGORY))
        } else {
            emptyList()
        }

        return create(
            type = type,
            issuer = issuer,
            label = label,
            secret = plaintextSecret,
            algorithm = uri.getQueryParameter("algorithm") ?: "SHA1",
            digits = digits,
            period = period,
            initialCounter = initialCounter,
            imagePath = uri.getQueryParameter("image"),
            categories = categories,
            isSteamIssuerOverride = isSteam
        )
    }

    fun toUri(token: OtpToken): Uri {
        return buildBaseUriBuilder(token).build()
    }

    fun toEncryptedUri(token: OtpToken, metadata: EncryptionMetadata): Uri {
        require(token.encryptionType == EncryptionType.ENCRYPTED) {
            "Token must be encrypted before building encrypted URI"
        }
        val builder = buildBaseUriBuilder(token)
            .appendQueryParameter(QUERY_ENCRYPTION, ENCRYPTION_AES_GCM)
            .appendQueryParameter(QUERY_KDF_SALT, metadata.kdfSalt)
            .appendQueryParameter(QUERY_KDF_ITERATIONS, metadata.kdfIterations.toString())
            .appendQueryParameter(QUERY_KEY_VERIFIER, metadata.keyVerifier)

        for (cat in CategoryUtil.normalizeCategories(token.categories)) {
            builder.appendQueryParameter(QUERY_CATEGORY, cat)
        }
        if (!token.imagePath.isNullOrBlank()) {
            builder.appendQueryParameter("image", token.imagePath)
        }

        return builder.build()
    }

    private fun buildBaseUriBuilder(token: OtpToken): Uri.Builder {
        val hasIssuer = !token.issuer.isNullOrBlank()
        val labelAndIssuer = when {
            hasIssuer -> "${token.issuer}:${token.label}"
            token.label.contains(':') -> ":${token.label}"
            else -> token.label
        }

        val builder = Uri.Builder().scheme("otpauth").path(labelAndIssuer)
            .appendQueryParameter("secret", token.secret)
            .appendQueryParameter("algorithm", token.algorithm)
            .appendQueryParameter("digits", token.digits.toString())
            .appendQueryParameter("period", token.period.toString())

        if (hasIssuer) {
            builder.appendQueryParameter("issuer", token.issuer)
        }

        when (token.tokenType) {
            OtpTokenType.HOTP -> {
                builder.authority("hotp")
                builder.appendQueryParameter("counter", (token.counter + 1).toString())
            }
            OtpTokenType.TOTP -> builder.authority("totp")
        }

        return builder
    }
}
