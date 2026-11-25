package org.fedorahosted.freeotp.uitest

import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.about.htmlToLinkedAnnotatedString
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.navigation.FreeOtpDestination
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class FlagSecureTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @Test
    fun testMainActivitySetsFlagSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFlagSecureSet(activity.window)
            }
        }
    }

    @Test
    fun testFullScreenDestinationsKeepFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateToRoute(FreeOtpDestination.ADD_TOKEN)
                assertFlagSecureSet(activity.window)
            }
            composeRule.awaitTagDisplayed(UiTestTags.SECRET_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT).assertIsDisplayed()

            scenario.onActivity { activity ->
                activity.navigateBack()
                activity.navigateToEditToken(tokenId)
                assertFlagSecureSet(activity.window)
            }
            composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
            composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).assertIsDisplayed()

            scenario.onActivity { activity ->
                activity.navigateBack()
                activity.navigateToRoute(FreeOtpDestination.ABOUT)
                assertFlagSecureSet(activity.window)
            }
            composeRule.awaitTagDisplayed(UiTestTags.ABOUT_VERSION)
            composeRule.onNodeWithTag(UiTestTags.ABOUT_WEBSITE).assertIsDisplayed()

            val context = getInstrumentation().targetContext
            val websiteHtml = context.getString(R.string.about_website, context.getString(R.string.link_github))
            val annotated = htmlToLinkedAnnotatedString(websiteHtml, Color.Blue) {}
            val urls = annotated.getLinkAnnotations(0, annotated.length)
                .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
            assertThat(urls).contains("https://github.com/helloworld1/FreeOTPPlus")

            val nodeTextList = composeRule.onNodeWithTag(UiTestTags.ABOUT_WEBSITE)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
            assertThat(nodeTextList.joinToString { it.text }).contains("GitHub")
        }
    }

    @Test
    fun testShareQrCodeDialogSetsFlagSecure() {
        val tokenId = insertTestToken()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openShareQrDialog(tokenId)
            }
            composeRule.awaitTagDisplayed(UiTestTags.SHARE_QR_IMAGE)

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
                activity.openDeleteTokenDialog(tokenId)
            }
            composeRule.awaitTagDisplayed(UiTestTags.DELETE_CONFIRM_BUTTON)

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
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.authentication_mode)).performClick()
            composeRule.onNodeWithText(string(R.string.auth_mode_token_encrypted))
                .assertIsDisplayed()
                .performClick()

            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)

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
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
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
