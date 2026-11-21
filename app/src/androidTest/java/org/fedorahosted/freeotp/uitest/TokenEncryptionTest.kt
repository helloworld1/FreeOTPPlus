package org.fedorahosted.freeotp.uitest

import android.content.Intent
import android.preference.PreferenceManager
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.widget.RadioButton
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.core.net.toUri
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
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
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
import org.hamcrest.Matchers.allOf
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

            // Open Authentication mode popup selector and choose "Token encrypted"
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_token_encrypted)).perform(click())

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

            // Tap HOTP token card and verify exact RFC 4226 counter=0 OTP code generation ("047 701")
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                .check(matches(hasDescendant(withText("047 701"))))

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
                    EditTokenFragment.args(1L)
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
            onView(withText(R.string.auth_mode_token_encrypted)).perform(click())
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
            onView(withText(R.string.auth_mode_token_encrypted)).perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("rotateKey1"), closeSoftKeyboard())
            onView(withId(R.id.confirm_key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("rotateKey1"), closeSoftKeyboard())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
                assertThat(toolbar.menu.findItem(R.id.quit_and_lock).isVisible).isTrue()
            }

            // Switch back to "None" -> verify "Token encrypted" radio is currently checked -> select "None" -> verify warning dialog -> verify key -> secrets restored to PLAIN_TEXT
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(
                allOf(
                    isAssignableFrom(ListMenuItemView::class.java),
                    hasDescendant(withText(R.string.auth_mode_token_encrypted))
                )
            ).check(matches(hasDescendant(allOf(isAssignableFrom(RadioButton::class.java), isChecked()))))
            onView(
                allOf(
                    isAssignableFrom(ListMenuItemView::class.java),
                    hasDescendant(withText(R.string.auth_mode_none))
                )
            ).check(matches(hasDescendant(allOf(isAssignableFrom(RadioButton::class.java), isNotChecked()))))
            onView(withText(R.string.auth_mode_none)).perform(click())

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

    @Test
    fun testInterruptedEnableEncryptionAfterSqliteCommitRecoversOnNextLaunch() {
        val context = getInstrumentation().targetContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        AuthenticationUtil.hasStrongBiometricOverride = false

        // 1. Seed token in Room and simulate a process crash right AFTER SQLite encrypted the token
        //    with a random-salt key, BEFORE active KDF_SALT_KEY / AUTH_MODE_KEY were committed (Issue F2).
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.kdfIterationsOverride = 2_000
                runBlocking(Dispatchers.IO) {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    activity.otpTokenDatabase.clearAllTables()
                    dao.insert(TestData.OTP_HOTP_TOKEN_1)

                    val setup = activity.tokenCryptoManager.deriveSetupMaterial("recoverKey99".toCharArray())
                    val storedPlain = dao.getAll().first().single()
                    val encryptedToken = storedPlain.copy(
                        secret = org.fedorahosted.freeotp.data.util.CryptoEngine.encryptSecret(
                            storedPlain.secret,
                            setup.keyBytes
                        ),
                        encryptionType = EncryptionType.ENCRYPTED
                    )
                    dao.update(encryptedToken)

                    prefs.edit()
                        .clear()
                        .putString(TokenCryptoManager.PENDING_KDF_SALT_KEY, setup.kdfSalt)
                        .putInt(TokenCryptoManager.PENDING_KDF_ITERATIONS_KEY, setup.kdfIterations)
                        .putString(TokenCryptoManager.PENDING_KEY_VERIFIER_KEY, setup.keyVerifier)
                        .putLong(TokenCryptoManager.PENDING_SAMPLE_TOKEN_ID_KEY, encryptedToken.id)
                        .putString(TokenCryptoManager.PENDING_SAMPLE_TOKEN_SECRET_KEY, encryptedToken.secret)
                        .commit()
                    setup.clear()
                }
                activity.tokenCryptoManager.lock()
            }
        }

        // 2. Relaunch MainActivity -> reconciles pending metadata into active TOKEN_ENCRYPTED mode,
        //    prompts for manual key, unlocks token, and generates exact OTP code "047 701"!
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                assertThat(activity.tokenCryptoManager.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
                assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
                assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isTrue()
            }

            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("recoverKey99"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())

            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                .check(matches(hasDescendant(withText("047 701"))))
        }
    }

    @Test
    fun testCorruptedEncryptedTokenAndConcurrentVaultLockNeverCrashAcrossUiPaths() {
        val context = getInstrumentation().targetContext
        AuthenticationUtil.hasStrongBiometricOverride = false
        // Bypass screen-lock prompt during sensitive actions (Share / Share QR / Secret reveal)
        AuthenticationUtil.authCallbackOverride = { onAuthenticated -> onAuthenticated() }

        val corruptedTokenId = 1L

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                runBlocking(Dispatchers.IO) {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    activity.otpTokenDatabase.clearAllTables()
                    val setup = activity.tokenCryptoManager.deriveSetupMaterial("f12TestKey".toCharArray())
                    try {
                        activity.tokenCryptoManager.enableTokenEncryption(setup, null)
                    } finally {
                        setup.clear()
                    }
                    dao.insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(
                            id = corruptedTokenId,
                            ordinal = 1L,
                            counter = 0L,
                            secret = "v1:AAAA_CORRUPTED_CIPHERTEXT",
                            encryptionType = EncryptionType.ENCRYPTED
                        )
                    )
                }
                activity.appSessionManager.setAuthState(org.fedorahosted.freeotp.ui.MainViewModel.AuthState.AUTHENTICATED)
            }

            // 1. Tap corrupted encrypted HOTP card -> shows invalid_token Snackbar without crashing or advancing counter
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            onView(withText(R.string.invalid_token)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val stored = runBlocking { activity.otpTokenDatabase.otpTokenDao().get(corruptedTokenId).first()!! }
                assertThat(stored.counter).isEqualTo(0L)
            }

            // 2. Overflow -> Share QR Code -> shows invalid_token Snackbar and dismisses dialog without crashing
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, RecyclerViewAction.clickChildViewWithId(R.id.menu)))
            onView(withText(R.string.share_qr_code)).inRoot(isPlatformPopup()).perform(click())
            onView(withText(R.string.invalid_token)).check(matches(isDisplayed()))

            // 3. Overflow -> Share to other apps -> shows invalid_token Snackbar without crashing
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, RecyclerViewAction.clickChildViewWithId(R.id.menu)))
            onView(withText(R.string.share_to_other_apps)).inRoot(isPlatformPopup()).perform(click())
            onView(withText(R.string.invalid_token)).check(matches(isDisplayed()))

            // 4. EditTokenFragment -> Click to display secret -> shows invalid_token and keeps "Click to display"
            scenario.onActivity { activity ->
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(corruptedTokenId)
                )
            }
            onView(withId(R.id.secret))
                .perform(scrollTo())
                .check(matches(withText(R.string.click_to_display)))
                .perform(click())
            onView(withText(R.string.invalid_token)).check(matches(isDisplayed()))
            onView(withId(R.id.secret)).check(matches(withText(R.string.click_to_display)))

            scenario.onActivity { activity ->
                activity.navigateBack()
            }

            // 5. Concurrent vault lock right before tapping a token card -> transitions to Unlock dialog cleanly
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.lock()
            }
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
            onView(withText(R.string.unlock_tokens_title)).inRoot(isDialog()).check(matches(isDisplayed()))

            // Unlock again so vault is unlocked for deep-link confirmation test
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("f12TestKey"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())
        }

        // 6. Deep-link otpauth:// add confirmation when vault locks concurrently before tapping Add ->
        //    re-queues deep link, prompts Unlock, and adds token once unlocked without crashing
        val deepLinkIntent = Intent(
            Intent.ACTION_VIEW,
            "otpauth://totp/DeepLink:alice?secret=JBSWY3DPEHPK3PXP".toUri()
        ).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(deepLinkIntent).use { deepLinkScenario ->
            deepLinkScenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                // Simulate concurrent vault lock while confirmation dialog is open
                activity.tokenCryptoManager.lock()
            }
            onView(withText(R.string.add)).inRoot(isDialog()).perform(click())

            // Unlock dialog appears instead of crashing
            onView(withId(R.id.key_edit_text)).inRoot(isDialog())
                .perform(scrollTo(), replaceText("f12TestKey"), closeSoftKeyboard())
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())

            // Re-queued deep-link confirmation dialog is re-presented and succeeds after unlock
            onView(withText(R.string.add)).inRoot(isDialog()).perform(click())
            deepLinkScenario.onActivity { activity ->
                val allTokens = runBlocking { activity.otpTokenDatabase.otpTokenDao().getAll().first() }
                assertThat(allTokens).hasSize(2)
                assertThat(allTokens.any { it.issuer == "DeepLink" && it.encryptionType == EncryptionType.ENCRYPTED }).isTrue()
            }
        }
    }
}
