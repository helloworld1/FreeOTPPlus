package org.fedorahosted.freeotp.uitest

import android.view.Window
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
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
                    bundleOf(EditTokenFragment.ARG_TOKEN_ID to tokenId)
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
        }
    }

    @Test
    fun testShareQrCodeDialogSetsFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateTo(
                    R.id.action_tokenList_to_shareQrCode,
                    bundleOf(ShareQrCodeDialogFragment.ARG_TOKEN_ID to tokenId)
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
                    bundleOf(DeleteTokenDialogFragment.ARG_TOKEN_ID to tokenId)
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
