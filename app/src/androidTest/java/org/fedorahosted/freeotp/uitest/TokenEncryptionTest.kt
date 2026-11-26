package org.fedorahosted.freeotp.uitest

import android.content.Intent
import android.preference.PreferenceManager
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.IdlingResource
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.android.material.R as MaterialR
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitNodeDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.uitest.TestWaitUtil.clickCardAtPosition
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.Settings
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@RunWith(AndroidJUnit4ClassRunner::class)
class TokenEncryptionTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

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

    @Volatile
    private var boundCryptoManager: TokenCryptoManager? = null

    private val cryptoComposeIdlingResource = object : IdlingResource {
        override val isIdleNow: Boolean
            get() = (boundCryptoManager?.activeAsyncOperations?.get() ?: 0) == 0
    }

    private fun attachCryptoIdlingResource(manager: TokenCryptoManager) {
        boundCryptoManager = manager
        manager.kdfIterationsOverride = 2_000
    }

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @Before
    fun setUp() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AuthenticationUtil.hasScreenLockOverride = false
        AuthenticationUtil.hasStrongBiometricOverride = false
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        AuthenticationUtil.biometricCryptoCallbackOverride = null
        composeRule.registerIdlingResource(cryptoComposeIdlingResource)
    }

    @After
    fun tearDown() {
        composeRule.unregisterIdlingResource(cryptoComposeIdlingResource)
        boundCryptoManager?.onAsyncOperationIdle = null
        boundCryptoManager?.kdfIterationsOverride = null
        boundCryptoManager?.keyStoreBackend = TokenCryptoManager.AndroidKeyStoreBackend()
        boundCryptoManager?.lock()
        boundCryptoManager = null
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.hasStrongBiometricOverride = null
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        AuthenticationUtil.biometricCryptoCallbackOverride = null
    }

    @Test
    fun testBackwardCompatibleSettingsUpgradeFromLegacyBoolean() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
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

            // Open Authentication mode submenu and choose "Token encrypted"
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()

            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)

            // Test key < 6 chars rejected
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("12345")
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("12345")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).assertIsDisplayed()

            // Test mismatched confirm key rejected
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("secret123")
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("secret999")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).assertIsDisplayed()

            // Enter valid matching 6+ char key
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("secret123")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()

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
            composeRule.clickCardAtPosition(0)
            composeRule.assertCardAtPositionHasText(0, "047 701")

            // Add a new token while in Token encrypted mode and verify it is stored encrypted too
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.add_token)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
            composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("gitlab.com")
            composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("bob")
            composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT).performTextReplacement("JBSWY3DPEHPK3PXP")
            composeRule.onNodeWithTag(UiTestTags.ADD_SUBMIT_BUTTON).performClick()

            composeRule.awaitTokenCardCount(2)
            val storedAfterAdd = TestWaitUtil.awaitStoredTokens(scenario, expectedCount = 2)
            storedAfterAdd.forEach { token ->
                assertThat(token.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                assertThat(token.secret).startsWith("v1:")
            }

            scenario.onActivity { activity ->
                activity.navigateToEditToken(1L)
            }

            // Verify EditTokenScreen secret reveal requires key verification when no screen lock is set and reveals plaintext
            composeRule.awaitTagDisplayed(UiTestTags.DETAIL_SECRET)
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .performScrollTo()
                .assertTextEquals(string(R.string.click_to_display))
                .performClick()

            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("secret123")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitUntil(5_000L) {
                runCatching {
                    composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                        .assertTextEquals(TestData.OTP_HOTP_TOKEN_1.secret)
                    true
                }.getOrDefault(false)
            }
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

            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
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
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
            }
            composeRule.awaitTagDisplayed(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX)
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOn()

            // Wrong key stays in dialog
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("wrongKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).assertIsDisplayed()

            // Right key unlocks and wraps key with biometric
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            assertThat(bioPromptCount).isEqualTo(1)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isTrue()
                activity.tokenCryptoManager.lock()
            }
        }

        // 3. Relaunch with valid biometric -> unlocks directly via biometric without manual key dialog
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertThat(bioPromptCount).isEqualTo(2)
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")

            fakeBackend.invalidated = true
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.lock()
            }
        }

        // 4. Relaunch after biometric invalidation -> prompts manual key + "Re-enable biometric unlock"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
            }
            composeRule.awaitTextDisplayed(string(R.string.biometric_invalidated_enter_key_message))
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX)
                .assertIsDisplayed()
                .assert(hasText(string(R.string.reenable_biometric_unlock)))

            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            assertThat(bioPromptCount).isEqualTo(3)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isTrue()
                assertThat(activity.tokenCryptoManager.wasBiometricInvalidated()).isFalse()
            }
            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "github.com")
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

            composeRule.awaitTokenCardCount(2)
            composeRule.onNodeWithTag(UiTestTags.QUIT_AND_LOCK_BUTTON).assertDoesNotExist()

            // Enable Token encrypted mode
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("rotateKey1")
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("rotateKey1")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.awaitTagDisplayed(UiTestTags.QUIT_AND_LOCK_BUTTON)

            // Switch back to "None" -> verify "Token encrypted" radio is selected -> select "None" -> confirm warning -> verify key
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.awaitNodeDisplayed(authRadioMatcher(R.string.auth_mode_token_encrypted))
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).assertIsSelected()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_none)).assertIsNotSelected()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_none)).performClick()

            composeRule.awaitTextDisplayed(string(R.string.disable_token_encryption_warning))
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("rotateKey1")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            composeRule.onNodeWithTag(UiTestTags.QUIT_AND_LOCK_BUTTON).assertDoesNotExist()
            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
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

            val setup1 = activity.tokenCryptoManager.deriveSetupMaterial("backupKey1".toCharArray())
            activity.tokenCryptoManager.enableTokenEncryption(setup1, null)
            setup1.clear()

            activity.importFromUtil.exportJsonFile(encJsonFile.toUri(), encrypt = true)
            activity.importFromUtil.exportJsonFile(decJsonFile.toUri(), encrypt = false)
            activity.importFromUtil.exportKeyUriFile(encUriFile.toUri(), encrypt = true)

            assertThat(activity.importFromUtil.isJsonFileEncrypted(encJsonFile.toUri())).isTrue()
            assertThat(activity.importFromUtil.isJsonFileEncrypted(decJsonFile.toUri())).isFalse()
            assertThat(activity.importFromUtil.isKeyUriFileEncrypted(encUriFile.toUri())).isTrue()

            activity.tokenCryptoManager.disableTokenEncryption(AuthMode.NONE)
            dao.insertAll(
                listOf(
                    TestData.OTP_HOTP_TOKEN_1.copy(id = 10, ordinal = 10),
                    TestData.OTP_TOTP_TOKEN_2.copy(id = 11, ordinal = 11),
                    TestData.OTP_TOTP_TOKEN_2.copy(id = 12, ordinal = 12, label = "extra_to_be_cleared"),
                )
            )
            assertThat(dao.getAll().first()).hasSize(5)

            activity.importFromUtil.importJsonFile(encJsonFile.toUri(), "backupKey1".toCharArray())
            val plainImported = dao.getAll().first()
            assertThat(plainImported).hasSize(2)
            assertThat(plainImported[0].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
            assertThat(plainImported[0].secret).isEqualTo(TestData.OTP_HOTP_TOKEN_1.secret)
            assertThat(plainImported[1].encryptionType).isEqualTo(EncryptionType.PLAIN_TEXT)
            assertThat(plainImported[1].secret).isEqualTo(TestData.OTP_TOTP_TOKEN_2.secret)

            dao.insert(TestData.OTP_HOTP_TOKEN_1.copy(id = 25, ordinal = 25))
            assertThat(dao.getAll().first()).hasSize(3)
            activity.importFromUtil.importJsonFile(decJsonFile.toUri())
            assertThat(dao.getAll().first()).hasSize(2)

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
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        AuthenticationUtil.hasStrongBiometricOverride = false

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
                            setup.keyBytes,
                        ),
                        encryptionType = EncryptionType.ENCRYPTED,
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

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                assertThat(activity.tokenCryptoManager.getAuthMode()).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
                assertThat(prefs.contains(TokenCryptoManager.PENDING_KDF_SALT_KEY)).isFalse()
                assertThat(prefs.contains(TokenCryptoManager.KDF_SALT_KEY)).isTrue()
            }

            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("recoverKey99")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.awaitTokenCardCount(1)
            composeRule.clickCardAtPosition(0)
            composeRule.assertCardAtPositionHasText(0, "047 701")
        }
    }

    @Test
    fun testCorruptedEncryptedTokenAndConcurrentVaultLockNeverCrashAcrossUiPaths() {
        val context = getInstrumentation().targetContext
        AuthenticationUtil.hasStrongBiometricOverride = false
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
                            encryptionType = EncryptionType.ENCRYPTED,
                        )
                    )
                }
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            }

            composeRule.awaitTokenCardCount(1)

            // 1. Tap corrupted encrypted HOTP card -> shows invalid_token Snackbar without crashing or advancing counter
            composeRule.clickCardAtPosition(0)
            composeRule.awaitTextDisplayed(string(R.string.invalid_token))
            scenario.onActivity { activity ->
                val stored = runBlocking { activity.otpTokenDatabase.otpTokenDao().get(corruptedTokenId).first()!! }
                assertThat(stored.counter).isEqualTo(0L)
                activity.dismissSnackbar()
            }

            // 2. Card menu -> Share QR Code -> shows invalid_token Snackbar and dismisses dialog without crashing
            composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(corruptedTokenId)).performClick()
            composeRule.onNodeWithText(string(R.string.share_qr_code)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.invalid_token))
            scenario.onActivity { it.dismissSnackbar() }

            // 3. Card menu -> Share to other apps -> shows invalid_token Snackbar without crashing
            composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(corruptedTokenId)).performClick()
            composeRule.onNodeWithText(string(R.string.share_to_other_apps)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.invalid_token))
            scenario.onActivity { it.dismissSnackbar() }

            // 4. EditTokenScreen -> Click to display secret -> shows invalid_token and keeps "Click to display"
            scenario.onActivity { activity ->
                activity.navigateToEditToken(corruptedTokenId)
            }
            composeRule.awaitTagDisplayed(UiTestTags.DETAIL_SECRET)
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .performScrollTo()
                .assertTextEquals(string(R.string.click_to_display))
                .performClick()
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .assertTextEquals(string(R.string.click_to_display))

            scenario.onActivity { activity ->
                activity.navigateBack()
            }
            composeRule.awaitTokenCardCount(1)

            // 5. Concurrent vault lock right before tapping a token card -> transitions to Unlock dialog cleanly
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.lock()
            }
            composeRule.clickCardAtPosition(0)
            composeRule.awaitTextDisplayed(string(R.string.unlock_tokens_title))

            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("f12TestKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.awaitTokenCardCount(1)
        }

        // 6. Deep-link otpauth:// add confirmation when vault locks concurrently before tapping Add ->
        //    re-queues deep link, prompts Unlock, and adds token once unlocked without crashing
        val deepLinkIntent = Intent(
            Intent.ACTION_VIEW,
            "otpauth://totp/DeepLink:alice?secret=JBSWY3DPEHPK3PXP".toUri(),
        ).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(deepLinkIntent).use { deepLinkScenario ->
            composeRule.awaitTagDisplayed(UiTestTags.DIALOG_POSITIVE_BUTTON)
            deepLinkScenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.lock()
            }
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            // Unlock dialog appears instead of crashing
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("f12TestKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            // Re-queued deep-link confirmation dialog is re-presented and succeeds after unlock
            composeRule.awaitTextDisplayed("DeepLink", substring = true)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            val allTokens = TestWaitUtil.awaitStoredTokens(deepLinkScenario, expectedCount = 2)
            assertThat(allTokens.any { it.issuer == "DeepLink" && it.encryptionType == EncryptionType.ENCRYPTED })
                .isTrue()
        }
    }

    @Test
    fun testActivityRecreationMidEncryptionTransitionPreservesTokens() {
        AuthenticationUtil.hasStrongBiometricOverride = false
        val enteredEnableTx = CountDownLatch(1)
        val proceedEnableTx = CountDownLatch(1)
        lateinit var cryptoManager: TokenCryptoManager
        lateinit var defaultRunner: suspend (suspend () -> Unit) -> Unit

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                cryptoManager = activity.tokenCryptoManager
                cryptoManager.kdfIterationsOverride = 1_500
                defaultRunner = cryptoManager.transactionRunner
                cryptoManager.transactionRunner = { block ->
                    enteredEnableTx.countDown()
                    assertThat(proceedEnableTx.await(5, TimeUnit.SECONDS)).isTrue()
                    defaultRunner(block)
                }
                activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        proceedEnableTx.countDown()
                    }
                })
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(
                        TestData.OTP_HOTP_TOKEN_1.copy(id = 1L, ordinal = 1L, counter = 0L)
                    )
                }
            }

            try {
                composeRule.openOverflowMenu()
                composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
                composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()

                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("rotateSecret99")
                composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("rotateSecret99")
                composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

                assertThat(enteredEnableTx.await(5, TimeUnit.SECONDS)).isTrue()
                scenario.recreate()

                val deadline = System.currentTimeMillis() + 5_000L
                while (cryptoManager.activeAsyncOperations.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                }
            } finally {
                proceedEnableTx.countDown()
                cryptoManager.transactionRunner = defaultRunner
            }

            scenario.onActivity { recreatedActivity ->
                attachCryptoIdlingResource(recreatedActivity.tokenCryptoManager)
                assertThat(recreatedActivity.settings.authMode).isEqualTo(AuthMode.TOKEN_ENCRYPTED)
                assertThat(recreatedActivity.tokenCryptoManager.isUnlocked()).isTrue()
                val stored = runBlocking { recreatedActivity.otpTokenDatabase.otpTokenDao().getAll().first() }
                assertThat(stored).hasSize(1)
                assertThat(stored[0].encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                assertThat(stored[0].secret).startsWith("v1:")
            }

            // Verify the recreated Activity can immediately generate the encrypted HOTP code
            composeRule.awaitTokenCardCount(1)
            composeRule.clickCardAtPosition(0)
            composeRule.assertCardAtPositionHasText(0, "047 701")

            // Lock and verify unlocking with the passphrase succeeds (KDF salt was never rolled back)
            scenario.onActivity { recreatedActivity ->
                recreatedActivity.appSessionManager.lockSession()
            }
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("rotateSecret99")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            scenario.onActivity { recreatedActivity ->
                assertThat(recreatedActivity.tokenCryptoManager.isUnlocked()).isTrue()
            }
        }
    }

    @Test
    fun testEncryptionKeyAndBackupPasswordFieldsHiddenByDefault() {
        val context = getInstrumentation().targetContext
        val encryptedBackupFile = File(context.cacheDir, "masking_check_encrypted.json")

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    attachCryptoIdlingResource(activity.tokenCryptoManager)
                    runBlocking(Dispatchers.IO) {
                        activity.otpTokenDatabase.clearAllTables()
                        activity.otpTokenDatabase.otpTokenDao().insert(
                            TestData.OTP_TOTP_TOKEN_2.copy(id = 1L, ordinal = 1L)
                        )
                    }
                }

                // 1. SetEncryptionKeyDialog: both key and confirm fields start hidden by default
                composeRule.openOverflowMenu()
                composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
                composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()

                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("HiddenPassphrase123")
                composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("HiddenPassphrase123")
                composeRule.waitForIdle()

                scenario.onActivity { activity ->
                    val layouts = findDialogPasswordLayouts(activity)
                    assertThat(layouts).hasSize(2)
                    for (layout in layouts) {
                        val editText = layout.editText as TextInputEditText
                        val endIcon = layout.findViewById<View>(MaterialR.id.text_input_end_icon)
                        assertThat(editText.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)
                        assertThat((endIcon as Checkable).isChecked).isFalse()
                    }

                    // Toggle visibility ON on the first field, then cancel dialog
                    val firstEndIcon = layouts[0].findViewById<View>(MaterialR.id.text_input_end_icon)
                    firstEndIcon.performClick()
                    assertThat(layouts[0].editText?.transformationMethod).isNull()
                    assertThat((firstEndIcon as Checkable).isChecked).isTrue()
                }

                composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()
                composeRule.waitForIdle()

                // Reopen SetEncryptionKeyDialog: both fields must start hidden again by default
                composeRule.openOverflowMenu()
                composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
                composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()

                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                scenario.onActivity { activity ->
                    val layouts = findDialogPasswordLayouts(activity)
                    assertThat(layouts).hasSize(2)
                    for (layout in layouts) {
                        val editText = layout.editText as TextInputEditText
                        val endIcon = layout.findViewById<View>(MaterialR.id.text_input_end_icon)
                        assertThat(editText.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)
                        assertThat((endIcon as Checkable).isChecked).isFalse()
                    }
                }

                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("HiddenPassphrase123")
                composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("HiddenPassphrase123")
                composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
                composeRule.waitForIdle()

                // Export encrypted backup while unlocked, then lock session
                scenario.onActivity { activity ->
                    runBlocking(Dispatchers.IO) {
                        activity.importFromUtil.exportJsonFile(encryptedBackupFile.toUri(), encrypt = true)
                    }
                    activity.appSessionManager.lockSession()
                }

                // 2. ManualKeyPromptDialog (unlock prompt): encryption key field starts hidden by default
                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                scenario.onActivity { activity ->
                    val layouts = findDialogPasswordLayouts(activity)
                    assertThat(layouts).hasSize(1)
                    val editText = layouts[0].editText as TextInputEditText
                    val endIcon = layouts[0].findViewById<View>(MaterialR.id.text_input_end_icon)
                    assertThat(editText.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)
                    assertThat((endIcon as Checkable).isChecked).isFalse()
                }

                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("HiddenPassphrase123")
                composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
                composeRule.waitForIdle()

                // 3. BackupImportDialog (encrypted backup password prompt): field starts hidden by default
                scenario.onActivity { activity ->
                    activity.onDocumentPicked(
                        MainActivity.DocumentAction.IMPORT_JSON,
                        encryptedBackupFile.toUri(),
                    )
                }
                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                scenario.onActivity { activity ->
                    val layouts = findDialogPasswordLayouts(activity)
                    assertThat(layouts).hasSize(1)
                    val editText = layouts[0].editText as TextInputEditText
                    val endIcon = layouts[0].findViewById<View>(MaterialR.id.text_input_end_icon)
                    assertThat(editText.transformationMethod).isInstanceOf(PasswordTransformationMethod::class.java)
                    assertThat((endIcon as Checkable).isChecked).isFalse()
                }
                composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()
            }
        } finally {
            encryptedBackupFile.delete()
        }
    }

    @Test
    fun testEnableBiometricUnlockOptionRememberedWhenUnselectedAndReselected() {
        val fakeBackend = FakeKeyStoreBackend()
        AuthenticationUtil.hasStrongBiometricOverride = false

        // 1. Set up Token encrypted mode with manual key only
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
                runBlocking(Dispatchers.IO) {
                    activity.otpTokenDatabase.clearAllTables()
                    activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_HOTP_TOKEN_1)
                }
            }

            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_token_encrypted)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isFalse()
                assertThat(activity.settings.enableBiometricUnlock).isTrue()
                activity.tokenCryptoManager.lock()
            }
        }

        // 2. Strong biometric becomes available: checkbox starts ON by default; user unselects it and unlocks
        AuthenticationUtil.hasStrongBiometricOverride = true
        var bioPromptCount = 0
        AuthenticationUtil.biometricCryptoCallbackOverride = { cipher, onSuccess, _, _ ->
            bioPromptCount++
            onSuccess(cipher)
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
            }
            composeRule.awaitTagDisplayed(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX)
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOn()
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).performClick()
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOff()

            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            assertThat(bioPromptCount).isEqualTo(0)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isFalse()
                assertThat(activity.settings.enableBiometricUnlock).isFalse()
                activity.tokenCryptoManager.lock()
            }
        }

        // 3. Next launch: "Enable biometric unlock" must start UNSELECTED by default and stay unselected
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
            }
            composeRule.awaitTagDisplayed(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX)
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOff()

            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            assertThat(bioPromptCount).isEqualTo(0)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isFalse()
                assertThat(activity.settings.enableBiometricUnlock).isFalse()
                activity.tokenCryptoManager.lock()
            }
        }

        // 4. Subsequent launch: still unselected by default; user re-selects it and unlocks -> enables biometric
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                attachCryptoIdlingResource(activity.tokenCryptoManager)
                activity.tokenCryptoManager.keyStoreBackend = fakeBackend
            }
            composeRule.awaitTagDisplayed(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX)
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOff()
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).performClick()
            composeRule.onNodeWithTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX).assertIsOn()

            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("myMasterKey")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.waitForIdle()
            assertThat(bioPromptCount).isEqualTo(1)
            scenario.onActivity { activity ->
                assertThat(activity.tokenCryptoManager.isBiometricKeyConfigured()).isTrue()
                assertThat(activity.settings.enableBiometricUnlock).isTrue()
            }
        }
    }

    private fun findDialogPasswordLayouts(activity: MainActivity): List<TextInputLayout> {
        val result = mutableListOf<TextInputLayout>()
        fun collect(view: View) {
            if (view is TextInputLayout) {
                result += view
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    collect(view.getChildAt(i))
                }
            }
        }
        for (window in activity.dialogWindowRegistry.snapshot()) {
            collect(window.decorView)
        }
        return result
    }

    private fun authRadioMatcher(titleRes: Int): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasText(string(titleRes))
    }
}
