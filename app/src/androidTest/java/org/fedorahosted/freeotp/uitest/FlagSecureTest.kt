package org.fedorahosted.freeotp.uitest

import android.text.Spanned
import android.text.style.URLSpan
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.DeleteTokenDialogFragment
import org.fedorahosted.freeotp.ui.EditTokenFragment
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.ShareQrCodeDialogFragment
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class FlagSecureTest {

    @Test
    fun testMainActivitySetsFlagSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity.window)
            }
        }
    }

    @Test
    fun testFullScreenFragmentsKeepFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateTo(R.id.action_tokenList_to_addToken)
                assertFlagSecureSet(activity.window)
            }
            onView(withId(R.id.secret)).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                activity.navigateBack()
                activity.navigateTo(
                    R.id.action_tokenList_to_editToken,
                    EditTokenFragment.args(tokenId)
                )
                assertFlagSecureSet(activity.window)
            }
            onView(withId(R.id.issuer)).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                activity.navigateBack()
                activity.navigateTo(R.id.action_tokenList_to_about)
                assertFlagSecureSet(activity.window)
            }
            onView(withId(R.id.about_version)).check(matches(isDisplayed()))
            onView(withId(R.id.about_website)).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val websiteView = activity.findViewById<TextView>(R.id.about_website)
                val spanned = websiteView.text as Spanned
                val urls = spanned.getSpans(0, spanned.length, URLSpan::class.java).map { it.url }
                assertThat(urls).contains("https://github.com/helloworld1/FreeOTPPlus")
            }
        }
    }

    @Test
    fun testShareQrCodeDialogSetsFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateTo(
                    R.id.action_tokenList_to_shareQrCode,
                    ShareQrCodeDialogFragment.args(tokenId)
                )
            }
            onView(withId(R.id.qrcode)).inRoot(isDialog()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                val dialogWindows = activity.getOpenDialogWindows()
                assertThat(dialogWindows).isNotEmpty()
                assertFlagSecureSet(dialogWindows.first())
            }
        }
    }

    @Test
    fun testDeleteTokenDialogSetsFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateTo(
                    R.id.action_tokenList_to_deleteToken,
                    DeleteTokenDialogFragment.args(tokenId)
                )
            }
            onView(withId(R.id.delete)).inRoot(isDialog()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                val dialogWindows = activity.getOpenDialogWindows()
                assertThat(dialogWindows).isNotEmpty()
                assertFlagSecureSet(dialogWindows.first())
            }
        }
    }

    @Test
    fun testActivityAlertDialogsSetFlagSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
            onView(withText(R.string.authentication_mode)).perform(click())
            onView(withText(R.string.auth_mode_token_encrypted))
                .check(matches(isDisplayed()))
                .perform(click())
            onView(withId(R.id.key_edit_text)).inRoot(isDialog()).check(matches(isDisplayed()))

            scenario.onActivity { activity ->
                val dialogWindows = activity.getOpenDialogWindows()
                assertThat(dialogWindows).isNotEmpty()
                assertFlagSecureSet(dialogWindows.first())
            }
        }
    }

    private fun insertTestToken(): Long {
        var tokenId = 1L
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao()
                        .insert(TestData.OTP_TOTP_TOKEN_2.copy(id = 1, ordinal = 1))
                    tokenId = 1L
                }
            }
        }
        return tokenId
    }

    private fun assertFlagSecureSet(window: Window) {
        val flags = window.attributes.flags
        assertThat(flags and WindowManager.LayoutParams.FLAG_SECURE)
            .isEqualTo(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
