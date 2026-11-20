package org.fedorahosted.freeotp.data

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenCryptoManagerTest {

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
                val allowed = onBeforeCommit?.invoke(staged.toMap(), removals.toSet()) ?: true
                if (!allowed) {
                    return false
                }
                if (clearAll) data.clear()
                removals.forEach {
                    if (!staged.containsKey(it)) {
                        data.remove(it)
                    }
                }
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

    private class FakeOtpTokenDao(initialTokens: List<OtpToken> = emptyList()) : OtpTokenDao {
        private val tokensFlow = MutableStateFlow(initialTokens.sortedBy { it.ordinal })

        fun snapshot(): List<OtpToken> = tokensFlow.value.toList()
        fun restore(snapshot: List<OtpToken>) {
            tokensFlow.value = snapshot.sortedBy { it.ordinal }
        }

        override fun getAll(): Flow<List<OtpToken>> = tokensFlow
        override fun get(id: Long): Flow<OtpToken?> = tokensFlow.map { list -> list.find { it.id == id } }
        override fun getLastOrdinal(): Long? = tokensFlow.value.maxOfOrNull { it.ordinal }
        override fun getAllCategoryJsonRows(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun deleteById(id: Long) {
            tokensFlow.value = tokensFlow.value.filterNot { it.id == id }
        }
        override suspend fun deleteAll() {
            tokensFlow.value = emptyList()
        }
        override suspend fun insertAll(otpTokenList: List<OtpToken>) {
            val map = tokensFlow.value.associateBy { it.id }.toMutableMap()
            otpTokenList.forEach { map[it.id] = it }
            tokensFlow.value = map.values.sortedBy { it.ordinal }
        }
        override suspend fun insert(otpTokenList: OtpToken) {
            insertAll(listOf(otpTokenList))
        }
        override suspend fun update(otpTokenList: OtpToken) {
            insert(otpTokenList)
        }
        override suspend fun updateAll(otpTokenList: List<OtpToken>) {
            insertAll(otpTokenList)
        }
        override suspend fun updateOrdinal(id: Long, ordinal: Long) {
            tokensFlow.value = tokensFlow.value
                .map { if (it.id == id) it.copy(ordinal = ordinal) else it }
                .sortedBy { it.ordinal }
        }
        override suspend fun incrementCounter(id: Long) {
            tokensFlow.value = tokensFlow.value.map {
                if (it.id == id) it.copy(counter = it.counter + 1) else it
            }
        }
    }

    private class FakeDatabase(val dao: FakeOtpTokenDao = FakeOtpTokenDao()) : OtpTokenDatabase() {
        override fun otpTokenDao(): OtpTokenDao = dao
        override fun createInvalidationTracker() = throw UnsupportedOperationException()
        override fun clearAllTables() {
            dao.restore(emptyList())
        }
        override fun createOpenHelper(config: androidx.room.DatabaseConfiguration) =
            throw UnsupportedOperationException()
    }

    private fun createManagerWithFakeDb(
        prefs: InMemorySharedPreferences,
        db: FakeDatabase,
        backend: FakeKeyStoreBackend = FakeKeyStoreBackend()
    ): TokenCryptoManager {
        return TokenCryptoManager(prefs, db).apply {
            keyStoreBackend = backend
            kdfIterationsOverride = 1_500
            transactionRunner = { block ->
                val before = db.dao.snapshot()
                try {
                    block()
                } catch (t: Throwable) {
                    db.dao.restore(before)
                    throw t
                }
            }
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
        counter = -1L,
        period = 30,
        encryptionType = EncryptionType.PLAIN_TEXT
    )

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

    @Test
    fun enableTokenEncryption_stagingCommitFailure_leavesSqliteUntouched() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = FakeDatabase(FakeOtpTokenDao(listOf(sampleHotpToken())))
        val manager = createManagerWithFakeDb(prefs, db)

        val setup = manager.deriveSetupMaterial("passphrase123".toCharArray())
        prefs.onBeforeCommit = { staged, _ ->
            !staged.containsKey(TokenCryptoManager.PENDING_KDF_SALT_KEY)
        }

        assertThrows(IOException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup, null) }
        }

        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(db.dao.snapshot().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(db.dao.snapshot().single().secret).isEqualTo("abcd5432")
    }

    @Test
    fun enableTokenEncryption_sqliteFailure_rollsBackAndCleansPendingMetadata() = runBlocking {
        val prefs = InMemorySharedPreferences()
        val db = FakeDatabase(FakeOtpTokenDao(listOf(sampleHotpToken())))
        val backend = FakeKeyStoreBackend()
        val manager = createManagerWithFakeDb(prefs, db, backend)

        val setup = manager.deriveSetupMaterial("passphrase123".toCharArray())
        val bioCipher = manager.createBiometricEncryptCipher()
        val bioBlob = manager.wrapKeyWithBiometricCipher(bioCipher, setup.keyBytes)

        manager.transactionRunner = {
            throw IllegalStateException("Simulated SQLite transaction failure")
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup, bioBlob) }
        }

        assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(manager.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(backend.key).isNull()
        assertThat(db.dao.snapshot().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(db.dao.snapshot().single().secret).isEqualTo("abcd5432")
    }

    @Test
    fun enableTokenEncryption_crashBeforeVsAfterSqliteCommit_recoversWithoutDataLoss() = runBlocking {
        // 1. Crash Point A: process dies AFTER staging SharedPreferences, BEFORE SQLite commits
        val prefsCrashA = InMemorySharedPreferences()
        val dbCrashA = FakeDatabase(FakeOtpTokenDao(listOf(sampleHotpToken(1L), sampleHotpToken(2L, "JBSWY3DPEHPK3PXP"))))
        val managerA = createManagerWithFakeDb(prefsCrashA, dbCrashA)
        val setupA = managerA.deriveSetupMaterial("crashKeyA".toCharArray())

        lateinit var crashedPrefsSnapshotA: InMemorySharedPreferences
        managerA.transactionRunner = {
            // Capture exact disk state right before SQLite commits, then abort SQLite
            crashedPrefsSnapshotA = prefsCrashA.snapshot()
            throw RuntimeException("Simulated process kill before SQLite commit")
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking { managerA.enableTokenEncryption(setupA, null) }
        }
        assertThat(crashedPrefsSnapshotA.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isTrue()

        // Restart in a brand-new TokenCryptoManager process with crashedPrefsSnapshotA + untouched SQLite
        val restartedManagerA = createManagerWithFakeDb(crashedPrefsSnapshotA, dbCrashA)
        assertThat(restartedManagerA.getAuthMode()).isEqualTo(AuthMode.NONE)
        assertThat(crashedPrefsSnapshotA.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
        assertThat(dbCrashA.dao.snapshot().map { it.secret }).containsExactly("abcd5432", "JBSWY3DPEHPK3PXP")

        // 2. Crash Point B (F2): process dies AFTER SQLite commits encrypted rows, BEFORE active KDF_SALT_KEY commit!
        val prefsCrashB = InMemorySharedPreferences()
        val dbCrashB = FakeDatabase(FakeOtpTokenDao(listOf(sampleHotpToken(1L), sampleHotpToken(2L, "JBSWY3DPEHPK3PXP"))))
        val backendB = FakeKeyStoreBackend()
        val managerB = createManagerWithFakeDb(prefsCrashB, dbCrashB, backendB)
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
        assertThat(dbCrashB.dao.snapshot().all { it.encryptionType == EncryptionType.ENCRYPTED }).isTrue()
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
        val recoveredTokens = dbCrashB.dao.snapshot()
        assertThat(restartedManagerB.decryptSecret(recoveredTokens[0])).isEqualTo("abcd5432")
        assertThat(restartedManagerB.decryptSecret(recoveredTokens[1])).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(TokenCodeUtil(restartedManagerB).generateTokenCode(recoveredTokens[0]).currentCode)
            .isEqualTo("248759")

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
        val db = FakeDatabase(FakeOtpTokenDao(listOf(sampleHotpToken())))
        val manager = createManagerWithFakeDb(prefs, db)

        val setup1 = manager.deriveSetupMaterial("initialKey1".toCharArray())
        manager.enableTokenEncryption(setup1, null)

        // Re-key Crash Point A (before SQLite commits) -> preserves initialKey1
        val setup2 = manager.deriveSetupMaterial("rotatedKey2".toCharArray())
        lateinit var rekeyCrashAPrefs: InMemorySharedPreferences
        manager.transactionRunner = {
            rekeyCrashAPrefs = prefs.snapshot()
            throw RuntimeException("Simulated crash before SQLite re-key commit")
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking { manager.enableTokenEncryption(setup2, null) }
        }
        val recoveredRekeyA = createManagerWithFakeDb(rekeyCrashAPrefs, db)
        assertThat(recoveredRekeyA.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
        assertThat(recoveredRekeyA.unlockWithPassphrase("initialKey1".toCharArray())).isTrue()
        assertThat(recoveredRekeyA.decryptSecret(db.dao.snapshot().single())).isEqualTo("abcd5432")

        // Restore normal runner on manager and test Disable Crash Point B (after SQLite decrypts, before active prefs commit)
        val disableManager = createManagerWithFakeDb(prefs, db)
        assertThat(disableManager.unlockWithPassphrase("initialKey1".toCharArray())).isTrue()
        prefs.onBeforeCommit = { staged, _ ->
            // Allow staging PENDING_DISABLE_TARGET_MODE_KEY, fail final AUTH_MODE_KEY commit
            !staged.containsKey(TokenCryptoManager.AUTH_MODE_KEY)
        }
        assertThrows(IOException::class.java) {
            runBlocking { disableManager.disableTokenEncryption(AuthMode.APP_LAUNCH) }
        }
        assertThat(db.dao.snapshot().single().encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isTrue()

        prefs.onBeforeCommit = null
        val restartedAfterDisable = createManagerWithFakeDb(prefs, db)
        assertThat(restartedAfterDisable.getAuthMode()).isEqualTo(AuthMode.APP_LAUNCH)
        assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isFalse()
        assertThat(prefs.contains(TokenCryptoManager.PENDING_DISABLE_TARGET_MODE_KEY)).isFalse()
        assertThat(db.dao.snapshot().single().secret).isEqualTo("abcd5432")
    }
}
