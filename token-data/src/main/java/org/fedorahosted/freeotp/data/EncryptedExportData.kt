package org.fedorahosted.freeotp.data

import com.google.gson.annotations.SerializedName
import org.fedorahosted.freeotp.data.util.CategoryUtil

class InvalidEncryptionKeyException(message: String = "Incorrect encryption key") : Exception(message)

class EncryptedBackupKeyRequiredException(message: String = "Encryption key required to import encrypted backup") : Exception(message)

data class EncryptionMetadata(
    val kdfSalt: String,
    val kdfIterations: Int,
    val keyVerifier: String
)

data class EncryptedExportBundle(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("encrypted") val encrypted: Boolean = true,
    @SerializedName("kdfSalt") val kdfSalt: String,
    @SerializedName("kdfIterations") val kdfIterations: Int,
    @SerializedName("keyVerifier") val keyVerifier: String,
    @SerializedName("tokens") val tokens: List<EncryptedExportToken>
)

data class EncryptedExportToken(
    @SerializedName("issuer") val issuer: String?,
    @SerializedName("label") val label: String,
    @SerializedName("imagePath") val imagePath: String?,
    @SerializedName("tokenType") val tokenType: OtpTokenType,
    @SerializedName("algorithm") val algorithm: String,
    @SerializedName("secret") val secret: String,
    @SerializedName("digits") val digits: Int,
    @SerializedName("counter") val counter: Long,
    @SerializedName("period") val period: Int,
    @SerializedName("encryptionType") val encryptionType: EncryptionType,
    @SerializedName("category") val category: String? = null,
    @SerializedName("categories") val categories: List<String>? = null
) {
    companion object {
        fun fromOtpToken(token: OtpToken): EncryptedExportToken {
            val normalizedCategories = CategoryUtil.normalizeCategories(token.categories)
            return EncryptedExportToken(
                issuer = token.issuer,
                label = token.label,
                imagePath = token.imagePath,
                tokenType = token.tokenType,
                algorithm = token.algorithm,
                secret = token.secret,
                digits = token.digits,
                counter = token.counter,
                period = token.period,
                encryptionType = token.encryptionType,
                category = normalizedCategories.firstOrNull(),
                categories = normalizedCategories
            )
        }
    }
}
