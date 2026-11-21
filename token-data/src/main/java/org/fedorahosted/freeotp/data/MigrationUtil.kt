package org.fedorahosted.freeotp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.legacy.SavedTokens
import org.fedorahosted.freeotp.data.legacy.Token
import org.fedorahosted.freeotp.data.legacy.TokenPersistence
import org.fedorahosted.freeotp.data.util.CategoryUtil
import javax.inject.Inject

class MigrationUtil private constructor(
    private val optTokenDatabase: OtpTokenDatabase,
    private val tokenPersistence: TokenPersistence,
    private val tokenCryptoManager: TokenCryptoManager?,
    @Suppress("UNUSED_PARAMETER") unused: Unit
) {
    @Inject
    constructor(
        optTokenDatabase: OtpTokenDatabase,
        tokenPersistence: TokenPersistence,
        tokenCryptoManager: TokenCryptoManager
    ) : this(optTokenDatabase, tokenPersistence, tokenCryptoManager, Unit)

    constructor(
        optTokenDatabase: OtpTokenDatabase,
        tokenPersistence: TokenPersistence
    ) : this(optTokenDatabase, tokenPersistence, null, Unit)

    fun isMigrated(): Boolean = tokenPersistence.isLegacyTokenMigrated()

    suspend fun migrate() {
        withContext(Dispatchers.IO) {
            if (tokenCryptoManager?.getAuthMode() == AuthMode.TOKEN_ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                return@withContext
            }
            val legacyTokens = tokenPersistence.getTokens()
            if (legacyTokens.isNotEmpty()) {
                val tokenList = convertLegacyTokensToOtpTokens(legacyTokens)
                val tokensToInsert = tokenCryptoManager?.encryptTokensForStorage(tokenList) ?: tokenList
                optTokenDatabase.otpTokenDao().insertAll(tokensToInsert)
            }
            tokenPersistence.setLegacyTokenMigrated()
            tokenPersistence.clearLegacyTokens()
        }
    }

    suspend fun purgeLegacyTokensIfMigrated() {
        withContext(Dispatchers.IO) {
            if (tokenPersistence.isLegacyTokenMigrated()) {
                tokenPersistence.clearLegacyTokens()
            }
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
            try {
                val uri = OtpTokenFactory.toUri(otpToken, includeImage = true)
                val normalizedCategories = CategoryUtil.normalizeCategories(otpToken.categories)
                Token(uri).apply {
                    category = normalizedCategories.firstOrNull()
                    categories = normalizedCategories
                }
            } catch (e: Token.TokenUriInvalidException) {
                throw IllegalArgumentException("Invalid token cannot be exported: ${otpToken.issuer ?: otpToken.label}", e)
            }
        }
    }

    companion object {
        @Suppress("USELESS_CAST", "UNCHECKED_CAST")
        internal fun convertSavedTokens(savedTokens: SavedTokens): List<OtpToken> {
            val rawTokens = (savedTokens.tokens as List<Token?>?)
                ?: throw IllegalArgumentException("Malformed JSON backup: missing tokens")
            val rawOrder = (savedTokens.tokenOrder as List<String?>?)
                ?: throw IllegalArgumentException("Malformed JSON backup: missing tokenOrder")

            val remainingTokens = rawTokens.filterNotNull().toMutableList()

            val legacyTokens = rawOrder.filterNotNull().mapNotNull { tokenKey ->
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
                    remainingTokens.removeAt(matchIndex).takeIf { token ->
                        (token.label.isNotEmpty() || !token.issuer.isNullOrBlank()) &&
                            runCatching { token.secret.isNotEmpty() }.getOrDefault(false)
                    }
                } else {
                    null
                }
            }

            return mapLegacyTokensToOtpTokens(legacyTokens)
        }

        internal fun mapLegacyTokensToOtpTokens(legacyTokens: List<Token>): List<OtpToken> {
            return legacyTokens.mapIndexed { index, legacyToken ->
                val resolvedCategories = if (legacyToken.categories != null) {
                    CategoryUtil.normalizeCategories(legacyToken.categories)
                } else {
                    CategoryUtil.normalizeCategories(listOf(legacyToken.category))
                }
                val resolvedTokenType = if (legacyToken.type == Token.TokenType.HOTP) {
                    OtpTokenType.HOTP
                } else {
                    OtpTokenType.TOTP
                }
                OtpToken(
                    id = index.toLong() + 1,
                    ordinal = index.toLong() + 1,
                    issuer = legacyToken.issuer.takeIf { !it.isNullOrBlank() },
                    label = legacyToken.label,
                    imagePath = legacyToken.image?.toString(),
                    tokenType = resolvedTokenType,
                    algorithm = legacyToken.algorithm ?: "SHA1",
                    counter = if (resolvedTokenType == OtpTokenType.HOTP) {
                        legacyToken.counter.coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    secret = legacyToken.secret,
                    digits = if (legacyToken.digits > 0) legacyToken.digits else 6,
                    period = if (legacyToken.period > 0) legacyToken.period else 30,
                    encryptionType = EncryptionType.PLAIN_TEXT,
                    categories = resolvedCategories
                )
            }
        }
    }
}
