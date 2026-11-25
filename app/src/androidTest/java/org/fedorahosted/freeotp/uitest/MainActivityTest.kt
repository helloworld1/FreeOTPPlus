package org.fedorahosted.freeotp.uitest

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.uitest.TestWaitUtil.clickCardAtPosition
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class MainActivityTest {
    private val composeRule = createEmptyComposeRule()
    private val activityRule = activityScenarioRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(composeRule).around(activityRule)

    @Before
    fun setUp() {
        activityRule.scenario.onActivity { activity ->
            runBlocking {
                activity.otpTokenDatabase.otpTokenDao().deleteAll()
            }
        }
    }

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @Test
    fun testListing() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)
        composeRule.assertCardAtPositionHasText(0, "github.com")
        composeRule.assertCardAtPositionHasText(0, "github account 1")
        composeRule.assertCardAtPositionHasText(1, "microsoft.com")
        composeRule.assertCardAtPositionHasText(1, "microsoft account 1")
    }

    @Test
    fun testInsertToken() {
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.add_token)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("issuer1")
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("account1")
        composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT).performTextReplacement("abcd5432")
        composeRule.onNodeWithTag(UiTestTags.ADD_SUBMIT_BUTTON)
            .assertIsEnabled()
            .performClick()

        composeRule.awaitTokenCardCount(1)
        composeRule.assertCardAtPositionHasText(0, "issuer1")
        composeRule.assertCardAtPositionHasText(0, "account1")
    }

    @Test
    fun testInsertTokenWithoutIssuer() {
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.add_token)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.LABEL_INPUT)
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("account_no_issuer")
        composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT).performTextReplacement("abcd5432")
        composeRule.onNodeWithTag(UiTestTags.ADD_SUBMIT_BUTTON)
            .assertIsEnabled()
            .performClick()

        composeRule.awaitTokenCardCount(1)
        composeRule.assertCardAtPositionHasText(0, "account_no_issuer")
    }

    @Test
    fun testEditTokenRemoveIssuer() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("")
        composeRule.onNodeWithTag(UiTestTags.SAVE_BUTTON)
            .assertIsEnabled()
            .performClick()

        composeRule.awaitTokenCardCount(2)
        composeRule.assertCardAtPositionHasText(0, "github account 1")

        // Reopen Edit screen and verify Save/Restore start disabled for null issuer
        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.SAVE_BUTTON).assertIsNotEnabled()
        composeRule.onNodeWithTag(UiTestTags.RESTORE_DEFAULTS_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun testEditToken() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("new issuer")
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("new account")
        composeRule.onNodeWithTag(UiTestTags.SAVE_BUTTON).performClick()

        composeRule.awaitTokenCardCount(2)
        composeRule.assertCardAtPositionHasText(0, "new issuer")
        composeRule.assertCardAtPositionHasText(0, "new account")
    }

    @Test
    fun testDeleteToken() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.DELETE_CONFIRM_BUTTON)
        composeRule.onNodeWithTag(UiTestTags.DELETE_CONFIRM_BUTTON).performClick()

        composeRule.awaitTokenCardCount(1)
        composeRule.assertCardAtPositionHasText(0, "microsoft.com")
        composeRule.assertCardAtPositionHasText(0, "microsoft account 1")
    }

    @Test
    fun testDeleteTokenWithoutIssuer() {
        activityRule.scenario.onActivity { activity ->
            runBlocking {
                activity.otpTokenDatabase.otpTokenDao().insert(
                    TestData.OTP_TOTP_TOKEN_2.copy(id = 1, ordinal = 1, issuer = null)
                )
            }
        }

        composeRule.awaitTokenCardCount(1)
        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.delete)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.DELETE_ISSUER)
        composeRule.onNodeWithTag(UiTestTags.DELETE_ISSUER).assertTextEquals("microsoft account 1")
        composeRule.onNodeWithTag(UiTestTags.DELETE_CONFIRM_BUTTON).performClick()

        composeRule.awaitTokenCardCount(0)
    }

    @Test
    fun testTokenClickRevealHotp() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        // HOTP token (id=1) shows counter "0"; TOTP token (id=2) omits counter badge
        composeRule.onNodeWithTag(UiTestTags.tokenCounterContainerTag(1L), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(1L), useUnmergedTree = true)
            .assertTextEquals("0")
        composeRule.onNodeWithTag(UiTestTags.tokenCounterContainerTag(2L), useUnmergedTree = true)
            .assertDoesNotExist()

        // Tap HOTP token -> reveals RFC 4226 counter=0 code ("047 701") AND increments counter badge live to "1"
        composeRule.clickCardAtPosition(0)
        composeRule.assertCardAtPositionHasText(0, "047 701")
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(1L), useUnmergedTree = true)
            .assertTextEquals("1")

        // Open Edit screen and verify Counter matches "1"
        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(1L)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.DETAIL_INTERVAL_OR_COUNTER)
        composeRule.onNodeWithTag(UiTestTags.DETAIL_INTERVAL_OR_COUNTER)
            .performScrollTo()
            .assertTextEquals("1")
    }

    @Test
    fun testAddHotpTokenWithCustomCounterAndIncrementOnUse() {
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.add_token)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("RFC4226")
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("alice@example.com")
        composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT)
            .performTextReplacement("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        composeRule.onNodeWithTag(UiTestTags.TYPE_HOTP_BUTTON)
            .performScrollTo()
            .performClick()
        composeRule.awaitTagDisplayed(UiTestTags.COUNTER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.COUNTER_INPUT)
            .performScrollTo()
            .performTextReplacement("5")
        composeRule.onNodeWithTag(UiTestTags.ADD_SUBMIT_BUTTON)
            .assertIsEnabled()
            .performClick()

        composeRule.awaitTokenCardCount(1)
        val insertedId = runBlocking {
            var id = 1L
            activityRule.scenario.onActivity { activity ->
                id = runBlocking { activity.otpTokenDatabase.otpTokenDao().getAll().first().single().id }
            }
            id
        }
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(insertedId), useUnmergedTree = true)
            .assertTextEquals("5")

        composeRule.clickCardAtPosition(0)

        // RFC 4226 Appendix D Count=5 code is "254 676", and counter advances to "6"
        composeRule.assertCardAtPositionHasText(0, "254 676")
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(insertedId), useUnmergedTree = true)
            .assertTextEquals("6")
    }

    @Test
    fun testSearchToken() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        composeRule.onNodeWithTag(UiTestTags.SEARCH_BUTTON).performClick()
        composeRule.awaitTagDisplayed(UiTestTags.SEARCH_FIELD)
        composeRule.onNodeWithTag(UiTestTags.SEARCH_FIELD).performTextReplacement("Microsoft")
        composeRule.awaitTokenCardCount(1)
        composeRule.assertCardAtPositionHasText(0, "microsoft.com")
        composeRule.assertCardAtPositionHasText(0, "microsoft account 1")
        composeRule.clickCardAtPosition(0)

        // Collapsing search clears query and restores full list
        composeRule.onNodeWithTag(UiTestTags.SEARCH_COLLAPSE_BUTTON).performClick()
        composeRule.awaitTokenCardCount(2)
    }

    @Test
    fun testExternalTokenLinkConfirmationAccept() {
        activityRule.scenario.close()
        val context = getInstrumentation().targetContext
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("otpauth://totp/GitHub:alice@example.com?secret=JBSWY3DPEHPK3PXP")
        ).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    assertThat(activity.otpTokenDatabase.otpTokenDao().getAll().first()).isEmpty()
                }
            }

            composeRule.awaitTextDisplayed("GitHub", substring = true)
            composeRule.awaitTextDisplayed("alice@example.com", substring = true)

            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            composeRule.awaitTokenCardCount(1)
            composeRule.assertCardAtPositionHasText(0, "GitHub")
            composeRule.assertCardAtPositionHasText(0, "alice@example.com")
        }
    }

    @Test
    fun testExternalTokenLinkConfirmationCancel() {
        activityRule.scenario.close()
        val context = getInstrumentation().targetContext
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("otpauth://totp/bob@example.com?secret=JBSWY3DPEHPK3PXP")
        ).setClass(context, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use {
            composeRule.awaitTextDisplayed("bob@example.com", substring = true)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()
            composeRule.awaitTokenCardCount(0)
        }
    }

    @Test
    fun testBuiltInIconPickerInAddTokenSelectsIconAndSaves() {
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.add_token)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.TOKEN_AVATAR_BUTTON)
        composeRule.onNodeWithTag(UiTestTags.TOKEN_AVATAR_BUTTON).performClick()
        composeRule.awaitTextDisplayed(string(R.string.choose_builtin_icon))
        composeRule.onNodeWithText(string(R.string.choose_builtin_icon)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ICON_SEARCH_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ICON_SEARCH_INPUT).performTextReplacement("GitHub")
        composeRule.awaitTagDisplayed(UiTestTags.builtInIconItemTag("GitHub"))
        composeRule.onNodeWithTag(UiTestTags.builtInIconItemTag("GitHub")).performClick()

        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).performTextReplacement("CustomGitService")
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).performTextReplacement("dev@custom.example")
        composeRule.onNodeWithTag(UiTestTags.SECRET_INPUT).performTextReplacement("JBSWY3DPEHPK3PXP")
        composeRule.onNodeWithTag(UiTestTags.ADD_SUBMIT_BUTTON).performClick()

        val saved = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1).single()
        assertThat(saved.imagePath).isEqualTo("builtin:GitHub")
    }

    private fun populateTestData() {
        activityRule.scenario.onActivity { activity ->
            runBlocking {
                activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_HOTP_TOKEN_1)
                activity.otpTokenDatabase.otpTokenDao().insert(TestData.OTP_TOTP_TOKEN_2)
            }
        }
    }
}
