package org.fedorahosted.freeotp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "otp_tokens")
data class OtpToken (
    @PrimaryKey(autoGenerate = true) val id: Long,
    val ordinal: Long,
    val issuer: String?,
    val label: String,
    val imagePath: String?,
    val tokenType: OtpTokenType,
    val algorithm: String,
    val secret: String,
    val digits: Int,
    val counter: Long,
    val period: Int,
    val encryptionType: EncryptionType
)