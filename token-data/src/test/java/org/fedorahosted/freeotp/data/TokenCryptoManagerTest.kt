package org.fedorahosted.freeotp.data

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCryptoManagerTest {

    private val openDatabases = mutableListOf<OtpTokenDatabase>()

    @After
    fun tearDown() {
        openDatabases.forEach { db -> runCatching { db.close() } }
        openDatabases.clear()
    }

    private class InMemorySharedPreferences(
        initialData: Map<String, Any?> = emptyMap()
    ) : SharedPreferences {
        private val data = initialData.toMutableMap()
        var onBeforeCommit: ((staged: Map<String, Any?>, removals: Set<String>) -> Boolean)? = null

        fun snapshot(): InMemorySharedPreferences = InMemorySharedPreferences(data.toMap())

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }

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
                val allowed = onBeforeCommit?.invoke(staged.toMap(), removals.toSet()) ?: true
                if (!allowed) {
                    return false
                }
                val changedKeys = LinkedHashSet<String>()
                if (clearAll) {
                    changedKeys.addAll(data.keys)
                    data.clear()
                }
                removals.forEach {
                    if (!staged.containsKey(it)) {
                        if (data.containsKey(it)) changedKeys.add(it)
                        data.remove(it)
                    }
                }
                staged.forEach { (k, v) ->
                    if (data[k] != v) changedKeys.add(k)
                }
                data.putAll(staged)
                for (key in changedKeys) {
                    for (listener in listeners.toList()) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                    }
                }
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

    private suspend fun createInMemoryDb(initialTokens: List<OtpToken> = emptyList()): OtpTokenDatabase {
        val db = Room.inMemoryDatabaseBuilder<OtpTokenDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        openDatabases.add(db)
        if (initialTokens.isNotEmpty()) {
            db.otpTokenDao().insertAll(initialTokens)
        }
        return db
    }

    private suspend fun OtpTokenDatabase.allTokens(): List<OtpToken> =
        otpTokenDao().getAll().first()

    private fun createManager(
        prefs: InMemorySharedPreferences,
        db: OtpTokenDatabase,
        backend: FakeKeyStoreBackend = FakeKeyStoreBackend()
    ): TokenCryptoManager {
        return TokenCryptoManager(prefs, db).apply {
            keyStoreBackend = backend
            kdfIterationsOverride = 1_500
        }
    }

    private fun sampleHotpToken(id: Long = 1L, secret: String = "abcd5432") = OtpToken(
        id = id,
        ordinal = id,
        issuer = "github.com",
        label = "alice",
        imagePath = null,
        tokenType = OtpTokenType.HOTP,
        algorithm = "SHA256",
        secret = secret,
        digits = 6,
        counter = 0L,
        period = 30,
        encryptionType = EncryptionType.PLAIN_TEXT
    )

    @Test
    fun authMode_backwardCompatibleWithLegacyRequireAuthentication() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val manager = TokenCryptoManager(prefs, createInMemoryDb())

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
        val manager = createManager(prefs, createInMemoryDb(), backend)

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
        val plainToken = sampleHotpToken()
        val encryptedToken = manager.encryptTokenForStorage(plainToken)
        assertThat(encryptedToken.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
        assertThat(encryptedToken.secret).startsWith("v1:")
        assertThat(TokenCodeUtil(manager).generateTokenCode(encryptedToken).currentCode)
            .isEqualTo("047701")

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

    @Test
    fun enableTokenEncryption_stagingCommitFailure_leavesSqliteUntouched() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(listOf(sampleHotpToken()))
        val manager = createManager(prefs, db)

        val setup = manager.deriveSetupMaterial("passphrase123".toCharArray())
        prefs.onBeforeCommit = { staged, _ ->
            !staged.containsKey(TokenCryptoManager.PENDING_KDF_SALT_KEY)
        }

        assertThrows(IOException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup, null) }
        }

        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(db.allTokens().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(db.allTokens().single().secret).isEqualTo("abcd5432")
    }

    @Test
    fun enableTokenEncryption_sqliteFailure_rollsBackRealTransactionAndCleansPendingMetadata() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(listOf(sampleHotpToken()))
        val backend = FakeKeyStoreBackend()
        val manager = createManager(prefs, db, backend)
        val defaultRunner = manager.transactionRunner

        val setup = manager.deriveSetupMaterial("passphrase123".toCharArray())
        val bioCipher = manager.createBiometricEncryptCipher()
        val bioBlob = manager.wrapKeyWithBiometricCipher(bioCipher, setup.keyBytes)

        // Execute the encrypted row writes inside the real SQLite transaction, then throw before commit
        manager.transactionRunner = { block ->
            defaultRunner {
                block()
                throw IllegalStateException("Simulated mid-transaction SQLite failure")
            }
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup, bioBlob) }
        }

        assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(backend.key).isNull()
        assertThat(db.allTokens().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(db.allTokens().single().secret).isEqualTo("abcd5432")
    }

    @Test
    fun enableTokenEncryption_crashBeforeVsAfterSqliteCommit_recoversWithoutDataLoss() = runBlocking {
        // 1. Crash Point A: process dies AFTER staging SharedPreferences, BEFORE SQLite commits
        val prefsCrashA = InMemorySharedPreferences()
        val dbCrashA = createInMemoryDb(listOf(sampleHotpToken(1L), sampleHotpToken(2L, "JBSWY3DPEHPK3PXP")))
        val managerA = createManager(prefsCrashA, dbCrashA)
        val defaultRunnerA = managerA.transactionRunner
        val setupA = managerA.deriveSetupMaterial("crashKeyA".toCharArray())

        lateinit var crashedPrefsSnapshotA: InMemorySharedPreferences
        managerA.transactionRunner = { block ->
            defaultRunnerA {
                block()
                // Capture exact disk state while SQLite writes are uncommitted, then abort real SQLite tx
                crashedPrefsSnapshotA = prefsCrashA.snapshot()
                throw RuntimeException("Simulated process kill before SQLite commit")
            }
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking { managerA.enableTokenEncryption(setupA, null) }
        }
        assertThat(crashedPrefsSnapshotA.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isTrue()

        // Restart in a brand-new TokenCryptoManager process with crashedPrefsSnapshotA + rolled-back SQLite
        val restartedManagerA = createManager(crashedPrefsSnapshotA, dbCrashA)
        assertThat(restartedManagerA.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(crashedPrefsSnapshotA.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(dbCrashA.allTokens().map { it.secret }).containsExactly("abcd5432", "JBSWY3DPEHPK3PXP")

        // 2. Crash Point B (F2): process dies AFTER SQLite commits encrypted rows, BEFORE active KDF_SALT_KEY commit!
        val prefsCrashB = InMemorySharedPreferences()
        val dbCrashB = createInMemoryDb(listOf(sampleHotpToken(1L), sampleHotpToken(2L, "JBSWY3DPEHPK3PXP")))
        val backendB = FakeKeyStoreBackend()
        val managerB = createManager(prefsCrashB, dbCrashB, backendB)
        val setupB = managerB.deriveSetupMaterial("crashKeyB".toCharArray())
        val bioCipherB = managerB.createBiometricEncryptCipher()
        val bioBlobB = managerB.wrapKeyWithBiometricCipher(bioCipherB, setupB.keyBytes)

        prefsCrashB.onBeforeCommit = { staged, _ ->
            // Allow staging commit (Step 1), fail active promotion commit (Step 3) after SQLite already committed!
            !staged.containsKey(TokenCryptoManager.KDF_SALT_KEY)
        }
        assertThrows(IOException::class.java) {
            runBlocking { managerB.enableTokenEncryption(setupB, bioBlobB) }
        }

        // Verify exact F2 crash state on disk: SQLite rows ARE encrypted, active KDF_SALT_KEY is missing, PENDING_* is present
        assertThat(dbCrashB.allTokens().all { it.encryptionType == EncryptionType.ENCRYPTED }).isTrue()
        assertThat(prefsCrashB.contains(TokenCryptoManager.KDF_SALT_KEY)).isFalse()
        assertThat(prefsCrashB.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isTrue()

        // Clear fault injection and simulate fresh app restart
        prefsCrashB.onBeforeCommit = null
        val restartedManagerB = TokenCryptoManager(prefsCrashB, dbCrashB).apply {
            keyStoreBackend = backendB
        }

        assertThat(restartedManagerB.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(restartedManagerB.isUnlocked()).isFalse()
        assertThat(prefsCrashB.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(prefsCrashB.getString(TokenCryptoManager.KDF_SALT_KEY, null)).isEqualTo(setupB.kdfSalt)

        // Unlock with passphrase -> recovers 100% of tokens and generates exact HOTP code
        assertThat(restartedManagerB.unlockWithPassphrase("crashKeyB".toCharArray())).isTrue()
        val recoveredTokens = dbCrashB.allTokens()
        assertThat(restartedManagerB.decryptSecret(recoveredTokens[0])).isEqualTo("abcd5432")
        assertThat(restartedManagerB.decryptSecret(recoveredTokens[1])).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(TokenCodeUtil(restartedManagerB).generateTokenCode(recoveredTokens[0]).currentCode)
            .isEqualTo("047701")

        // Also verify promoted biometric blob unlocks cleanly after locking
        restartedManagerB.lock()
        val bioPrep = restartedManagerB.prepareBiometricDecryptCipher()
        assertThat(bioPrep.status).isEqualTo(TokenCryptoManager.BiometricKeyStatus.READY)
        assertThat(restartedManagerB.unlockWithBiometricCipher(bioPrep.cipher)).isTrue()
        assertThat(restartedManagerB.decryptSecret(recoveredTokens[0])).isEqualTo("abcd5432")
    }

    @Test
    fun rekeyAndDisableEncryption_interruptedTransitions_recoverDeterministically() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(listOf(sampleHotpToken()))
        val manager = createManager(prefs, db)
        val defaultRunner = manager.transactionRunner

        val setup1 = manager.deriveSetupMaterial("initialKey1".toCharArray())
        manager.enableTokenEncryption(setup1, null)

        // Re-key Crash Point A (before SQLite commits) -> rolls back SQLite and preserves initialKey1
        val setup2 = manager.deriveSetupMaterial("rotatedKey2".toCharArray())
        lateinit var rekeyCrashAPrefs: InMemorySharedPreferences
        manager.transactionRunner = { block ->
            defaultRunner {
                block()
                rekeyCrashAPrefs = prefs.snapshot()
                throw RuntimeException("Simulated crash before SQLite re-key commit")
            }
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup2, null) }
        }
        val recoveredRekeyA = createManager(rekeyCrashAPrefs, db)
        assertThat(recoveredRekeyA.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(recoveredRekeyA.unlockWithPassphrase("initialKey1".toCharArray())).isTrue()
        assertThat(recoveredRekeyA.decryptSecret(db.allTokens().single())).isEqualTo("abcd5432")

        // Test Disable Crash Point B (after SQLite decrypts, before active prefs commit)
        val disableManager = createManager(prefs, db)
        assertThat(disableManager.unlockWithPassphrase("initialKey1".toCharArray())).isTrue()
        prefs.onBeforeCommit = { staged, _ ->
            // Allow staging PENDING_DISABLE_TARGET_MODE_KEY, fail final AUTH_MODE_KEY commit
            !staged.containsKey(TokenCryptoManager.AUTH_MODE_KEY)
        }
        assertThrows(IOException::class.java) {
            runBlocking { disableManager.disableTokenEncryption(AuthMode.APP_LAUNCH) }
        }
        assertThat(db.allTokens().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isTrue()

        prefs.onBeforeCommit = null
        val restartedAfterDisable = createManager(prefs, db)
        assertThat(restartedAfterDisable.getAuthMode()).isEqualTo(AuthMode.APP_LAUNCH)
        assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isFalse()
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isFalse()
        assertThat(db.allTokens().single().secret).isEqualTo("abcd5432")
    }

    @Test
    fun tokenCodeUtil_handlesLockedVaultNullManagerAndCorruptedCiphertextWithoutCrash() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(listOf(sampleHotpToken()))
        val manager = createManager(prefs, db)

        val setup = manager.deriveSetupMaterial("vaultKey123".toCharArray())
        manager.enableTokenEncryption(setup, null)

        val validEncryptedHotp = db.allTokens().single()
        val validEncryptedTotp = validEncryptedHotp.copy(id = 2L, tokenType = OtpTokenType.TOTP)
        assertThat(TokenCodeUtil(manager).generateTokenCode(validEncryptedHotp).currentCode)
            .isEqualTo("047701")

        // 1. Null TokenCryptoManager returns empty code instead of crashing
        assertThat(TokenCodeUtil().generateTokenCode(validEncryptedHotp).currentCode).isEmpty()
        assertThat(TokenCodeUtil().generateTokenCode(validEncryptedTotp).currentCode).isEmpty()

        // 2. Corrupted ciphertext payloads return empty code instead of throwing GeneralSecurityException
        val corruptedPayloads = listOf(
            validEncryptedHotp.secret.dropLast(4) + "AAAA",
            "v1:AAAA",
            "v1:%%%NOT_BASE64%%%",
            "corrupted_without_prefix"
        )
        for (badSecret in corruptedPayloads) {
            assertThat(
                TokenCodeUtil(manager)
                    .generateTokenCode(validEncryptedHotp.copy(secret = badSecret))
                    .currentCode
            ).isEmpty()
            assertThat(
                TokenCodeUtil(manager)
                    .generateTokenCode(validEncryptedTotp.copy(secret = badSecret))
                    .currentCode
            ).isEmpty()
        }

        // 3. Invalid non-positive digits never infinite-loops or crashes
        assertThat(
            TokenCodeUtil(manager)
                .generateTokenCode(validEncryptedHotp.copy(digits = 0))
                .currentCode
        ).isEmpty()

        // 4. Locked TokenCryptoManager returns empty code instead of throwing IllegalStateException
        manager.lock()
        assertThat(TokenCodeUtil(manager).generateTokenCode(validEncryptedHotp).currentCode).isEmpty()
        assertThat(TokenCodeUtil(manager).generateTokenCode(validEncryptedTotp).currentCode).isEmpty()
    }

    @Test
    fun enableAndDisableTokenEncryption_outerScopeCancelledMidTransaction_completesWithoutDataLoss(): Unit = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(
            listOf(
                sampleHotpToken(1L, "abcd5432"),
                sampleHotpToken(2L, "JBSWY3DPEHPK3PXP")
            )
        )
        val manager = createManager(prefs, db)
        val defaultRunner = manager.transactionRunner

        // 1. Simulate Activity.lifecycleScope cancellation + caller setup.clear() mid-SQLite transaction
        val enteredTx = CountDownLatch(1)
        val proceedTx = CountDownLatch(1)
        manager.transactionRunner = { block ->
            enteredTx.countDown()
            assertThat(proceedTx.await(5, TimeUnit.SECONDS)).isTrue()
            defaultRunner(block)
        }

        val setup = manager.deriveSetupMaterial("rotateKey123".toCharArray())
        val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val enableJob = activityScope.launch {
            try {
                manager.enableTokenEncryption(setup, null)
            } finally {
                setup.clear()
            }
        }

        assertThat(enteredTx.await(5, TimeUnit.SECONDS)).isTrue()
        activityScope.cancel()
        setup.clear()
        proceedTx.countDown()
        enableJob.join()

        val encryptedTokens = db.allTokens()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(manager.isUnlocked()).isTrue()
        assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(encryptedTokens.all { it.encryptionType == EncryptionType.ENCRYPTED }).isTrue()
        assertThat(manager.decryptSecret(encryptedTokens[0])).isEqualTo("abcd5432")
        assertThat(manager.decryptSecret(encryptedTokens[1])).isEqualTo("JBSWY3DPEHPK3PXP")

        manager.lock()
        assertThat(manager.unlockWithPassphrase("rotateKey123".toCharArray())).isTrue()

        // 2. Simulate Activity.lifecycleScope cancellation mid-SQLite transaction during disableTokenEncryption
        val enteredDisableTx = CountDownLatch(1)
        val proceedDisableTx = CountDownLatch(1)
        manager.transactionRunner = { block ->
            enteredDisableTx.countDown()
            assertThat(proceedDisableTx.await(5, TimeUnit.SECONDS)).isTrue()
            defaultRunner(block)
        }

        val disableScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val disableJob = disableScope.launch {
            manager.disableTokenEncryption(AuthMode.APP_LAUNCH)
        }
        assertThat(enteredDisableTx.await(5, TimeUnit.SECONDS)).isTrue()
        disableScope.cancel()
        proceedDisableTx.countDown()
        disableJob.join()

        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.APP_LAUNCH)
        assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isFalse()
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isFalse()
        assertThat(db.allTokens().map { it.encryptionType to it.secret }).containsExactly(
            EncryptionType.PLAIN_TEXT to "abcd5432",
            EncryptionType.PLAIN_TEXT to "JBSWY3DPEHPK3PXP"
        )
    }

    @Test
    fun enableAndDisableTokenEncryption_cancellationThrownAfterSqliteCommit_promotesMetadataInsteadOfLockout(): Unit = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(
            listOf(
                sampleHotpToken(1L, "abcd5432"),
                sampleHotpToken(2L, "JBSWY3DPEHPK3PXP")
            )
        )
        val backend = FakeKeyStoreBackend()
        val manager = createManager(prefs, db, backend)
        val defaultRunner = manager.transactionRunner

        val setup = manager.deriveSetupMaterial("f14PostCommitKey".toCharArray())
        val bioCipher = manager.createBiometricEncryptCipher()
        val bioBlob = manager.wrapKeyWithBiometricCipher(bioCipher, setup.keyBytes)

        // Exact F14 failure mode: real SQLite commits encrypted rows, then transactionRunner throws CancellationException
        manager.transactionRunner = { block ->
            defaultRunner(block)
            throw CancellationException("Simulated coroutine cancellation immediately after SQLite commit")
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                try {
                    manager.enableTokenEncryption(setup, bioBlob)
                } finally {
                    setup.clear()
                }
            }
        }

        val encryptedTokens = db.allTokens()
        assertThat(encryptedTokens.all { it.encryptionType == EncryptionType.ENCRYPTED }).isTrue()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(prefs.getString(TokenCryptoManager.KDF_SALT_KEY, null)).isEqualTo(setup.kdfSalt)
        assertThat(manager.isUnlocked()).isTrue()
        assertThat(manager.decryptSecret(encryptedTokens[0])).isEqualTo("abcd5432")

        manager.lock()
        assertThat(manager.unlockWithPassphrase("f14PostCommitKey".toCharArray())).isTrue()
        assertThat(manager.decryptSecret(encryptedTokens[1])).isEqualTo("JBSWY3DPEHPK3PXP")

        // Same post-commit CancellationException during disableTokenEncryption -> finalizes target mode cleanly
        assertThrows(CancellationException::class.java) {
            runBlocking {
                manager.disableTokenEncryption(AuthMode.NONE)
            }
        }
        val plainTokens = db.allTokens()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isFalse()
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isFalse()
        assertThat(plainTokens.all { it.encryptionType == EncryptionType.PLAIN_TEXT }).isTrue()
        assertThat(plainTokens.map { it.secret }).containsExactly("abcd5432", "JBSWY3DPEHPK3PXP")
    }

    @Test
    fun enableTokenEncryption_concurrentSampleTokenDeletionBeforeTx_restagesAndRecoversOnCrash(): Unit = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(
            listOf(
                sampleHotpToken(1L, "abcd5432"),
                sampleHotpToken(2L, "JBSWY3DPEHPK3PXP")
            )
        )
        val manager = createManager(prefs, db)
        val defaultRunner = manager.transactionRunner

        val setup = manager.deriveSetupMaterial("restageKey99".toCharArray())
        manager.transactionRunner = { block ->
            // Simulate token #1 (initial staged sample) deleted right before SQLite transaction runs
            db.otpTokenDao().deleteById(1L)
            defaultRunner(block)
        }
        prefs.onBeforeCommit = { staged, _ ->
            !staged.containsKey(TokenCryptoManager.KDF_SALT_KEY)
        }

        assertThrows(IOException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup, null) }
        }

        prefs.onBeforeCommit = null
        val restarted = createManager(prefs, db)
        assertThat(restarted.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(restarted.unlockWithPassphrase("restageKey99".toCharArray())).isTrue()
        assertThat(restarted.decryptSecret(db.allTokens().single())).isEqualTo("JBSWY3DPEHPK3PXP")
    }

    @Test
    fun enableTokenEncryption_setsSessionKeyBeforePublishingAuthModeChange(): Unit = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = createInMemoryDb(listOf(sampleHotpToken(1L, "JBSWY3DPEHPK3PXP")))
        val manager = createManager(prefs, db)
        var unlockedWhenAuthModeChanged: Boolean? = null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TokenCryptoManager.AUTH_MODE_KEY && manager.getAuthMode() == AuthMode.TOKEN_ENCRYPTED) {
                unlockedWhenAuthModeChanged = manager.isUnlocked()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        val setup = manager.deriveSetupMaterial("noDoublePromptKey1".toCharArray())
        try {
            manager.enableTokenEncryption(setup, null)
        } finally {
            setup.clear()
        }

        assertThat(unlockedWhenAuthModeChanged).isTrue()
    }
}
