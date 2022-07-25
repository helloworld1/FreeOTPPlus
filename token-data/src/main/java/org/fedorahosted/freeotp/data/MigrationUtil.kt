package org.fedorahosted.freeotp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.common.encryption.EncryptionType
import org.fedorahosted.freeotp.common.util.Settings
import org.fedorahosted.freeotp.data.legacy.SavedTokens
import org.fedorahosted.freeotp.data.legacy.Token
import org.fedorahosted.freeotp.data.legacy.TokenPersistence
import javax.inject.Inject

class MigrationUtil @Inject constructor(
    private val otpTokenService: OtpTokenService,
    private val tokenPersistence: TokenPersistence,
    private val settings: Settings,
) {
    fun isMigrated(): Boolean = tokenPersistence.isLegacyTokenMigrated()

    suspend fun migrate() {
        withContext(Dispatchers.IO) {
            val tokenList = convertLegacyTokensToOtpTokens(tokenPersistence.getTokens())
            otpTokenService.insertAllEncrypted(tokenList, settings.password)
            tokenPersistence.setLegacyTokenMigrated()
        }
    }

    suspend fun convertLegacySavedTokensToOtpTokens(savedTokens: SavedTokens): List<OtpToken> = withContext(Dispatchers.IO) {
        val tokenMap = savedTokens.tokens.map { token ->
            token.id to token
        }.toMap()

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

    suspend fun convertOtpTokensToLegacyTokens(tokens: List<OtpToken>) = withContext(Dispatchers.IO) {
        tokens.map {
            OtpTokenFactory.toUri(it)
        }.map { uri ->
            Token(uri)
        }
    }
}