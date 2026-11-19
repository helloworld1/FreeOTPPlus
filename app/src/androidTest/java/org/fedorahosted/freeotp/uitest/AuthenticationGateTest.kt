package org.fedorahosted.freeotp.uitest

import android.os.SystemClock
import android.widget.TextView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.ui.EditTokenFragment
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.ShareQrCodeDialogFragment
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class AuthenticationGateTest {

    private var configuredSessionManager: AppSessionManager? = null

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

    private fun insertTestToken(): Long {
        var tokenId = 1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.settings.requireAuthentication = false
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao()
                        .insert(TestData.OTP_TOTP_TOKEN_2.copy(id = 1, ordinal = 1))
                    tokenId = 1L
                }
            }
        }
        return tokenId
    }
}
