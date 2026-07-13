package org.fedorahosted.freeotp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.legacy.SavedTokens
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
            val tokenList = convertLegacyTokensToOtpTokens(tokenPersistence.getTokens())
            optTokenDatabase.otpTokenDao().insertAll(tokenList)
            tokenPersistence.setLegacyTokenMigrated()
        }
    }

    suspend fun convertLegacySavedTokensToOtpTokens(savedTokens: SavedTokens): List<OtpToken> = withContext(Dispatchers.IO) {
        val tokenMap = savedTokens.tokens.associateBy { it.id }

        val legacyTokens = savedTokens.tokenOrder.mapNotNull { tokenKey ->
            tokenMap[tokenKey]
        }

        convertLegacyTokensToOtpTokens(legacyTokens)
    }

    suspend fun convertLegacyTokensToOtpTokens(legacyTokens: List<Token>): List<OtpToken> = withContext(Dispatchers.IO) {
        legacyTokens.mapIndexed{ index, legacyToken ->
            OtpToken(
                id = index.toLong() + 1,
                ordinal = index.toLong() + 1,
                issuer = legacyToken.issuer,
                label = legacyToken.label,
                alias = legacyToken.alias,
                iconKey = legacyToken.iconKey,
                imagePath = legacyToken.image?.toString(),
                tokenType = if (legacyToken.type == Token.TokenType.HOTP) OtpTokenType.HOTP else OtpTokenType.TOTP,
                algorithm = legacyToken.algorithm ?: "sha1",
                counter = legacyToken.counter,
                secret = legacyToken.secret,
                digits = legacyToken.digits,
                period = legacyToken.period,
                encryptionType = EncryptionType.PLAIN_TEXT,
                category = legacyToken.category
            )
        }
    };

    suspend fun convertOtpTokensToLegacyTokens(tokens: List<OtpToken>) = withContext(Dispatchers.IO) {
        tokens.map { otpToken ->
            val uri = OtpTokenFactory.toUri(otpToken)
            Token(uri).apply {
                category = otpToken.category
                alias = otpToken.alias
                iconKey = otpToken.iconKey
            }
        }
    }
}
