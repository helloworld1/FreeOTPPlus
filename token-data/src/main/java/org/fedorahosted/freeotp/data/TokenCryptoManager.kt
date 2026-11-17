package org.fedorahosted.freeotp.data

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.util.CryptoEngine
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenCryptoManager @Inject constructor(
    private val sharedPreferences: SharedPreferences,
    private val otpTokenDatabase: OtpTokenDatabase
) {
    interface KeyStoreBackend {
        @Throws(Exception::class)
        fun createEncryptCipher(): Cipher

        @Throws(Exception::class)
        fun createDecryptCipher(iv: ByteArray): Cipher?

        fun deleteKey()
    }

    class AndroidKeyStoreBackend : KeyStoreBackend {
        override fun createEncryptCipher(): Cipher {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )
            val builder = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(CryptoEngine.KEY_LENGTH_BITS)
                .setUserAuthenticationRequired(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setInvalidatedByBiometricEnrollment(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }

            keyGenerator.init(builder.build())
            val secretKey = keyGenerator.generateKey()

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            return cipher
        }

        override fun createDecryptCipher(iv: ByteArray): Cipher? {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            val secretKey = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(CryptoEngine.GCM_TAG_LENGTH_BITS, iv))
            return cipher
        }

        override fun deleteKey() {
            runCatching {
                val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
                    keyStore.deleteEntry(KEYSTORE_ALIAS)
                }
            }
        }
    }

    enum class BiometricKeyStatus {
        READY,
        NOT_CONFIGURED,
        INVALIDATED
    }

    data class BiometricDecryptPreparation(
        val status: BiometricKeyStatus,
        val cipher: Cipher? = null
    )

    class SetupMaterial(
        val keyBytes: ByteArray,
        val kdfSalt: String,
        val kdfIterations: Int,
        val keyVerifier: String
    ) {
        fun clear() {
            keyBytes.fill(0)
        }
    }

    @VisibleForTesting
    @Volatile
    var keyStoreBackend: KeyStoreBackend = AndroidKeyStoreBackend()

    @VisibleForTesting
    @Volatile
    var kdfIterationsOverride: Int? = null

    @VisibleForTesting
    val activeAsyncOperations = AtomicInteger(0)

    @VisibleForTesting
    @Volatile
    var onAsyncOperationIdle: (() -> Unit)? = null

    private inline fun <T> trackAsync(block: () -> T): T {
        activeAsyncOperations.incrementAndGet()
        try {
            return block()
        } finally {
            if (activeAsyncOperations.decrementAndGet() == 0) {
                onAsyncOperationIdle?.invoke()
            }
        }
    }

    private val lockObject = Any()
    private var unlockedKeyBytes: ByteArray? = null

    fun getAuthMode(): AuthMode {
        val raw = sharedPreferences.getString(AUTH_MODE_KEY, null)
        if (raw != null) {
            return runCatching { AuthMode.valueOf(raw) }.getOrDefault(AuthMode.NONE)
        }
        return if (sharedPreferences.getBoolean(REQUIRE_AUTHENTICATION_KEY, false)) {
            AuthMode.APP_LAUNCH
        } else {
            AuthMode.NONE
        }
    }

    fun setAuthMode(mode: AuthMode) {
        sharedPreferences.edit()
            .putString(AUTH_MODE_KEY, mode.name)
            .putBoolean(REQUIRE_AUTHENTICATION_KEY, mode != AuthMode.NONE)
            .apply()
    }

    fun isUnlocked(): Boolean = synchronized(lockObject) {
        if (getAuthMode() != AuthMode.TOKEN_ENCRYPTED) {
            true
        } else {
            unlockedKeyBytes != null
        }
    }

    fun lock() = synchronized(lockObject) {
        unlockedKeyBytes?.fill(0)
        unlockedKeyBytes = null
    }

    private fun setSessionKey(keyBytes: ByteArray) = synchronized(lockObject) {
        unlockedKeyBytes?.fill(0)
        unlockedKeyBytes = keyBytes.copyOf()
    }

    private fun requireUnlockedKey(): ByteArray = synchronized(lockObject) {
        unlockedKeyBytes?.copyOf()
            ?: throw IllegalStateException("Encryption key is not unlocked")
    }

    fun isBiometricKeyConfigured(): Boolean {
        return !sharedPreferences.getString(BIOMETRIC_ENCRYPTED_KEY, null).isNullOrEmpty()
    }

    fun wasBiometricInvalidated(): Boolean {
        return sharedPreferences.getBoolean(BIOMETRIC_INVALIDATED_KEY, false)
    }

    fun getEncryptionMetadata(): EncryptionMetadata? {
        val salt = sharedPreferences.getString(KDF_SALT_KEY, null) ?: return null
        val verifier = sharedPreferences.getString(KEY_VERIFIER_KEY, null) ?: return null
        val iterations = sharedPreferences.getInt(KDF_ITERATIONS_KEY, CryptoEngine.DEFAULT_KDF_ITERATIONS)
        return EncryptionMetadata(
            kdfSalt = salt,
            kdfIterations = iterations,
            keyVerifier = verifier
        )
    }

    @Throws(Exception::class)
    fun createBiometricEncryptCipher(): Cipher {
        return keyStoreBackend.createEncryptCipher()
    }

    fun cancelPendingBiometricKeySetup() {
        if (!isBiometricKeyConfigured()) {
            keyStoreBackend.deleteKey()
        }
    }

    @Throws(GeneralSecurityException::class)
    fun wrapKeyWithBiometricCipher(authenticatedEncryptCipher: Cipher, keyBytes: ByteArray): String {
        val iv = authenticatedEncryptCipher.iv
            ?: throw GeneralSecurityException("Biometric cipher missing IV")
        val ciphertext = authenticatedEncryptCipher.doFinal(keyBytes)
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)
        return CryptoEngine.encodeBase64(packed)
    }

    @Throws(GeneralSecurityException::class)
    fun saveBiometricEncryptedCurrentKey(authenticatedEncryptCipher: Cipher) {
        val keyBytes = requireUnlockedKey()
        try {
            val blob = wrapKeyWithBiometricCipher(authenticatedEncryptCipher, keyBytes)
            sharedPreferences.edit()
                .putString(BIOMETRIC_ENCRYPTED_KEY, blob)
                .putBoolean(BIOMETRIC_INVALIDATED_KEY, false)
                .apply()
        } finally {
            keyBytes.fill(0)
        }
    }

    fun prepareBiometricDecryptCipher(): BiometricDecryptPreparation {
        val blob = sharedPreferences.getString(BIOMETRIC_ENCRYPTED_KEY, null)
        if (blob.isNullOrEmpty()) {
            val status = if (wasBiometricInvalidated()) {
                BiometricKeyStatus.INVALIDATED
            } else {
                BiometricKeyStatus.NOT_CONFIGURED
            }
            return BiometricDecryptPreparation(status, null)
        }

        val iv = try {
            unpackBiometricBlob(blob).first
        } catch (_: Exception) {
            markBiometricInvalidated()
            return BiometricDecryptPreparation(BiometricKeyStatus.INVALIDATED, null)
        }

        return try {
            val cipher = keyStoreBackend.createDecryptCipher(iv)
            if (cipher == null) {
                markBiometricInvalidated()
                BiometricDecryptPreparation(BiometricKeyStatus.INVALIDATED, null)
            } else {
                BiometricDecryptPreparation(BiometricKeyStatus.READY, cipher)
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            markBiometricInvalidated()
            BiometricDecryptPreparation(BiometricKeyStatus.INVALIDATED, null)
        } catch (_: Exception) {
            markBiometricInvalidated()
            BiometricDecryptPreparation(BiometricKeyStatus.INVALIDATED, null)
        }
    }

    fun unlockWithBiometricCipher(authenticatedDecryptCipher: Cipher?): Boolean {
        if (authenticatedDecryptCipher == null) return false
        val blob = sharedPreferences.getString(BIOMETRIC_ENCRYPTED_KEY, null) ?: return false
        val verifier = sharedPreferences.getString(KEY_VERIFIER_KEY, null) ?: return false

        return try {
            val (_, ciphertext) = unpackBiometricBlob(blob)
            val keyBytes = authenticatedDecryptCipher.doFinal(ciphertext)
            try {
                if (!CryptoEngine.verifyKey(keyBytes, verifier)) {
                    markBiometricInvalidated()
                    false
                } else {
                    setSessionKey(keyBytes)
                    true
                }
            } finally {
                keyBytes.fill(0)
            }
        } catch (_: Exception) {
            markBiometricInvalidated()
            false
        }
    }

    private fun markBiometricInvalidated() {
        keyStoreBackend.deleteKey()
        sharedPreferences.edit()
            .remove(BIOMETRIC_ENCRYPTED_KEY)
            .putBoolean(BIOMETRIC_INVALIDATED_KEY, true)
            .apply()
    }

    private fun unpackBiometricBlob(blob: String): Pair<ByteArray, ByteArray> {
        val packed = CryptoEngine.decodeBase64(blob)
        require(packed.size > 2) { "Biometric blob too short" }
        val ivLen = packed[0].toInt() and 0xFF
        require(ivLen in 1 until packed.size) { "Invalid IV length in biometric blob" }
        val iv = packed.copyOfRange(1, 1 + ivLen)
        val ciphertext = packed.copyOfRange(1 + ivLen, packed.size)
        return iv to ciphertext
    }

    suspend fun deriveSetupMaterial(passphrase: CharArray): SetupMaterial = trackAsync {
        withContext(Dispatchers.Default) {
            val salt = CryptoEngine.generateSalt()
            val iterations = kdfIterationsOverride ?: CryptoEngine.DEFAULT_KDF_ITERATIONS
            val keyBytes = CryptoEngine.deriveKey(passphrase, salt, iterations)
            val verifier = CryptoEngine.createKeyVerifier(keyBytes)
            SetupMaterial(
                keyBytes = keyBytes,
                kdfSalt = CryptoEngine.encodeBase64(salt),
                kdfIterations = iterations,
                keyVerifier = verifier
            )
        }
    }

    suspend fun verifyPassphrase(passphrase: CharArray): ByteArray? = trackAsync {
        withContext(Dispatchers.Default) {
            val saltStr = sharedPreferences.getString(KDF_SALT_KEY, null) ?: return@withContext null
            val verifier = sharedPreferences.getString(KEY_VERIFIER_KEY, null) ?: return@withContext null
            val iterations = sharedPreferences.getInt(KDF_ITERATIONS_KEY, CryptoEngine.DEFAULT_KDF_ITERATIONS)

            val salt = try {
                CryptoEngine.decodeBase64(saltStr)
            } catch (_: Exception) {
                return@withContext null
            }

            val keyBytes = CryptoEngine.deriveKey(passphrase, salt, iterations)
            if (CryptoEngine.verifyKey(keyBytes, verifier)) {
                keyBytes
            } else {
                keyBytes.fill(0)
                null
            }
        }
    }

    suspend fun unlockWithPassphrase(passphrase: CharArray): Boolean {
        val keyBytes = verifyPassphrase(passphrase) ?: return false
        try {
            setSessionKey(keyBytes)
            return true
        } finally {
            keyBytes.fill(0)
        }
    }

    suspend fun enableTokenEncryption(
        setupMaterial: SetupMaterial,
        biometricEncryptedKeyBlob: String?
    ) = trackAsync {
        withContext(Dispatchers.IO) {
            val dao = otpTokenDatabase.otpTokenDao()
            otpTokenDatabase.withTransaction {
                val allTokens = dao.getAll().first()
                if (allTokens.isNotEmpty()) {
                    val encryptedTokens = allTokens.map { token ->
                        val plainSecret = if (token.encryptionType == EncryptionType.ENCRYPTED) {
                            decryptSecret(token)
                        } else {
                            token.secret
                        }
                        token.copy(
                            secret = CryptoEngine.encryptSecret(plainSecret, setupMaterial.keyBytes),
                            encryptionType = EncryptionType.ENCRYPTED
                        )
                    }
                    dao.updateAll(encryptedTokens)
                }
            }

            if (biometricEncryptedKeyBlob == null) {
                keyStoreBackend.deleteKey()
            }

            sharedPreferences.edit()
                .putString(KDF_SALT_KEY, setupMaterial.kdfSalt)
                .putInt(KDF_ITERATIONS_KEY, setupMaterial.kdfIterations)
                .putString(KEY_VERIFIER_KEY, setupMaterial.keyVerifier)
                .apply {
                    if (biometricEncryptedKeyBlob != null) {
                        putString(BIOMETRIC_ENCRYPTED_KEY, biometricEncryptedKeyBlob)
                    } else {
                        remove(BIOMETRIC_ENCRYPTED_KEY)
                    }
                }
                .putBoolean(BIOMETRIC_INVALIDATED_KEY, false)
                .putString(AUTH_MODE_KEY, AuthMode.TOKEN_ENCRYPTED.name)
                .putBoolean(REQUIRE_AUTHENTICATION_KEY, true)
                .commit()

            setSessionKey(setupMaterial.keyBytes)
        }
    }

    suspend fun disableTokenEncryption(targetMode: AuthMode) = trackAsync {
        withContext(Dispatchers.IO) {
            require(targetMode != AuthMode.TOKEN_ENCRYPTED) {
                "Target mode must not be TOKEN_ENCRYPTED"
            }
            val keyBytes = requireUnlockedKey()
            try {
                val dao = otpTokenDatabase.otpTokenDao()
                val allTokens = dao.getAll().first()
                val decryptedTokens = allTokens.map { token ->
                    if (token.encryptionType == EncryptionType.ENCRYPTED) {
                        token.copy(
                            secret = CryptoEngine.decryptSecret(token.secret, keyBytes),
                            encryptionType = EncryptionType.PLAIN_TEXT
                        )
                    } else {
                        token
                    }
                }

                otpTokenDatabase.withTransaction {
                    if (decryptedTokens.isNotEmpty()) {
                        dao.updateAll(decryptedTokens)
                    }
                }

                keyStoreBackend.deleteKey()
                sharedPreferences.edit()
                    .remove(KDF_SALT_KEY)
                    .remove(KDF_ITERATIONS_KEY)
                    .remove(KEY_VERIFIER_KEY)
                    .remove(BIOMETRIC_ENCRYPTED_KEY)
                    .remove(BIOMETRIC_INVALIDATED_KEY)
                    .putString(AUTH_MODE_KEY, targetMode.name)
                    .putBoolean(REQUIRE_AUTHENTICATION_KEY, targetMode != AuthMode.NONE)
                    .commit()

                lock()
            } finally {
                keyBytes.fill(0)
            }
        }
    }

    fun encryptTokenForStorage(token: OtpToken): OtpToken {
        if (getAuthMode() != AuthMode.TOKEN_ENCRYPTED) {
            return token
        }
        if (token.encryptionType == EncryptionType.ENCRYPTED) {
            return token
        }
        val keyBytes = requireUnlockedKey()
        return try {
            token.copy(
                secret = CryptoEngine.encryptSecret(token.secret, keyBytes),
                encryptionType = EncryptionType.ENCRYPTED
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    fun encryptTokensForStorage(tokens: List<OtpToken>): List<OtpToken> {
        if (getAuthMode() != AuthMode.TOKEN_ENCRYPTED) {
            return tokens
        }
        val keyBytes = requireUnlockedKey()
        return try {
            tokens.map { token ->
                if (token.encryptionType == EncryptionType.ENCRYPTED) {
                    token
                } else {
                    token.copy(
                        secret = CryptoEngine.encryptSecret(token.secret, keyBytes),
                        encryptionType = EncryptionType.ENCRYPTED
                    )
                }
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    fun decryptToken(token: OtpToken): OtpToken {
        if (token.encryptionType == EncryptionType.PLAIN_TEXT) {
            return token
        }
        val keyBytes = requireUnlockedKey()
        return try {
            token.copy(
                secret = CryptoEngine.decryptSecret(token.secret, keyBytes),
                encryptionType = EncryptionType.PLAIN_TEXT
            )
        } finally {
            keyBytes.fill(0)
        }
    }

    fun decryptSecret(token: OtpToken): String {
        if (token.encryptionType == EncryptionType.PLAIN_TEXT) {
            return token.secret
        }
        val keyBytes = requireUnlockedKey()
        return try {
            CryptoEngine.decryptSecret(token.secret, keyBytes)
        } finally {
            keyBytes.fill(0)
        }
    }

    companion object {
        const val AUTH_MODE_KEY = "authMode"
        const val REQUIRE_AUTHENTICATION_KEY = "requireAuthentication"
        const val KDF_SALT_KEY = "encryptionKdfSalt"
        const val KDF_ITERATIONS_KEY = "encryptionKdfIterations"
        const val KEY_VERIFIER_KEY = "encryptionKeyVerifier"
        const val BIOMETRIC_ENCRYPTED_KEY = "biometricEncryptedKey"
        const val BIOMETRIC_INVALIDATED_KEY = "biometricInvalidated"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "freeotp_biometric_master_key"
    }
}
