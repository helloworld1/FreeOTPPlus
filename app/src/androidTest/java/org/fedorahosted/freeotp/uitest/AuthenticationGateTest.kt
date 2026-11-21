package org.fedorahosted.freeotp.uitest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.EditTokenFragment
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.ShareQrCodeDialogFragment
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class AuthenticationGateTest {

    private var configuredSessionManager: AppSessionManager? = null
    private var configuredClipboardUtil: ClipboardUtil? = null

    @After
    fun tearDown() {
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.hasStrongBiometricOverride = null
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        configuredSessionManager?.let { manager ->
            manager.timeoutDelayMs = AppSessionManager.DEFAULT_TIMEOUT_DELAY_MS
            manager.elapsedRealtimeProvider = { SystemClock.elapsedRealtime() }
        }
        configuredSessionManager = null
        configuredClipboardUtil?.expirationDurationMs = ClipboardUtil.DEFAULT_CLIP_EXPIRATION_MS
        configuredClipboardUtil = null
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
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(tokenId)
                )
            }
            onView(withId(R.id.secret))
                .perform(scrollTo())
                .check(matches(withText(R.string.click_to_display)))
                .perform(click())
                .check(matches(withText(TestData.OTP_TOTP_TOKEN_2.secret)))
            scenario.onActivity { activity ->
                assertThat(activity.findViewById<TextView>(R.id.secret).freezesText).isFalse()
            }
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
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(tokenId)
                )
            }
            onView(withId(R.id.secret))
                .perform(scrollTo())
                .check(matches(withText(R.string.click_to_display)))
                .perform(click())
                .check(matches(withText(R.string.click_to_display)))

            assertThat(pendingAuth).isNotNull()

            scenario.onActivity {
                pendingAuth?.invoke()
            }

            onView(withId(R.id.secret))
                .check(matches(withText(TestData.OTP_TOTP_TOKEN_2.secret)))
        }
    }

    @Test
    fun testShareQrCodeWithScreenLockGatesDialog() {
        AuthenticationUtil.hasScreenLockOverride = true
        var pendingAuth: (() -> Unit)? = null
        AuthenticationUtil.authCallbackOverride = { onAuthenticated ->
            pendingAuth = onAuthenticated
        }

        insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withId(R.id.token_list))
                .perform(
                    actionOnItemAtPosition<RecyclerView.ViewHolder>(
                        0,
                        RecyclerViewAction.clickChildViewWithId(R.id.menu)
                    )
                )
            onView(withText(R.string.share_qr_code)).perform(click())

            assertThat(pendingAuth).isNotNull()
            onView(withId(R.id.token_list)).check(matches(isDisplayed()))

            scenario.onActivity {
                pendingAuth?.invoke()
            }

            onView(withId(R.id.qrcode)).inRoot(isDialog()).check(matches(isDisplayed()))
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
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.export_text)).perform(click())
            onView(withText(R.string.export_json_format)).perform(click())
            assertThat(authPromptCount).isEqualTo(1)

            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.export_text)).perform(click())
            onView(withText(R.string.export_key_uri_format)).perform(click())
            assertThat(authPromptCount).isEqualTo(2)
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
            // Hold prompt open without auto-completing on resume
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
                activity.navigateTo(
                    R.id.action_tokenList_to_shareQrCode,
                    ShareQrCodeDialogFragment.args(tokenId)
                )
            }

            onView(withId(R.id.qrcode)).inRoot(isDialog()).check(matches(isDisplayed()))

            scenario.moveToState(Lifecycle.State.CREATED)

            val deadline = System.currentTimeMillis() + 3_000L
            while (mainActivity.appSessionManager.authState.value != MainViewModel.AuthState.UNAUTHENTICATED &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(25)
            }
            assertThat(mainActivity.appSessionManager.authState.value)
                .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)

            // Resume app (simulating Recents return) -> ShareQrCodeDialogFragment is dismissed and lock gate prompts
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertThat(activity.getOpenDialogWindows()).isEmpty()
                assertThat(activity.currentDestinationId()).isEqualTo(R.id.tokenListFragment)
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
                    exportFile.toUri()
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
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val titles = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it).title.toString() }
                val themeIdx = titles.indexOf(activity.getString(R.string.theme))
                val authIdx = titles.indexOf(activity.getString(R.string.authentication_mode))
                assertThat(themeIdx).isAtLeast(0)
                assertThat(authIdx).isEqualTo(themeIdx + 1)
            }

            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_app_launch)).perform(click())

            onView(withText(R.string.screen_lock_required_title)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.open_settings_button)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.cancel_text)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
            }

            // Reopen Authentication mode popup selector -> "None" remains checked and "App launch" is unchecked
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(
                allOf(
                    isAssignableFrom(ListMenuItemView::class.java),
                    hasDescendant(withText(R.string.auth_mode_none))
                )
            ).check(matches(hasDescendant(allOf(isAssignableFrom(RadioButton::class.java), isChecked()))))
            onView(
                allOf(
                    isAssignableFrom(ListMenuItemView::class.java),
                    hasDescendant(withText(R.string.auth_mode_app_launch))
                )
            ).check(matches(hasDescendant(allOf(isAssignableFrom(RadioButton::class.java), isNotChecked()))))
        }
    }

    @Test
    fun testAppLaunchWithRemovedScreenLockBlocksExportAndOffersTurnOffRecovery() {
        insertTestToken()
        val exportFile = File(getInstrumentation().targetContext.cacheDir, "blocked_unauth_export.json")
        exportFile.delete()

        // Configure APP_LAUNCH first, then simulate user removing device screen lock in Android Settings
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.lockSession()
            }
        }

        AuthenticationUtil.hasScreenLockOverride = false

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.screen_lock_disabled_title)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            onView(withText(R.string.set_up_screen_lock_button)).inRoot(isDialog())
                .check(matches(isDisplayed()))

            // Verify session is strictly UNAUTHENTICATED and unauthenticated export is blocked
            scenario.onActivity { activity ->
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
                activity.handleExportAction(isJson = true)
            }
            assertThat(exportFile.exists()).isFalse()

            // Tapping "Turn off" cleanly disables APP_LAUNCH and unlocks tokens without trapping the user
            onView(withText(R.string.turn_off_button)).inRoot(isDialog()).perform(click())

            scenario.onActivity { activity ->
                assertThat(activity.settings.authMode).isEqualTo(AuthMode.NONE)
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.AUTHENTICATED)
            }
            onView(withId(R.id.token_list)).check(matches(isDisplayed()))
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

            // User cancels BiometricPrompt -> retry dialog shown while tokens stay locked
            scenario.onActivity {
                pendingCancel?.invoke()
            }

            onView(withText(R.string.authentication_dialog_title)).inRoot(isDialog())
                .check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                assertThat(activity.appSessionManager.authState.value)
                    .isEqualTo(MainViewModel.AuthState.UNAUTHENTICATED)
            }

            // Clicking Unlock re-invokes BiometricPrompt and unlocks on success
            onView(withText(R.string.unlock_text)).inRoot(isDialog()).perform(click())
            assertThat(promptCount).isEqualTo(2)

            scenario.onActivity {
                pendingSuccess?.invoke()
            }

            onView(withId(R.id.token_list)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun testShareQrCodeCopyTokenUriButtonCopiesExactSensitiveUriToClipboard() {
        AuthenticationUtil.hasScreenLockOverride = false
        insertTestToken()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitViewMatching(withId(R.id.token_list), isDisplayed())
            onView(withId(R.id.token_list))
                .perform(
                    actionOnItemAtPosition<RecyclerView.ViewHolder>(
                        0,
                        RecyclerViewAction.clickChildViewWithId(R.id.menu)
                    )
                )
            onView(withText(R.string.share_qr_code)).perform(click())

            awaitDialogViewMatching(withId(R.id.copy_uri_button), allOf(isDisplayed(), isEnabled()))
            onView(withId(R.id.copy_uri_button)).inRoot(isDialog())
                .perform(scrollTo(), click())

            awaitViewMatching(withText(R.string.token_uri_copied), isDisplayed())

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

                // Verify direct CharArray extraction & Editable scrubbing without String intermediaries
                val editText = EditText(activity).apply { setText("secretPass!42") }
                assertThat(AuthenticationUtil.extractCharArray(editText))
                    .isEqualTo("secretPass!42".toCharArray())
                AuthenticationUtil.clearAndCancelAutofill(activity, editText)
                assertThat(editText.text.toString()).isEmpty()
            }

            awaitViewMatching(withId(R.id.token_list), isDisplayed())

            // 1. Tap token card -> copies OTP with EXTRA_IS_SENSITIVE = true (API 24+), then auto-clears after 1.2s
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))

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

            // 2. If another clip replaces ours before expiration, expiration never overwrites the external clip
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

            // 3. Locking session immediately clears an active owned sensitive clip
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

    private fun awaitViewMatching(
        viewMatcher: Matcher<View>,
        assertionMatcher: Matcher<View>,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(viewMatcher).check(matches(assertionMatcher))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
            } catch (e: AssertionError) {
                lastError = e
            }
            Thread.sleep(50L)
        }
        if (lastError != null) throw lastError
        onView(viewMatcher).check(matches(assertionMatcher))
    }

    private fun awaitDialogViewMatching(
        viewMatcher: Matcher<View>,
        assertionMatcher: Matcher<View>,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(viewMatcher).inRoot(isDialog()).check(matches(assertionMatcher))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
            } catch (e: AssertionError) {
                lastError = e
            }
            Thread.sleep(50L)
        }
        if (lastError != null) throw lastError
        onView(viewMatcher).inRoot(isDialog()).check(matches(assertionMatcher))
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
