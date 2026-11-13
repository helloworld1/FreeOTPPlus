package org.fedorahosted.freeotp.uitest

import android.content.Intent
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
import org.fedorahosted.freeotp.ui.EditActivity
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class AuthenticationGateTest {

    @After
    fun tearDown() {
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.authCallbackOverride = null
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
