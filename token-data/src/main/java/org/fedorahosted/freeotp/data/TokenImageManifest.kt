package org.fedorahosted.freeotp.data

import com.google.gson.annotations.SerializedName

data class TokenImageManifest(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("entries") val entries: List<TokenImageManifestEntry> = emptyList()
)

data class TokenImageManifestEntry(
    @SerializedName("fileName") val fileName: String = "",
    @SerializedName("originalImagePath") val originalImagePath: String? = null,
    @SerializedName("issuer") val issuer: String? = null,
    @SerializedName("label") val label: String = "",
    @SerializedName("tokenType") val tokenType: OtpTokenType = OtpTokenType.TOTP,
    @SerializedName("algorithm") val algorithm: String = "SHA1",
    @SerializedName("digits") val digits: Int = 6,
    @SerializedName("period") val period: Int = 30
)
