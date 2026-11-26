package org.fedorahosted.freeotp.uitest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
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
import org.fedorahosted.freeotp.util.COPY_TO_CLIPBOARD_KEY
import org.fedorahosted.freeotp.util.COPY_TO_CLIPBOARD_MODE_KEY
import org.fedorahosted.freeotp.util.CopyToClipboardMode
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
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        activityRule.scenario.onActivity { activity ->
            runBlocking {
                activity.otpTokenDatabase.otpTokenDao().deleteAll()
            }
            activity.settings.selectedCategory = null
            activity.settings.authMode = AuthMode.NONE
            activity.settings.copyToClipboardMode = CopyToClipboardMode.ON_REVEAL
            activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
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

    @Test
    fun testAppTitleStaysSingleLineOnNarrowScreenWithAllToolbarActions() {
        AuthenticationUtil.hasScreenLockOverride = true
        try {
            for (widthDp in listOf(320, 280)) {
                activityRule.scenario.onActivity { activity ->
                    // Show all 4 TopAppBar action buttons (Search, Filter, Quit & Lock, Overflow)
                    activity.appSessionManager.prepareAuthenticatedSession()
                    activity.settings.authMode = AuthMode.APP_LAUNCH
                    activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)

                    val content = activity.findViewById<View>(android.R.id.content)
                    val params = content.layoutParams
                    params.width = (widthDp * activity.resources.displayMetrics.density).toInt()
                    content.layoutParams = params
                }
                composeRule.waitForIdle()
                composeRule.awaitTagDisplayed(UiTestTags.APP_TITLE)
                composeRule.onNodeWithTag(UiTestTags.QUIT_AND_LOCK_BUTTON).assertIsDisplayed()

                val results = mutableListOf<TextLayoutResult>()
                composeRule.onNodeWithTag(UiTestTags.APP_TITLE)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                        action(results)
                    }
                assertThat(results).isNotEmpty()
                assertThat(results.single().lineCount).isEqualTo(1)
            }
        } finally {
            AuthenticationUtil.hasScreenLockOverride = null
            activityRule.scenario.onActivity { activity ->
                activity.settings.authMode = AuthMode.NONE
                activity.appSessionManager.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                val content = activity.findViewById<View>(android.R.id.content)
                val params = content.layoutParams
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                content.layoutParams = params
            }
        }
    }

    @Test
    fun testOverflowMenuLeadingIconsAndCopyToClipboardModesForTotpAndHotp() {
        populateTestData()
        composeRule.awaitTokenCardCount(2)

        // 1. Verify leading-icon column alignment across Theme, Authentication mode, Copy to clipboard, and About
        composeRule.openOverflowMenu()
        composeRule.awaitTextDisplayed(string(R.string.theme))

        val referenceLeftPx = composeRule
            .onNodeWithText(string(R.string.scan_qr_code), useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val menuLeftPx = composeRule
            .onNodeWithTag(UiTestTags.OVERFLOW_MENU)
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val density = getInstrumentation().targetContext.resources.displayMetrics.density
        assertThat(referenceLeftPx - menuLeftPx).isAtLeast(40f * density)

        for (labelRes in listOf(R.string.theme, R.string.authentication_mode, R.string.copy_to_clipboard, R.string.about_freeotp)) {
            val itemTextLeftPx = composeRule
                .onNodeWithText(string(labelRes), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .left
            assertThat(itemTextLeftPx).isWithin(2f).of(referenceLeftPx)
        }

        // 2. Verify legacy boolean false -> Off radio selected in submenu
        activityRule.scenario.onActivity { activity ->
            activity.settings.sharedPreferences.edit()
                .remove(COPY_TO_CLIPBOARD_MODE_KEY)
                .putBoolean(COPY_TO_CLIPBOARD_KEY, false)
                .commit()
            activity.settings.refreshFlows()
        }
        composeRule.onNodeWithText(string(R.string.copy_to_clipboard)).performClick()
        composeRule.awaitNodeDisplayed(copyRadioMatcher(R.string.copy_to_clipboard_off))
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_off)).assertIsSelected()
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_on_reveal)).assertIsNotSelected()
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_tap_revealed)).assertIsNotSelected()

        // 3. Select "Off": revealing or re-tapping TOTP never copies to clipboard
        setClipboardSentinel("SENTINEL_OFF")
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_off)).performClick()
        composeRule.clickCardAtPosition(1)
        composeRule.waitForIdle()
        assertThat(readClipboardText()).isEqualTo("SENTINEL_OFF")
        composeRule.clickCardAtPosition(1)
        composeRule.waitForIdle()
        assertThat(readClipboardText()).isEqualTo("SENTINEL_OFF")

        // Reset revealed codes before testing "On reveal"
        activityRule.scenario.onActivity { activity ->
            activity.clearActiveTokenCodes()
        }
        composeRule.assertCardAtPositionHasText(1, "--- ---")

        // 4. Select "On reveal": first tap on hidden HOTP (id=1, counter=0 -> "047 701") reveals AND copies immediately
        setClipboardSentinel("SENTINEL_ON_REVEAL")
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.copy_to_clipboard)).performClick()
        composeRule.awaitNodeDisplayed(copyRadioMatcher(R.string.copy_to_clipboard_on_reveal))
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_on_reveal)).performClick()

        composeRule.clickCardAtPosition(0)
        composeRule.assertCardAtPositionHasText(0, "047 701")
        composeRule.waitUntil(5_000L) { readClipboardText() == "047701" }
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(1L), useUnmergedTree = true)
            .assertTextEquals("1")

        // Hide active codes before testing "Tap revealed code"
        activityRule.scenario.onActivity { activity ->
            activity.clearActiveTokenCodes()
        }
        composeRule.assertCardAtPositionHasText(0, "--- ---")
        composeRule.assertCardAtPositionHasText(1, "--- ---")

        // 5. Select "Tap revealed code":
        //    - First tap on HOTP (counter=1 -> 2) reveals code & advances counter to "2", WITHOUT copying
        //    - Immediate second tap copies that exact revealed code WITHOUT advancing HOTP counter beyond "2"
        setClipboardSentinel("SENTINEL_TAP_REVEALED")
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.copy_to_clipboard)).performClick()
        composeRule.awaitNodeDisplayed(copyRadioMatcher(R.string.copy_to_clipboard_tap_revealed))
        composeRule.onNode(copyRadioMatcher(R.string.copy_to_clipboard_tap_revealed)).performClick()

        composeRule.clickCardAtPosition(0)
        val formattedSixDigitRegex = Regex("\\b(\\d{3}) (\\d{3})\\b")
        var revealedHotpRaw = ""
        composeRule.waitUntil(5_000L) {
            val texts = composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST)
                .onChildren()[0]
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                .orEmpty()
            val match = formattedSixDigitRegex.find(texts)
            if (match != null) {
                revealedHotpRaw = match.groupValues[1] + match.groupValues[2]
                true
            } else {
                false
            }
        }
        assertThat(revealedHotpRaw).isNotEqualTo("047701")
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(1L), useUnmergedTree = true)
            .assertTextEquals("2")
        assertThat(readClipboardText()).isEqualTo("SENTINEL_TAP_REVEALED")

        // Immediate second tap (well within the 5s HOTP cooldown) copies without incrementing counter
        composeRule.clickCardAtPosition(0)
        composeRule.waitUntil(5_000L) { readClipboardText() == revealedHotpRaw }
        composeRule.onNodeWithTag(UiTestTags.tokenCounterValueTag(1L), useUnmergedTree = true)
            .assertTextEquals("2")

        // Also verify TOTP in "Tap revealed code" mode: first tap reveals without copying, second tap copies
        setClipboardSentinel("SENTINEL_TOTP")
        composeRule.clickCardAtPosition(1)
        composeRule.waitForIdle()
        assertThat(readClipboardText()).isEqualTo("SENTINEL_TOTP")

        composeRule.clickCardAtPosition(1)
        composeRule.waitUntil(5_000L) {
            val clip = readClipboardText()
            clip != null && clip != "SENTINEL_TOTP" && clip.length == 6 && clip.all { it.isDigit() }
        }
    }

    private fun copyRadioMatcher(titleRes: Int): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasText(string(titleRes))
    }

    private fun setClipboardSentinel(value: String) {
        activityRule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("sentinel", value))
        }
    }

    private fun readClipboardText(): String? {
        var text: String? = null
        activityRule.scenario.onActivity { activity ->
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
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
