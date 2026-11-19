package org.fedorahosted.freeotp.uitest

import android.preference.PreferenceManager
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.ui.EditTokenFragment
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.Settings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4ClassRunner::class)
class TokenEncryptionTest {

    private class FakeKeyStoreBackend : TokenCryptoManager.KeyStoreBackend {
        var secretKey: SecretKey? = null
        var invalidated: Boolean = false

        override fun createEncryptCipher(): Cipher {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val key = keyGen.generateKey()
            secretKey = key
            invalidated = false
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        }

        override fun createDecryptCipher(iv: ByteArray): Cipher? {
            if (invalidated) {
                throw KeyPermanentlyInvalidatedException("Simulated biometric invalidation")
            }
            val key = secretKey ?: return null
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
        }

        override fun deleteKey() {
            secretKey = null
            invalidated = false
        }
    }

    private var registeredIdlingResource: IdlingResource? = null
    private var boundCryptoManager: TokenCryptoManager? = null

    private fun attachCryptoIdlingResource(manager: TokenCryptoManager) {
        if (boundCryptoManager === manager) return
        registeredIdlingResource?.let { IdlingRegistry.getInstance().unregister(it) }
        boundCryptoManager = manager
        manager.kdfIterationsOverride = 2_000
        val resource = object : IdlingResource {
            @Volatile
            private var callback: IdlingResource.ResourceCallback? = null

            override fun getName(): String = "TokenCryptoManagerIdlingResource"

            override fun isIdleNow(): Boolean {
                val idle = manager.activeAsyncOperations.get() == 0
                if (idle) {
                    callback?.onTransitionToIdle()
                }
                return idle
            }

            override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
                this.callback = callback
                manager.onAsyncOperationIdle = {
                    callback?.onTransitionToIdle()
                }
            }
        }
        registeredIdlingResource = resource
        IdlingRegistry.getInstance().register(resource)
    }

    @Before
    fun setUp() {
        val context = getInstrumentation().targetContext
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AuthenticationUtil.hasScreenLockOverride = false
        AuthenticationUtil.hasStrongBiometricOverride = false
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        AuthenticationUtil.biometricCryptoCallbackOverride = null
    }

    @After
    fun tearDown() {
        registeredIdlingResource?.let { IdlingRegistry.getInstance().unregister(it) }
        registeredIdlingResource = null
        boundCryptoManager?.onAsyncOperationIdle = null
        boundCryptoManager?.kdfIterationsOverride = null
        boundCryptoManager?.keyStoreBackend = TokenCryptoManager.AndroidKeyStoreBackend()
        boundCryptoManager?.lock()
        boundCryptoManager = null
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.hasStrongBiometricOverride = null
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        AuthenticationUtil.biometricCryptoCallbackOverride = null
    }

    @Test
    fun testBackwardCompatibleSettingsUpgradeFromLegacyBoolean() {
        val context = getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val settings = Settings(prefs)

        prefs.edit().clear().putBoolean("requireAuthentication", false).commit()
        assertThat(settings.authMode).isEqualTo(AuthMode.NONE)
        assertThat(settings.requireAuthentication).isFalse()

        prefs.edit().clear().putBoolean("requireAuthentication", true).commit()
        assertThat(settings.authMode).isEqualTo(AuthMode.APP_LAUNCH)
        assertThat(settings.requireAuthentication).isTrue()

        assertThat(MainActivity.exportBaseFileName(encrypted = false)).isEqualTo("freeotp-backup")
        assertThat(MainActivity.exportBaseFileName(encrypted = true)).isEqualTo("freeotp-backup-encrypted")
    }

    @Test
    fun testEnableTokenEncryptionManualOnlyValidatesKeyEncryptsDbAndGeneratesOtp() {
        AuthenticationUtil.hasStrongBiometricOverride = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_HOTP_TOKEN_1)
                }
            }

            // Open Authentication mode dialog and choose "Token encrypted"
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_token_encrypted)).inRoot(isDialog()).perform(click())

            // Test key < 6 chars rejected
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("12345"), closeSoftKeyboard())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("12345"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            // Test mismatched confirm key rejected
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("secret123"), closeSoftKeyboard())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("secret999"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            // Enter valid matching 6+ char key
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("secret123"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            // Verify DB secret is encrypted at rest
            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
                runBlocking {
                    val stored = activity.otpTokenDatabase.otpTokenDao().getAll().first()
                    assertThat(stored).hasSize(1)
                    assertThat(stored[0].encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                    assertThat(stored[0].secret).startsWith("v1:")
                    assertThat(stored[0].secret).isNotEqualTo(TestData.OTP_HOTP_TOKEN_1.secret)
                }
            }

            // Tap HOTP token card and verify exact OTP code generation ("248 759")
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                .check(matches(hasDescendant(withText("248 759"))))

            // Add a new token while in Token encrypted mode and verify it is stored encrypted too
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.add_token)).perform(click())
            onView(withId(R.id.issuer)).perform(replaceText("gitlab.com"), closeSoftKeyboard())
            onView(withId(R.id.label)).perform(replaceText("bob"), closeSoftKeyboard())
            onView(withId(R.id.secret)).perform(replaceText("JBSWY3DPEHPK3PXP"), closeSoftKeyboard())
            onView(withId(R.id.add)).perform(click())

            scenario.onActivity { activity ->
                runBlocking {
                    val stored = activity.otpTokenDatabase.otpTokenDao().getAll().first()
                    assertThat(stored).hasSize(2)
                    stored.forEach { token ->
                        assertThat(token.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                        assertThat(token.secret).startsWith("v1:")
                    }
                }
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    bundleOf(EditTokenFragment.ARG_TOKEN_ID to 1L)
                )
            }

            // Verify EditTokenFragment secret reveal requires key verification when no screen lock is set and reveals plaintext
            onView(withId(R.id.secret))
                .perform(scrollTo())
                .check(matches(withText(R.string.click_to_display)))
                .perform(click())

            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("secret123"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            onView(withId(R.id.secret))
                .check(matches(withText(TestData.OTP_HOTP_TOKEN_1.secret)))
        }
    }

    @Test
    fun testDeferredBiometricEnableUnlockAndInvalidationRecovery() {
        val fakeBackend = FakeKeyStoreBackend()
        AuthenticationUtil.hasStrongBiometricOverride = false

        // 1. Setup Token encrypted mode without biometric enrolled
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_HOTP_TOKEN_1)
                }
            }

            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_token_encrypted)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("myMasterKey"), closeSoftKeyboard())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("myMasterKey"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isFalse()
                activity.tokenCryptoManager.lock()
            }
        }

        // 2. Simulate user enrolling strong biometric later; relaunch offers "Enable biometric unlock"
        AuthenticationUtil.hasStrongBiometricOverride = true
        var bioPromptCount = 0
        AuthenticationUtil.biometricCryptoCallbackOverride = { cipher, onSuccess, _, _ ->
            bioPromptCount++
            onSuccess(cipher)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.enable_biometric_checkbox)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
                .check(matches(isChecked()))

            // Wrong key stays in dialog
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("wrongKey"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))

            // Right key unlocks and wraps key with biometric
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("myMasterKey"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())

            assertThat(bioPromptCount).isEqualTo(1)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isTrue()
                activity.tokenCryptoManager.lock()
            }
        }

        // 3. Relaunch with valid biometric -> unlocks directly via biometric without manual key dialog!
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertThat(bioPromptCount).isEqualTo(2)
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))

            // Simulate biometric becoming invalidated (e.g. new fingerprint enrolled) and lock session
            fakeBackend.invalidated = true
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.lock()
            }
        }

        // 4. Relaunch after biometric invalidation -> prompts manual key + "Re-enable biometric unlock"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.biometric_invalidated_enter_key_message)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withId(R.id.enable_biometric_checkbox)).inRoot(isDialog())
                .perform(scrollTo())
                .check(matches(isDisplayed()))
                .check(matches(withText(R.string.reenable_biometric_unlock)))

            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("myMasterKey"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())

            // Re-encryption biometric prompt invoked
            assertThat(bioPromptCount).isEqualTo(3)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isTrue()
                assertThat(activity.tokenCryptoManager.wasBiometricInvalidated()).isFalse()
            }
            onView(withId(R.id.token_list))
                .check(matches(hasDescendant(withText("github.com"))))
        }
    }

    @Test
    fun testDisableTokenEncryptionShowsWarningAndDecryptsAllTokens() {
        AuthenticationUtil.hasStrongBiometricOverride = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insertAll(
                        listOf(TestData.OTP_HOTP_TOKEN_1, TestData.OTP_TOTP_TOKEN_2)
                    )
                }
            }

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                assertThat(toolbar.menu.findItem(R.id.quit_and_lock).isVisible).isFalse()
            }

            // Enable Token encrypted mode
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_token_encrypted)).inRoot(isDialog()).perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("rotateKey1"), closeSoftKeyboard())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("rotateKey1"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                assertThat(toolbar.menu.findItem(R.id.quit_and_lock).isVisible).isTrue()
            }

            // Switch back to "None" -> verify warning dialog -> verify key -> secrets restored to PLAIN_TEXT
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_none)).inRoot(isDialog()).perform(click())

            onView(withText(R.string.disable_token_encryption_warning)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("rotateKey1"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                assertThat(toolbar.menu.findItem(R.id.quit_and_lock).isVisible).isFalse()
                runBlocking {
                    val tokens = activity.otpTokenDatabase.otpTokenDao().getAll().first()
                    assertThat(tokens).hasSize(2)
                    assertThat(tokens[0].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
                    assertThat(tokens[0].secret).isEqualTo(TestData.OTP_HOTP_TOKEN_1.secret)
                    assertThat(tokens[1].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
                    assertThat(tokens[1].secret).isEqualTo(TestData.OTP_TOTP_TOKEN_2.secret)
                }
            }
        }
    }

    @Test
    fun testEncryptedAndDecryptedExportAndImportAcrossModes(): Unit = runBlocking {
        val context = getInstrumentation().targetContext
        val encJsonFile = File(context.cacheDir, "test_enc_export.json")
        val decJsonFile = File(context.cacheDir, "test_dec_export.json")
        val encUriFile = File(context.cacheDir, "test_enc_export.txt")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it }

            activity.otpTokenDatabase.clearAllTables()
            val dao = activity.otpTokenDatabase.otpTokenDao()
            dao.insertAll(listOf(TestData.OTP_HOTP_TOKEN_1, TestData.OTP_TOTP_TOKEN_2))

            // Enable Token encrypted mode with key "backupKey1"
            val setup1 = activity.tokenCryptoManager.deriveSetupMaterial("backupKey1".toCharArray())
            activity.tokenCryptoManager.enableTokenEncryption(setup1, null)
            setup1.clear()

            // Export encrypted & decrypted JSON, and encrypted Key URI
            activity.importFromUtil.exportJsonFile(encJsonFile.toUri(), encrypt = true)
            activity.importFromUtil.exportJsonFile(decJsonFile.toUri(), encrypt = false)
            activity.importFromUtil.exportKeyUriFile(encUriFile.toUri(), encrypt = true)

            assertThat(activity.importFromUtil.isJsonFileEncrypted(encJsonFile.toUri())).isTrue()
            assertThat(activity.importFromUtil.isJsonFileEncrypted(decJsonFile.toUri())).isFalse()
            assertThat(activity.importFromUtil.isKeyUriFileEncrypted(encUriFile.toUri())).isTrue()

            // Switch to NONE and keep existing tokens + insert high-ID tokens (id = 10, 11, 12)
            // to verify JSON import clears all existing tokens without creating duplicates!
            activity.tokenCryptoManager.disableTokenEncryption(AuthMode.NONE)
            dao.insertAll(
                listOf(
                    TestData.OTP_HOTP_TOKEN_1.copy(id = 10, ordinal = 10),
                    TestData.OTP_TOTP_TOKEN_2.copy(id = 11, ordinal = 11),
                    TestData.OTP_TOTP_TOKEN_2.copy(id = 12, ordinal = 12, label = "extra_to_be_cleared")
                )
            )
            assertThat(dao.getAll().first()).hasSize(5)

            // Import encrypted JSON in NONE mode over existing 5 tokens -> clears all 5 and leaves only the 2 imported tokens
            activity.importFromUtil.importJsonFile(encJsonFile.toUri(), "backupKey1".toCharArray())
            val plainImported = dao.getAll().first()
            assertThat(plainImported).hasSize(2)
            assertThat(plainImported[0].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
            assertThat(plainImported[0].secret).isEqualTo(TestData.OTP_HOTP_TOKEN_1.secret)
            assertThat(plainImported[1].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
            assertThat(plainImported[1].secret).isEqualTo(TestData.OTP_TOTP_TOKEN_2.secret)

            // Re-importing decrypted JSON over high-ID tokens also clears and produces no duplicates
            dao.insert(TestData.OTP_HOTP_TOKEN_1.copy(id = 25, ordinal = 25))
            assertThat(dao.getAll().first()).hasSize(3)
            activity.importFromUtil.importJsonFile(decJsonFile.toUri())
            assertThat(dao.getAll().first()).hasSize(2)

            // Now enable Token encrypted mode with a DIFFERENT key ("activeKey2"), clear DB, and import encUriFile (encrypted with "backupKey1")
            dao.deleteById(1)
            dao.deleteById(2)
            val setup2 = activity.tokenCryptoManager.deriveSetupMaterial("activeKey2".toCharArray())
            activity.tokenCryptoManager.enableTokenEncryption(setup2, null)
            setup2.clear()

            activity.importFromUtil.importKeyUriFile(encUriFile.toUri(), "backupKey1".toCharArray())
            val reEncryptedImported = dao.getAll().first()
            assertThat(reEncryptedImported).hasSize(2)
            reEncryptedImported.forEach { token ->
                assertThat(token.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                assertThat(token.secret).startsWith("v1:")
            }
            assertThat(activity.tokenCryptoManager.decryptSecret(reEncryptedImported[0]))
                .isEqualTo(TestData.OTP_HOTP_TOKEN_1.secret)
            assertThat(activity.tokenCryptoManager.decryptSecret(reEncryptedImported[1]))
                .isEqualTo(TestData.OTP_TOTP_TOKEN_2.secret)
        }

        encJsonFile.delete()
        decJsonFile.delete()
        encUriFile.delete()
    }
}
