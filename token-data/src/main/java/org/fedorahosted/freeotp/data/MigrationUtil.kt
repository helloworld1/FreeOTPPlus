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
        convertSavedTokens(savedTokens)
    }

    suspend fun convertLegacyTokensToOtpTokens(legacyTokens: List<Token>): List<OtpToken> = withContext(Dispatchers.IO) {
        mapLegacyTokensToOtpTokens(legacyTokens)
    }

    suspend fun convertOtpTokensToLegacyTokens(tokens: List<OtpToken>) = withContext(Dispatchers.IO) {
        tokens.map { otpToken ->
            val uri = OtpTokenFactory.toUri(otpToken)
            Token(uri).apply {
                category = otpToken.category
            }
        }
    }

    companion object {
        internal fun convertSavedTokens(savedTokens: SavedTokens): List<OtpToken> {
            val remainingTokens = savedTokens.tokens.toMutableList()

            val legacyTokens = savedTokens.tokenOrder.mapNotNull { tokenKey ->
                var matchIndex = remainingTokens.indexOfFirst { it.id == tokenKey }
                if (matchIndex < 0 && tokenKey.startsWith(":")) {
                    val strippedKey = tokenKey.substring(1)
                    matchIndex = remainingTokens.indexOfFirst {
                        it.issuer.isNullOrEmpty() && it.label == strippedKey
                    }
                }
                if (matchIndex < 0) {
                    matchIndex = remainingTokens.indexOfFirst {
                        it.issuer.isNotEmpty() && "${it.issuer}:${it.label}" == tokenKey
                    }
                }
                if (matchIndex < 0) {
                    val colonIndex = tokenKey.indexOf(':')
                    if (colonIndex > 0) {
                        val keyIssuer = tokenKey.substring(0, colonIndex)
                        val keyLabel = tokenKey.substring(colonIndex + 1)
                        matchIndex = remainingTokens.indexOfFirst {
                            it.issuer.isNullOrEmpty() && it.label == keyLabel
                        }
                        if (matchIndex >= 0) {
                            remainingTokens[matchIndex].issuer = keyIssuer
                        }
                    }
                }

                if (matchIndex >= 0) {
                    remainingTokens.removeAt(matchIndex).takeIf { it.label.isNotEmpty() }
                } else {
                    null
                }
            }

            return mapLegacyTokensToOtpTokens(legacyTokens)
        }

        internal fun mapLegacyTokensToOtpTokens(legacyTokens: List<Token>): List<OtpToken> {
            return legacyTokens.mapIndexed { index, legacyToken ->
                OtpToken(
                    id = index.toLong() + 1,
                    ordinal = index.toLong() + 1,
                    issuer = legacyToken.issuer.takeIf { !it.isNullOrBlank() },
                    label = legacyToken.label,
                    imagePath = legacyToken.image?.toString(),
                    tokenType = if (legacyToken.type == Token.TokenType.HOTP) OtpTokenType.HOTP else OtpTokenType.TOTP,
                    algorithm = legacyToken.algorithm ?: "SHA1",
                    counter = legacyToken.counter,
                    secret = legacyToken.secret,
                    digits = if (legacyToken.digits > 0) legacyToken.digits else 6,
                    period = if (legacyToken.period > 0) legacyToken.period else 30,
                    encryptionType = EncryptionType.PLAIN_TEXT,
                    category = legacyToken.category
                )
            }
        }
    }
}
