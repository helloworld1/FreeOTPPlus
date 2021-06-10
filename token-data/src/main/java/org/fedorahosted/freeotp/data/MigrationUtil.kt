package org.fedorahosted.freeotp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.legacy.Token
import org.fedorahosted.freeotp.data.legacy.TokenPersistence
import javax.inject.Inject

class MigrationUtil @Inject constructor(
    private val optTokenDatabase: OtpTokenDatabase,
    private val tokenPersistence: TokenPersistence,
) {
    fun isMigrated(): Boolean = tokenPersistence.isLegacyTokenMigrated()

    suspend fun migrate() {
        withContext(Dispatchers.IO) {
            val tokenList = convertLegacyTokens(tokenPersistence.getTokens())
            optTokenDatabase.otpTokenDao().insertAll(tokenList)
            tokenPersistence.setLegacyTokenMigrated()
        }
    }

    private suspend fun convertLegacyTokens(legacyTokens: List<Token>) = withContext(Dispatchers.IO) {
        legacyTokens.mapIndexed{ index, legacyToken ->
            OtpToken(
                id = index.toLong() + 1,
                ordinal = index.toLong() + 1,
                issuer = legacyToken.issuer,
                label = legacyToken.label,
                imagePath = legacyToken.image?.toString(),
                tokenType = if (legacyToken.type == Token.TokenType.HOTP) OtpTokenType.HOTP else OtpTokenType.TOTP,
                algorithm = legacyToken.algorithm ?: "sha1",
                counter = legacyToken.counter,
                secret = legacyToken.secret,
                digits = legacyToken.digits,
                period = legacyToken.period,
                encryptionType = EncryptionType.PLAIN_TEXT
            )
        }
    };

}