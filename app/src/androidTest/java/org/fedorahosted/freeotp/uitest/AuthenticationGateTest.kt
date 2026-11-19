package org.fedorahosted.freeotp.uitest

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.ui.EditActivity
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.ShareQrCodeActivity
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
        val intent = Intent(getInstrumentation().targetContext, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<EditActivity>(intent).use {
            onView(withId(R.id.secret))
                .check(matches(withText(R.string.click_to_display)))
                .perform(click())
                .check(matches(withText(TestData.OTP_TOTP_TOKEN_2.secret)))
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
        val intent = Intent(getInstrumentation().targetContext, EditActivity::class.java)
            .putExtra(EditActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<EditActivity>(intent).use { scenario ->
            onView(withId(R.id.secret))
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
    fun testShareQrCodeWithScreenLockGatesActivity() {
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

            onView(withId(R.id.qrcode)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun testExportJsonAndKeyUriWithScreenLockInvokeAuthGate() {
        AuthenticationUtil.hasScreenLockOverride = true
        var authPromptCount = 0
        AuthenticationUtil.authCallbackOverride = {
            // Simulate cancelling the prompt before launching SAF createFile
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

            // Move MainActivity to background (STOPPED) without calling onStart() again
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
    fun testChildActivityClosesOnBackgroundTimeoutInAppLaunchMode() {
        val tokenId = insertTestToken()
        AuthenticationUtil.appLaunchAuthCallbackOverride = { onSuccess, _ -> onSuccess() }

        ActivityScenario.launch(MainActivity::class.java).use { mainScenario ->
            mainScenario.onActivity { activity ->
                configuredSessionManager = activity.appSessionManager
                activity.appSessionManager.timeoutDelayMs = 150L
                activity.settings.authMode = AuthMode.APP_LAUNCH
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            }
        }

        val qrIntent = Intent(getInstrumentation().targetContext, ShareQrCodeActivity::class.java)
            .putExtra(ShareQrCodeActivity.EXTRA_TOKEN_ID, tokenId)

        ActivityScenario.launch<ShareQrCodeActivity>(qrIntent).use { qrScenario ->
            onView(withId(R.id.qrcode)).check(matches(isDisplayed()))

            qrScenario.moveToState(Lifecycle.State.CREATED)

            val deadline = System.currentTimeMillis() + 3_000L
            while (qrScenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }

            assertThat(qrScenario.state).isEqualTo(Lifecycle.State.DESTROYED)
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

            // Simulate MainActivity stopping when external document picker opens at T = 10s
            scenario.moveToState(Lifecycle.State.CREATED)

            // Simulate user returning from picker 3 minutes later (T = 190s > 120s timeout)
            fakeNow = 190_000L
            getInstrumentation().runOnMainSync {
                mainActivity.onActivityResult(
                    MainActivity.WRITE_JSON_REQUEST_CODE,
                    Activity.RESULT_OK,
                    Intent().setData(exportFile.toUri())
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
