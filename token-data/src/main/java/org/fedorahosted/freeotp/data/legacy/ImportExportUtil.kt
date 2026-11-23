package org.fedorahosted.freeotp.data.legacy

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
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
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportExportUtil @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val migrationUtil: MigrationUtil,
    private val gson: Gson,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val tokenCryptoManager: TokenCryptoManager
) {
    suspend fun isJsonFileEncrypted(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).readText()
        } ?: return@withContext false

        isJsonStringEncrypted(text)
    }

    suspend fun isKeyUriFileEncrypted(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val lines = context.contentResolver.openInputStream(fileUri)
            ?.reader(StandardCharsets.UTF_8)
            ?.use { reader -> reader.readLines() }
            ?: return@withContext false

        isKeyUriLinesEncrypted(lines)
    }

    suspend fun importJsonFile(uri: Uri, backupPassphrase: CharArray? = null) {
        withContext(Dispatchers.IO) {
            val jsonText = openRequiredInputStream(uri).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).readText()
            }
            if (jsonText.isBlank()) {
                throw IllegalArgumentException("Empty JSON backup")
            }

            val plaintextTokens = if (isJsonStringEncrypted(jsonText)) {
                val passphrase = backupPassphrase ?: throw EncryptedBackupKeyRequiredException()
                decryptJsonBackup(gson, jsonText, passphrase)
            } else {
                val savedTokens = gson.fromJson(jsonText, SavedTokens::class.java)
                    ?: throw IllegalArgumentException("Malformed JSON backup")
                migrationUtil.convertLegacySavedTokensToOtpTokens(savedTokens)
            }

            val linkedTokens = runCatching {
                TokenImageBackupUtil.resolveRestoredTokenImageLinks(
                    tokens = plaintextTokens,
                    filesDir = context.filesDir,
                    manifest = TokenImageBackupUtil.loadCachedManifest(context.filesDir, gson)
                )
            }.getOrDefault(plaintextTokens).map { token ->
                token.copy(
                    imagePath = TokenImageBackupUtil.sanitizeImportedImagePath(token.imagePath, context.filesDir)
                )
            }
            val tokensToStore = tokenCryptoManager.encryptTokensForStorage(linkedTokens)
            otpTokenDatabase.otpTokenDao().replaceAll(tokensToStore)
        }
    }

    suspend fun exportJsonFile(uri: Uri, encrypt: Boolean = false) {
        withContext(Dispatchers.IO) {
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

            openTruncatedOutputStream(uri).use { outputStream ->
                BufferedOutputStream(outputStream).use { buffered ->
                    buffered.write(jsonString.toByteArray(StandardCharsets.UTF_8))
                    buffered.flush()
                }
            }
        }
    }

    suspend fun importKeyUriFile(fileUri: Uri, backupPassphrase: CharArray? = null) {
        withContext(Dispatchers.IO) {
            val currentLastOrdinal = otpTokenDatabase.otpTokenDao().getLastOrdinal() ?: 0

            val lines = openRequiredInputStream(fileUri)
                .reader(StandardCharsets.UTF_8)
                .use { reader -> reader.readLines() }

            val nonBlankLines = lines.map { it.trim() }.filter { it.isNotEmpty() }
            val plaintextTokens = if (isKeyUriLinesEncrypted(nonBlankLines)) {
                val passphrase = backupPassphrase ?: throw EncryptedBackupKeyRequiredException()
                decryptKeyUriLines(nonBlankLines, passphrase, currentLastOrdinal)
            } else {
                nonBlankLines.mapIndexed { index, line ->
                    OtpTokenFactory.createFromUri(Uri.parse(line), allowImageParam = true).copy(
                        ordinal = currentLastOrdinal + index + 1
                    )
                }
            }

            val linkedTokens = runCatching {
                TokenImageBackupUtil.resolveRestoredTokenImageLinks(
                    tokens = plaintextTokens,
                    filesDir = context.filesDir,
                    manifest = TokenImageBackupUtil.loadCachedManifest(context.filesDir, gson)
                )
            }.getOrDefault(plaintextTokens).map { token ->
                token.copy(
                    imagePath = TokenImageBackupUtil.sanitizeImportedImagePath(token.imagePath, context.filesDir)
                )
            }
            val tokensToStore = tokenCryptoManager.encryptTokensForStorage(linkedTokens)
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

                    OtpTokenFactory.createFromEncryptedUri(uri, decryptedSecret, allowImageParam = true)
                } else {
                    OtpTokenFactory.createFromUri(uri, allowImageParam = true)
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
            val tokens = otpTokenDatabase.otpTokenDao().getAll().first()
            val lines = if (encrypt) {
                val metadata = tokenCryptoManager.getEncryptionMetadata()
                    ?: throw IllegalStateException("Encryption metadata not available")
                tokens.map { token ->
                    val encryptedToken = tokenCryptoManager.encryptTokenForStorage(token)
                    OtpTokenFactory.toEncryptedUri(encryptedToken, metadata).toString()
                }
            } else {
                tokens.map { token ->
                    val plainToken = tokenCryptoManager.decryptToken(token)
                    OtpTokenFactory.toUri(
                        plainToken,
                        includeImage = true,
                        includeCategories = true
                    ).toString()
                }
            }

            openTruncatedOutputStream(fileUri).use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8)).use { writer ->
                    for (line in lines) {
                        writer.write(line)
                        writer.write("\n")
                    }
                    writer.flush()
                }
            }
        }
    }

    suspend fun exportTokenImagesZip(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            val tokens = otpTokenDatabase.otpTokenDao().getAll().first()
            openTruncatedOutputStream(fileUri).use { out ->
                TokenImageBackupUtil.exportTokenImagesToZip(
                    tokens = tokens,
                    filesDir = context.filesDir,
                    gson = gson,
                    zipOutputStream = out
                )
            }
        }
    }

    suspend fun importTokenImagesZip(fileUri: Uri) {
        withContext(Dispatchers.IO) {
            val currentTokens = otpTokenDatabase.otpTokenDao().getAll().first()
            val updatedTokens = openRequiredInputStream(fileUri).use { input ->
                TokenImageBackupUtil.importTokenImagesFromZip(
                    currentTokens = currentTokens,
                    filesDir = context.filesDir,
                    gson = gson,
                    zipInputStream = input
                )
            }
            if (updatedTokens.isNotEmpty()) {
                otpTokenDatabase.otpTokenDao().updateAll(updatedTokens)
            }
        }
    }

    @Throws(IOException::class)
    private fun openTruncatedOutputStream(uri: Uri): OutputStream {
        val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "wt") }.getOrNull()
            ?: runCatching { context.contentResolver.openFileDescriptor(uri, "w") }.getOrNull()
        if (pfd != null) {
            return ParcelFileDescriptor.AutoCloseOutputStream(pfd).also { stream ->
                runCatching { stream.channel.truncate(0L) }
            }
        }
        return runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException("Unable to open output stream for $uri")
    }

    @Throws(IOException::class)
    private fun openRequiredInputStream(uri: Uri): InputStream {
        return context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for $uri")
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
                        counter = if (exportedToken.tokenType == OtpTokenType.HOTP) {
                            exportedToken.counter.coerceAtLeast(0L)
                        } else {
                            0L
                        },
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
