package org.fedorahosted.freeotp.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCryptoManagerTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class EditorImpl : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                if (value == null) removals.add(key) else staged[key] = value
            }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (values == null) removals.add(key) else staged[key] = values
            }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                data.putAll(staged)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private class FakeKeyStoreBackend : TokenCryptoManager.KeyStoreBackend {
        var key: SecretKey? = null
        var throwOnDecrypt: Boolean = false

        override fun createEncryptCipher(): Cipher {
            val gen = KeyGenerator.getInstance("AES").apply { init(256) }
            val secretKey = gen.generateKey()
            key = secretKey
            throwOnDecrypt = false
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, secretKey)
            }
        }

        override fun createDecryptCipher(iv: ByteArray): Cipher? {
            if (throwOnDecrypt) {
                throw GeneralSecurityException("Simulated invalidated biometric key")
            }
            val secretKey = key ?: return null
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            }
        }

        override fun deleteKey() {
            key = null
            throwOnDecrypt = false
        }
    }

    private class NoOpDatabase : OtpTokenDatabase() {
        override fun otpTokenDao(): OtpTokenDao = throw UnsupportedOperationException()
        override fun createInvalidationTracker() = throw UnsupportedOperationException()
        override fun clearAllTables() {}
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration) =
            throw UnsupportedOperationException()
    }

    @Test
    fun authMode_backwardCompatibleWithLegacyRequireAuthentication() {
        val prefs = InMemorySharedPreferences()
        val manager = TokenCryptoManager(prefs, NoOpDatabase())

        // Default unset -> NONE
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(manager.isUnlocked()).isTrue()

        // Legacy requireAuthentication = true -> APP_LAUNCH
        prefs.edit().putBoolean("requireAuthentication", true).commit()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.APP_LAUNCH)
        assertThat(manager.isUnlocked()).isTrue()

        // Legacy requireAuthentication = false -> NONE
        prefs.edit().putBoolean("requireAuthentication", false).commit()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)

        // Setting AuthMode updates legacy boolean in sync
        manager.setAuthMode(AuthMode.APP_LAUNCH)
        assertThat(prefs.getBoolean("requireAuthentication", false)).isTrue()
        manager.setAuthMode(AuthMode.NONE)
        assertThat(prefs.getBoolean("requireAuthentication", true)).isFalse()
    }

    @Test
    fun biometricWrapUnwrapInvalidationAndManualRecovery() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val backend = FakeKeyStoreBackend()
        val manager = TokenCryptoManager(prefs, NoOpDatabase()).apply {
            keyStoreBackend = backend
        }

        val salt = CryptoEngine.generateSalt()
        val iterations = 1_500
        val keyBytes = CryptoEngine.deriveKey("strongKey123".toCharArray(), salt, iterations)
        val verifier = CryptoEngine.createKeyVerifier(keyBytes)

        val encryptCipher = manager.createBiometricEncryptCipher()
        val wrappedBlob = manager.wrapKeyWithBiometricCipher(encryptCipher, keyBytes)

        prefs.edit()
            .putString(TokenCryptoManager.KDF_SALT_KEY, CryptoEngine.encodeBase64(salt))
            .putInt(TokenCryptoManager.KDF_ITERATIONS_KEY, iterations)
            .putString(TokenCryptoManager.KEY_VERIFIER_KEY, verifier)
            .putString(TokenCryptoManager.BIOMETRIC_ENCRYPTED_KEY, wrappedBlob)
            .putString(TokenCryptoManager.AUTH_MODE_KEY, AuthMode.TOKEN_ENCRYPTED.name)
            .commit()

        assertThat(manager.isUnlocked()).isFalse()

        // 1. Unlock via biometric decrypt cipher
        val prep = manager.prepareBiometricDecryptCipher()
        assertThat(prep.status).isEqualTo(TokenCryptoManager.BiometricKeyStatus.READY)
        assertThat(manager.unlockWithBiometricCipher(prep.cipher)).isTrue()
        assertThat(manager.isUnlocked()).isTrue()

        // 2. Encrypt a token and generate HOTP code
        val plainToken = OtpToken(
            id = 1L,
            ordinal = 1L,
            issuer = "github.com",
            label = "alice",
            imagePath = null,
            tokenType = OtpTokenType.HOTP,
            algorithm = "SHA256",
            secret = "abcd5432",
            digits = 6,
            counter = -1L,
            period = 30,
            encryptionType = EncryptionType.PLAIN_TEXT
        )
        val encryptedToken = manager.encryptTokenForStorage(plainToken)
        assertThat(encryptedToken.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
        assertThat(encryptedToken.secret).startsWith("v1:")
        assertThat(TokenCodeUtil(manager).generateTokenCode(encryptedToken).currentCode)
            .isEqualTo("248759")

        // 3. Lock session -> decrypting while locked throws IllegalStateException
        manager.lock()
        assertThat(manager.isUnlocked()).isFalse()
        assertThrows(IllegalStateException::class.java) {
            manager.decryptSecret(encryptedToken)
        }

        // 4. Simulate biometric invalidation -> prepareBiometricDecryptCipher returns INVALIDATED
        backend.throwOnDecrypt = true
        val invalidatedPrep = manager.prepareBiometricDecryptCipher()
        assertThat(invalidatedPrep.status).isEqualTo(TokenCryptoManager.BiometricKeyStatus.INVALIDATED)
        assertThat(manager.wasBiometricInvalidated()).isTrue()
        assertThat(manager.isBiometricKeyConfigured()).isFalse()

        // 5. Wrong manual key fails; right manual key unlocks and re-enables biometric
        assertThat(manager.unlockWithPassphrase("wrongKey".toCharArray())).isFalse()
        assertThat(manager.unlockWithPassphrase("strongKey123".toCharArray())).isTrue()
        assertThat(manager.isUnlocked()).isTrue()

        val newEncryptCipher = manager.createBiometricEncryptCipher()
        manager.saveBiometricEncryptedCurrentKey(newEncryptCipher)
        assertThat(manager.isBiometricKeyConfigured()).isTrue()
        assertThat(manager.wasBiometricInvalidated()).isFalse()

        // 6. Lock and verify biometric unlock works again with the newly enrolled key
        manager.lock()
        val reenabledPrep = manager.prepareBiometricDecryptCipher()
        assertThat(reenabledPrep.status).isEqualTo(TokenCryptoManager.BiometricKeyStatus.READY)
        assertThat(manager.unlockWithBiometricCipher(reenabledPrep.cipher)).isTrue()
        assertThat(manager.decryptSecret(encryptedToken)).isEqualTo("abcd5432")
    }

    @Test
    fun encryptedJsonBackup_detectsAndDecryptsAndRejectsWrongKey() {
        val gson = Gson()
        val prefs = InMemorySharedPreferences()
        val manager = TokenCryptoManager(prefs, NoOpDatabase())

        val salt = CryptoEngine.generateSalt()
        val iterations = 1_500
        val backupKey = CryptoEngine.deriveKey("backupSecretKey".toCharArray(), salt, iterations)
        val verifier = CryptoEngine.createKeyVerifier(backupKey)
        val encryptedSecret = CryptoEngine.encryptSecret("JBSWY3DPEHPK3PXP", backupKey)

        val bundle = EncryptedExportBundle(
            version = 1,
            encrypted = true,
            kdfSalt = CryptoEngine.encodeBase64(salt),
            kdfIterations = iterations,
            keyVerifier = verifier,
            tokens = listOf(
                EncryptedExportToken(
                    issuer = "GitHub",
                    label = "alice@example.com",
                    imagePath = null,
                    tokenType = OtpTokenType.TOTP,
                    algorithm = "SHA1",
                    secret = encryptedSecret,
                    digits = 6,
                    counter = 0,
                    period = 30,
                    encryptionType = EncryptionType.ENCRYPTED,
                    category = "Work"
                )
            )
        )
        val json = gson.toJson(bundle)

        assertThat(ImportExportUtil.isJsonStringEncrypted(json)).isTrue()
        assertThat(ImportExportUtil.isJsonStringEncrypted("""{"tokens":[],"tokenOrder":[]}""")).isFalse()

        assertThrows(InvalidEncryptionKeyException::class.java) {
            ImportExportUtil.decryptJsonBackup(gson, json, "wrongPassword".toCharArray())
        }

        val decrypted = ImportExportUtil.decryptJsonBackup(gson, json, "backupSecretKey".toCharArray())
        assertThat(decrypted).hasSize(1)
        assertThat(decrypted[0].issuer).isEqualTo("GitHub")
        assertThat(decrypted[0].label).isEqualTo("alice@example.com")
        assertThat(decrypted[0].secret).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(decrypted[0].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(decrypted[0].categories).containsExactly("Work")
    }
}
