package org.fedorahosted.freeotp.data.legacy

import android.content.Context
import android.net.Uri
import com.google.android.apps.authenticator.Base32String
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.EncryptedBackupKeyRequiredException
import org.fedorahosted.freeotp.data.EncryptedExportBundle
import org.fedorahosted.freeotp.data.EncryptedExportToken
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportExportUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    private val migrationUtil: MigrationUtil,
    private val gson: Gson,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val tokenCryptoManager: TokenCryptoManager
) {
    suspend fun isJsonFileEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).readText()
        } ?: return@withContext false

        isJsonStringEncrypted(text)
    }

    suspend fun isKeyUriFileEncrypted(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val lines = context.contentResolver.openInputStream(fileUri)?.reader()?.use { reader ->
            reader.readLines()
        } ?: return@withContext false

        isKeyUriLinesEncrypted(lines)
    }

    suspend fun importJsonFile(uri: Uri, backupPassphrase: CharArray? = null) {
        withContext(Dispatchers.IO) {
            val jsonText = context.contentResolver.openInputStream(uri).use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                reader.readText()
            }

            val plaintextTokens = if (isJsonStringEncrypted(jsonText)) {
                val passphrase = backupPassphrase ?: throw EncryptedBackupKeyRequiredException()
                decryptJsonBackup(gson, jsonText, passphrase)
            } else {
                val savedTokens = gson.fromJson(jsonText, SavedTokens::class.java)
                migrationUtil.convertLegacySavedTokensToOtpTokens(savedTokens)
            }

            val tokensToStore = tokenCryptoManager.encryptTokensForStorage(plaintextTokens)
            otpTokenDatabase.otpTokenDao().replaceAll(tokensToStore)
        }
    }

    suspend fun exportJsonFile(uri: Uri, encrypt: Boolean = false) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "w").use { outputStream ->
                val otpTokens = otpTokenDatabase.otpTokenDao().getAll().first()

                val jsonString = if (encrypt) {
                    val metadata = tokenCryptoManager.getEncryptionMetadata()
                        ?: throw IllegalStateException("Encryption metadata not available")
                    val encryptedTokens = otpTokens.map { token ->
                        EncryptedExportToken.fromOtpToken(tokenCryptoManager.encryptTokenForStorage(token))
                    }
                    gson.toJson(
                        EncryptedExportBundle(
                            version = 1,
                            encrypted = true,
                            kdfSalt = metadata.kdfSalt,
                            kdfIterations = metadata.kdfIterations,
                            keyVerifier = metadata.keyVerifier,
                            tokens = encryptedTokens
                        )
                    )
                } else {
                    val plainTokens = otpTokens.map { tokenCryptoManager.decryptToken(it) }
                    val legacyTokens = migrationUtil.convertOtpTokensToLegacyTokens(plainTokens)
                    val tokenOrder = legacyTokens.map { it.id }
                    gson.toJson(SavedTokens(legacyTokens, tokenOrder))
                }

                outputStream?.write(jsonString.toByteArray())
            }
        }
    }

    suspend fun importKeyUriFile(fileUri: Uri, backupPassphrase: CharArray? = null) {
        withContext(Dispatchers.IO) {
            val currentLastOrdinal = otpTokenDatabase.otpTokenDao().getLastOrdinal() ?: 0

            val lines = context.contentResolver.openInputStream(fileUri)?.reader()?.use { reader ->
                reader.readLines()
            } ?: return@withContext

            val nonBlankLines = lines.map { it.trim() }.filter { it.isNotEmpty() }
            val plaintextTokens = if (isKeyUriLinesEncrypted(nonBlankLines)) {
                val passphrase = backupPassphrase ?: throw EncryptedBackupKeyRequiredException()
                decryptKeyUriLines(nonBlankLines, passphrase, currentLastOrdinal)
            } else {
                nonBlankLines.mapIndexed { index, line ->
                    OtpTokenFactory.createFromUri(Uri.parse(line)).copy(
                        ordinal = currentLastOrdinal + index + 1
                    )
                }
            }

            val tokensToStore = tokenCryptoManager.encryptTokensForStorage(plaintextTokens)
            otpTokenDatabase.otpTokenDao().insertAll(tokensToStore)
        }
    }

    internal fun decryptKeyUriLines(
        nonBlankLines: List<String>,
        passphrase: CharArray,
        currentLastOrdinal: Long
    ): List<OtpToken> {
        val derivedKeyCache = mutableMapOf<Pair<String, Int>, ByteArray>()
        try {
            return nonBlankLines.mapIndexed { index, line ->
                val uri = Uri.parse(line)
                val token = if (OtpTokenFactory.isEncryptedUri(uri)) {
                    val saltStr = uri.getQueryParameter(OtpTokenFactory.QUERY_KDF_SALT)
                        ?: throw IllegalArgumentException("Missing kdfSalt in encrypted URI")
                    val iterations = uri.getQueryParameter(OtpTokenFactory.QUERY_KDF_ITERATIONS)?.toIntOrNull()
                        ?: throw IllegalArgumentException("Missing kdfIterations in encrypted URI")
                    val verifier = uri.getQueryParameter(OtpTokenFactory.QUERY_KEY_VERIFIER)
                    val encryptedSecret = uri.getQueryParameter("secret")
                        ?: throw IllegalArgumentException("Missing secret in encrypted URI")

                    val cacheKey = saltStr to iterations
                    val backupKey = derivedKeyCache.getOrPut(cacheKey) {
                        val salt = CryptoEngine.decodeBase64(saltStr)
                        val derived = CryptoEngine.deriveKey(passphrase, salt, iterations)
                        if (!verifier.isNullOrBlank() && !CryptoEngine.verifyKey(derived, verifier)) {
                            derived.fill(0)
                            throw InvalidEncryptionKeyException()
                        }
                        derived
                    }

                    if (!verifier.isNullOrBlank() && !CryptoEngine.verifyKey(backupKey, verifier)) {
                        throw InvalidEncryptionKeyException()
                    }

                    val decryptedSecret = try {
                        CryptoEngine.decryptSecret(encryptedSecret, backupKey)
                    } catch (_: GeneralSecurityException) {
                        throw InvalidEncryptionKeyException()
                    }

                    OtpTokenFactory.createFromEncryptedUri(uri, decryptedSecret)
                } else {
                    OtpTokenFactory.createFromUri(uri)
                }

                token.copy(ordinal = currentLastOrdinal + index + 1)
            }
        } finally {
            derivedKeyCache.values.forEach { it.fill(0) }
            derivedKeyCache.clear()
        }
    }

    suspend fun exportKeyUriFile(fileUri: Uri, encrypt: Boolean = false) {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(fileUri, "w")?.use { outputStream ->
                PrintWriter(outputStream).use { printWriter ->
                    val tokens = otpTokenDatabase.otpTokenDao().getAll().first()
                    if (encrypt) {
                        val metadata = tokenCryptoManager.getEncryptionMetadata()
                            ?: throw IllegalStateException("Encryption metadata not available")
                        for (token in tokens) {
                            val encryptedToken = tokenCryptoManager.encryptTokenForStorage(token)
                            printWriter.println(OtpTokenFactory.toEncryptedUri(encryptedToken, metadata).toString())
                        }
                    } else {
                        for (token in tokens) {
                            val plainToken = tokenCryptoManager.decryptToken(token)
                            printWriter.println(OtpTokenFactory.toUri(plainToken).toString())
                        }
                    }
                }
            }
        }
    }

    companion object {
        internal fun isJsonStringEncrypted(jsonString: String): Boolean {
            return try {
                val element = JsonParser.parseString(jsonString)
                element.isJsonObject && element.asJsonObject.get("encrypted")?.asBoolean == true
            } catch (_: Exception) {
                false
            }
        }

        internal fun isKeyUriLinesEncrypted(lines: List<String>): Boolean {
            return lines.any { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && runCatching {
                    OtpTokenFactory.isEncryptedUri(Uri.parse(trimmed))
                }.getOrDefault(false)
            }
        }

        internal fun decryptJsonBackup(gson: Gson, jsonText: String, passphrase: CharArray): List<OtpToken> {
            val bundle = gson.fromJson(jsonText, EncryptedExportBundle::class.java)
                ?: throw IllegalArgumentException("Malformed encrypted JSON backup")
            if (bundle.kdfSalt.isBlank() || bundle.kdfIterations <= 0 || bundle.keyVerifier.isBlank()) {
                throw IllegalArgumentException("Missing encryption KDF metadata in JSON backup")
            }

            val salt = CryptoEngine.decodeBase64(bundle.kdfSalt)
            val backupKey = CryptoEngine.deriveKey(passphrase, salt, bundle.kdfIterations)
            try {
                if (!CryptoEngine.verifyKey(backupKey, bundle.keyVerifier)) {
                    throw InvalidEncryptionKeyException()
                }

                return bundle.tokens.mapIndexed { index, exportedToken ->
                    if (exportedToken.label.isEmpty() && exportedToken.issuer.isNullOrBlank()) {
                        throw IllegalArgumentException("Token missing label and issuer")
                    }
                    val plainSecret = if (
                        exportedToken.encryptionType == EncryptionType.ENCRYPTED ||
                        CryptoEngine.isEncryptedPayload(exportedToken.secret)
                    ) {
                        try {
                            CryptoEngine.decryptSecret(exportedToken.secret, backupKey)
                        } catch (_: GeneralSecurityException) {
                            throw InvalidEncryptionKeyException()
                        }
                    } else {
                        exportedToken.secret
                    }

                    // Validate Base32 secret format
                    Base32String.decode(plainSecret)

                    val resolvedCategories = if (exportedToken.categories != null) {
                        CategoryUtil.normalizeCategories(exportedToken.categories)
                    } else {
                        CategoryUtil.normalizeCategories(listOf(exportedToken.category))
                    }

                    OtpToken(
                        id = index.toLong() + 1,
                        ordinal = index.toLong() + 1,
                        issuer = exportedToken.issuer?.takeIf { it.isNotBlank() },
                        label = exportedToken.label,
                        imagePath = exportedToken.imagePath,
                        tokenType = exportedToken.tokenType,
                        algorithm = exportedToken.algorithm,
                        secret = plainSecret,
                        digits = if (exportedToken.digits > 0) exportedToken.digits else 6,
                        counter = exportedToken.counter,
                        period = if (exportedToken.period > 0) exportedToken.period else 30,
                        encryptionType = EncryptionType.PLAIN_TEXT,
                        categories = resolvedCategories
                    )
                }
            } finally {
                backupKey.fill(0)
            }
        }
    }
}
