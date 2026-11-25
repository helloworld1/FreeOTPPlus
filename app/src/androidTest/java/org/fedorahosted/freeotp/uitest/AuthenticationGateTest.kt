package org.fedorahosted.freeotp.uitest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.EditText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.navigation.FreeOtpDestination
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitNodeDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.uitest.TestWaitUtil.clickCardAtPosition
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class AuthenticationGateTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var configuredSessionManager: AppSessionManager? = null
    private var configuredClipboardUtil: ClipboardUtil? = null

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @After
    fun tearDown() {
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.hasStrongBiometricOverride = null
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        configuredSessionManager?.let { manager ->
            manager.timeoutDelayMs = AppSessionManager.DEFAULT_TIMEOUT_DELAY_MS
            manager.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
            manager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
        }
        configuredSessionManager = null
        configuredClipboardUtil?.expirationDurationMs = ClipboardUtil.DEFAULT_CLIP_EXPIRATION_MS
        configuredClipboardUtil = null
        @Suppress("DEPRECATION")
        android.preference.PreferenceManager.getDefaultSharedPreferences(getInstrumentation().targetContext)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testSecretRevealWithoutScreenLockSkipsPrompt() {
        AuthenticationUtil.hasScreenLockOverride = false
        var authPromptCalled = false
        AuthenticationUtil.authCallbackOverride = { onAuthenticated ->
            authPromptCalled = true
            onAuthenticated()
        }

        val tokenId = insertTestToken()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateToEditToken(tokenId)
            }
            composeRule.awaitTagDisplayed(UiTestTags.DETAIL_SECRET)
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .performScrollTo()
                .assertTextEquals(string(R.string.click_to_display))
                .performClick()
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .assertTextEquals(TestData.OTP_TOTP_TOKEN_2.secret)
        }
        assertThat(authPromptCalled).isFalse()
    }

    @Test
    fun testSecretRevealWithScreenLockPromptsAndGatesSecret() {
        AuthenticationUtil.hasScreenLockOverride = true
        var pendingAuth: (() -> Unit)? = null
        AuthenticationUtil.authCallbackOverride = { onAuthenticated ->
            pendingAuth = onAuthenticated
        }

        val tokenId = insertTestToken()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateToEditToken(tokenId)
            }
            composeRule.awaitTagDisplayed(UiTestTags.DETAIL_SECRET)
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .performScrollTo()
                .assertTextEquals(string(R.string.click_to_display))
                .performClick()
            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .assertTextEquals(string(R.string.click_to_display))

            assertThat(pendingAuth).isNotNull()

            scenario.onActivity {
                pendingAuth?.invoke()
            }

            composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
                .assertTextEquals(TestData.OTP_TOTP_TOKEN_2.secret)
        }
    }

    @Test
    fun testShareQrCodeWithScreenLockGatesDialog() {
        AuthenticationUtil.hasScreenLockOverride = true
        var pendingAuth: (() -> Unit)? = null
        AuthenticationUtil.authCallbackOverride = { onAuthenticated ->
            pendingAuth = onAuthenticated
        }

        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.awaitTokenCardCount(1)
            composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(tokenId)).performClick()
            composeRule.onNodeWithText(string(R.string.share_qr_code)).performClick()

            assertThat(pendingAuth).isNotNull()
            composeRule.onNodeWithTag(UiTestTags.SHARE_QR_IMAGE).assertDoesNotExist()

            scenario.onActivity {
                pendingAuth?.invoke()
            }

            composeRule.awaitTagDisplayed(UiTestTags.SHARE_QR_IMAGE)
        }
    }

    @Test
    fun testExportJsonAndKeyUriWithScreenLockInvokeAuthGate() {
        AuthenticationUtil.hasScreenLockOverride = true
        var authPromptCount = 0
        AuthenticationUtil.authCallbackOverride = {
            authPromptCount++
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.export_text)).performClick()
            composeRule.onNodeWithText(string(R.string.export_json_format)).performClick()
            assertThat(authPromptCount).isEqualTo(1)

            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.export_text)).performClick()
            composeRule.onNodeWithText(string(R.string.export_key_uri_format)).performClick()
            assertThat(authPromptCount).isEqualTo(2)

            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.export_text)).performClick()
            composeRule.onNodeWithText(string(R.string.export_token_images)).performClick()
            assertThat(authPromptCount).isEqualTo(3)
        }
    }

    @Test
    fun testTokenEncryptedBackgroundTimeoutAutoLocksKeyInMemory() {
        AuthenticationUtil.hasStrongBiometricOverride = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var mainActivity: MainActivity
            scenario.onActivity { activity ->
                mainActivity = activity
                configuredSessionManager = activity.appSessionManager
                activity.appSessionManager.timeoutDelayMs = 150L
                activity.tokenCryptoManager.kdfIterationsOverride = 1_500
                runBlocking {
                    val setup = activity.tokenCryptoManager.deriveSetupMaterial("secret123".toCharArray())
                    activity.tokenCryptoManager.enableTokenEncryption(setup, null)
                    setup.clear()
                }
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            }

            assertThat(mainActivity.tokenCryptoManager.isUnlocked()).isTrue()

            scenario.moveToState(Lifecycle.State.CREATED)

            val deadline = System.currentTimeMillis() + 3_000L
            while (mainActivity.tokenCryptoManager.isUnlocked() && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }

            assertThat(mainActivity.tokenCryptoManager.isUnlocked()).isFalse()
            assertThat(mainActivity.appSessionManager.authState.value)
                .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
        }
    }

    @Test
    fun testChildScreensDismissOnTimeoutAndResumeRequiresReauthInAppLaunchMode() {
        val tokenId = insertTestToken()
        var authPromptCount = 0
        AuthenticationUtil.appLaunchAuthCallbackOverride = { _, _ ->
            authPromptCount++
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var mainActivity: MainActivity
            scenario.onActivity { activity ->
                mainActivity = activity
                configuredSessionManager = activity.appSessionManager
                activity.appSessionManager.timeoutDelayMs = 150L
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                activity.openShareQrDialog(tokenId)
            }

            composeRule.awaitTagDisplayed(UiTestTags.SHARE_QR_IMAGE)

            scenario.moveToState(Lifecycle.State.CREATED)

            val deadline = System.currentTimeMillis() + 3_000L
            while (mainActivity.appSessionManager.authState.value != MainViewModel.AuthState.UNAUTHENTICATED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }
            assertThat(mainActivity.appSessionManager.authState.value)
                .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)

            // Resume app -> ShareQrCodeDialog is dismissed and lock gate prompts
            scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertThat(activity.getOpenDialogWindows()).isEmpty()
                assertThat(activity.currentRoute()).isEqualTo(FreeOtpDestination.TOKEN_LIST)
                assertThat(authPromptCount).isGreaterThan(0)
            }
        }
    }

    @Test
    fun testLongOpenDocumentPickerDoesNotBypassSessionTimeout() {
        insertTestToken()
        val exportFile = File(getInstrumentation().targetContext.cacheDir, "timed_out_export.json")
        exportFile.delete()

        var fakeNow = 10_000L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var mainActivity: MainActivity
            scenario.onActivity { activity ->
                mainActivity = activity
                configuredSessionManager = activity.appSessionManager
                activity.appSessionManager.elapsedRealtimeProvider = { fakeNow }
                activity.tokenCryptoManager.kdfIterationsOverride = 1_500
                runBlocking {
                    val setup = activity.tokenCryptoManager.deriveSetupMaterial("secret123".toCharArray())
                    activity.tokenCryptoManager.enableTokenEncryption(setup, null)
                    setup.clear()
                }
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            }

            scenario.moveToState(Lifecycle.State.CREATED)

            fakeNow = 190_000L
            getInstrumentation().runOnMainSync {
                mainActivity.onDocumentPicked(
                    MainActivity.DocumentAction.EXPORT_JSON,
                    exportFile.toUri(),
                )
            }

            assertThat(mainActivity.tokenCryptoManager.isUnlocked()).isFalse()
            assertThat(mainActivity.appSessionManager.authState.value)
                .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
            assertThat(exportFile.exists()).isFalse()
        }
        exportFile.delete()
    }

    @Test
    fun testSelectingAppLaunchWithoutScreenLockShowsHelpfulDialogAndKeepsNoneMode() {
        AuthenticationUtil.hasScreenLockOverride = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.openOverflowMenu()
            composeRule.awaitTagDisplayed(UiTestTags.OVERFLOW_MENU)

            val titles = composeRule.onNodeWithTag(UiTestTags.OVERFLOW_MENU)
                .onChildren()
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { s -> s.text } }
            val themeIdx = titles.indexOf(string(R.string.theme))
            val authIdx = titles.indexOf(string(R.string.authentication_mode))
            assertThat(themeIdx).isAtLeast(0)
            assertThat(authIdx).isEqualTo(themeIdx + 1)

            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_app_launch)).performClick()

            composeRule.awaitTextDisplayed(string(R.string.screen_lock_required_title))
            composeRule.onNodeWithText(string(R.string.open_settings_button)).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()

            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
            }

            // Reopen Authentication mode submenu -> "None" remains selected and "App launch" unselected
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.awaitNodeDisplayed(authRadioMatcher(R.string.auth_mode_none))
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_none)).assertIsSelected()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_app_launch)).assertIsNotSelected()
        }
    }

    @Test
    fun testAppLaunchWithRemovedScreenLockBlocksExportAndOffersTurnOffRecovery() {
        insertTestToken()
        val exportFile = File(getInstrumentation().targetContext.cacheDir, "blocked_unauth_export.json")
        exportFile.delete()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.lockSession()
            }
        }

        AuthenticationUtil.hasScreenLockOverride = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.awaitTextDisplayed(string(R.string.screen_lock_disabled_title))
            composeRule.onNodeWithText(string(R.string.set_up_screen_lock_button)).assertIsDisplayed()

            scenario.onActivity { activity ->
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
                activity.handleExportAction(isJson = true)
            }
            assertThat(exportFile.exists()).isFalse()

            // Tapping "Turn off" cleanly disables APP_LAUNCH and unlocks tokens
            composeRule.onNodeWithTag(UiTestTags.DIALOG_NEUTRAL_BUTTON).performClick()

            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.AUTHENTICATED)
            }
            composeRule.awaitTokenCardCount(1)
        }
    }

    @Test
    fun testAppLaunchCancelShowsRetryDialogAndUnlockRetriesPrompt() {
        insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.lockSession()
            }
        }

        AuthenticationUtil.hasScreenLockOverride = true
        var pendingCancel: (() -> Unit)? = null
        var pendingSuccess: (() -> Unit)? = null
        var promptCount = 0

        AuthenticationUtil.appLaunchAuthCallbackOverride = { onSuccess, onCancel ->
            promptCount++
            pendingSuccess = onSuccess
            pendingCancel = onCancel
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertThat(promptCount).isEqualTo(1)

            scenario.onActivity {
                pendingCancel?.invoke()
            }

            composeRule.awaitTextDisplayed(string(R.string.authentication_dialog_title))
            scenario.onActivity { activity ->
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
            }

            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertThat(promptCount).isEqualTo(2)

            scenario.onActivity {
                pendingSuccess?.invoke()
            }

            composeRule.awaitTokenCardCount(1)
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.NONE
            }
        }
    }

    @Test
    fun testSwitchingFromNoneToAppLaunchPromptsOnceAndHandlesBackCancelCleanly() {
        insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.NONE
                activity.appSessionManager.lockSession()
            }
        }

        AuthenticationUtil.hasScreenLockOverride = true
        var pendingCancel: (() -> Unit)? = null
        var pendingSuccess: (() -> Unit)? = null
        var promptCount = 0

        AuthenticationUtil.appLaunchAuthCallbackOverride = { onSuccess, onCancel ->
            promptCount++
            pendingSuccess = onSuccess
            pendingCancel = onCancel
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertThat(promptCount).isEqualTo(0)
            composeRule.awaitTokenCardCount(1)

            // 1. Select App launch and press Back/Cancel on the prompt -> stays in None mode, no retry dialog
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_app_launch)).performClick()
            assertThat(promptCount).isEqualTo(1)

            scenario.onActivity {
                pendingCancel?.invoke()
            }

            assertThat(promptCount).isEqualTo(1)
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
                assertThat(activity.getOpenDialogWindows()).isEmpty()
            }
            composeRule.awaitTokenCardCount(1)

            // 2. Select App launch again and complete auth -> prompts ONLY ONCE
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_app_launch)).performClick()
            assertThat(promptCount).isEqualTo(2)

            scenario.onActivity {
                pendingSuccess?.invoke()
            }

            assertThat(promptCount).isEqualTo(2)
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.APP_LAUNCH)
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.AUTHENTICATED)
                assertThat(activity.getOpenDialogWindows()).isEmpty()
            }
            composeRule.awaitTokenCardCount(1)

            // 3. Switch back from App launch -> None -> prompts once and returns cleanly to None
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNode(authRadioMatcher(R.string.auth_mode_none)).performClick()
            assertThat(promptCount).isEqualTo(3)

            scenario.onActivity {
                pendingSuccess?.invoke()
            }

            assertThat(promptCount).isEqualTo(3)
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
                assertThat(activity.getOpenDialogWindows()).isEmpty()
            }
            composeRule.awaitTokenCardCount(1)
        }
    }

    @Test
    fun testAppLaunchCancelWhileStoppedShowsRetryDialogOnResumeWithoutInfinitePromptLoop() {
        insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.lockSession()
            }
        }

        AuthenticationUtil.hasScreenLockOverride = true
        var pendingCancel: (() -> Unit)? = null
        var pendingSuccess: (() -> Unit)? = null
        var promptCount = 0

        AuthenticationUtil.appLaunchAuthCallbackOverride = { onSuccess, onCancel ->
            promptCount++
            pendingSuccess = onSuccess
            pendingCancel = onCancel
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var mainActivity: MainActivity
            scenario.onActivity { activity ->
                mainActivity = activity
            }
            assertThat(promptCount).isEqualTo(1)

            scenario.moveToState(Lifecycle.State.CREATED)
            getInstrumentation().runOnMainSync {
                pendingCancel?.invoke()
            }

            scenario.moveToState(Lifecycle.State.RESUMED)

            assertThat(promptCount).isEqualTo(1)
            composeRule.awaitTextDisplayed(string(R.string.authentication_dialog_title))
            // Fail-closed: token list is completely omitted from composition while locked
            composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST).assertDoesNotExist()
            scenario.onActivity { activity ->
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
            }

            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertThat(promptCount).isEqualTo(2)

            scenario.onActivity {
                pendingSuccess?.invoke()
            }

            composeRule.awaitTokenCardCount(1)
            assertThat(mainActivity.appSessionManager.authState.value)
                .isEqualTo(MainViewModel.AuthState.AUTHENTICATED)

            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.NONE
            }
        }
    }

    @Test
    fun testQuitAndLockInAppLaunchAndTokenEncryptedModesExitsWithoutPrompting() {
        insertTestToken()
        try {
            AuthenticationUtil.hasScreenLockOverride = true
            var promptCount = 0
            AuthenticationUtil.appLaunchAuthCallbackOverride = { _, _ ->
                promptCount++
            }

            lateinit var appSessionRef: AppSessionManager
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    appSessionRef = activity.appSessionManager
                    activity.appSessionManager.prepareAuthenticatedSession()
                    activity.settings.authMode = AuthMode.APP_LAUNCH
                    activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                }
                composeRule.awaitTagDisplayed(UiTestTags.QUIT_AND_LOCK_BUTTON)
                assertThat(promptCount).isEqualTo(0)

                composeRule.onNodeWithTag(UiTestTags.QUIT_AND_LOCK_BUTTON).performClick()

                val deadline = System.currentTimeMillis() + 3_000L
                while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25L)
                }
                assertThat(promptCount).isEqualTo(0)
                assertThat(appSessionRef.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
                assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
            }

            AuthenticationUtil.appLaunchAuthCallbackOverride = null
            AuthenticationUtil.hasStrongBiometricOverride = false

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var mainActivity: MainActivity
                scenario.onActivity { activity ->
                    mainActivity = activity
                    activity.settings.authMode = AuthMode.NONE
                    activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                    activity.tokenCryptoManager.kdfIterationsOverride = 1_500
                    runBlocking {
                        val setup = activity.tokenCryptoManager.deriveSetupMaterial("secret123".toCharArray())
                        activity.appSessionManager.prepareAuthenticatedSession()
                        activity.tokenCryptoManager.enableTokenEncryption(setup, null)
                        setup.clear()
                    }
                    activity.settings.refreshFlows()
                    activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                }
                composeRule.awaitTagDisplayed(UiTestTags.QUIT_AND_LOCK_BUTTON)
                assertThat(mainActivity.tokenCryptoManager.isUnlocked()).isTrue()

                composeRule.onNodeWithTag(UiTestTags.QUIT_AND_LOCK_BUTTON).performClick()

                val deadline = System.currentTimeMillis() + 3_000L
                while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25L)
                }
                assertThat(mainActivity.tokenCryptoManager.isUnlocked()).isFalse()
                assertThat(mainActivity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
                assertThat(scenario.state).isEqualTo(Lifecycle.State.DESTROYED)
            }
        } finally {
            AuthenticationUtil.appLaunchAuthCallbackOverride = null
            AuthenticationUtil.hasScreenLockOverride = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    runBlocking {
                        if (activity.tokenCryptoManager.getAuthMode() == AuthMode.TOKEN_ENCRYPTED) {
                            activity.tokenCryptoManager.unlockWithPassphrase("secret123".toCharArray())
                            activity.tokenCryptoManager.disableTokenEncryption(AuthMode.NONE)
                        } else {
                            activity.settings.authMode = AuthMode.NONE
                        }
                    }
                    activity.settings.refreshFlows()
                    activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                }
            }
        }
    }

    @Test
    fun testShareQrCodeCopyTokenUriButtonCopiesExactSensitiveUriToClipboard() {
        AuthenticationUtil.hasScreenLockOverride = false
        val tokenId = insertTestToken()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.awaitTokenCardCount(1)
            composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(tokenId)).performClick()
            composeRule.onNodeWithText(string(R.string.share_qr_code)).performClick()

            composeRule.awaitTagDisplayed(UiTestTags.COPY_URI_BUTTON)
            composeRule.waitUntil(5_000L) {
                runCatching {
                    composeRule.onNodeWithTag(UiTestTags.COPY_URI_BUTTON).assertIsEnabled()
                    true
                }.getOrDefault(false)
            }
            composeRule.onNodeWithTag(UiTestTags.COPY_URI_BUTTON)
                .performScrollTo()
                .performClick()

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                composeRule.awaitTextDisplayed(string(R.string.token_uri_copied))
            } else {
                composeRule.onNodeWithText(string(R.string.token_uri_copied)).assertDoesNotExist()
            }

            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                assertThat(clip).isNotNull()
                val copiedText = clip!!.getItemAt(0).text.toString()
                assertThat(copiedText).isEqualTo(OtpTokenFactory.toUri(TestData.OTP_TOTP_TOKEN_2).toString())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    assertThat(clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE"))
                        .isTrue()
                }
            }
        }
    }

    @Test
    fun testSensitiveOtpClipboardMarksSensitiveAutoExpiresPreservesExternalClipsAndClearsOnLock() {
        AuthenticationUtil.hasScreenLockOverride = false
        insertTestToken()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.copyToClipboard = true
                configuredClipboardUtil = activity.clipboardUtil
                activity.clipboardUtil.expirationDurationMs = 1_200L

                val editText = EditText(activity).apply { setText("secretPass!42") }
                assertThat(AuthenticationUtil.extractCharArray(editText))
                    .isEqualTo("secretPass!42".toCharArray())
                AuthenticationUtil.clearAndCancelAutofill(activity, editText)
                assertThat(editText.text.toString()).isEmpty()
            }

            composeRule.awaitTokenCardCount(1)
            composeRule.clickCardAtPosition(0)

            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                composeRule.awaitTextDisplayed(string(R.string.code_copied))
            } else {
                composeRule.onNodeWithText(string(R.string.code_copied)).assertDoesNotExist()
            }

            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                assertThat(clip).isNotNull()
                assertThat(clip!!.getItemAt(0).text.toString()).hasLength(6)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    assertThat(clip.description.extras?.getBoolean(ClipboardUtil.EXTRA_IS_SENSITIVE_KEY))
                        .isTrue()
                }
            }

            Thread.sleep(1_500L)
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val remaining = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                    .orEmpty()
                assertThat(remaining).isEmpty()
            }

            scenario.onActivity { activity ->
                activity.clipboardUtil.copySensitiveText("otp", "123456", ttlMs = 400L)
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("external", "https://external.example"))
            }
            Thread.sleep(650L)
            scenario.onActivity { activity ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                assertThat(text).isEqualTo("https://external.example")
            }

            scenario.onActivity { activity ->
                activity.clipboardUtil.copySensitiveText("otp", "654321", ttlMs = 60_000L)
                activity.appSessionManager.lockSession()
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val remaining = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
                    .orEmpty()
                assertThat(remaining).isEmpty()
            }
        }
    }

    private fun authRadioMatcher(titleRes: Int): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasText(string(titleRes))
    }

    private fun insertTestToken(): Long {
        var tokenId = 1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.tokenCryptoManager.lock()
                activity.settings.authMode = AuthMode.NONE
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                    activity.otpTokenDatabase.otpTokenDao()
                        .insert(TestData.OTP_TOTP_TOKEN_2.copy(id = 1, ordinal = 1))
                    tokenId = 1L
                }
            }
        }
        return tokenId
    }
}
