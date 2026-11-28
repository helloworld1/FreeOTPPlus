package org.fedorahosted.freeotp.uitest

import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitNodeDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.DARK_MODE_KEY
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.THEME_MODE_KEY
import org.fedorahosted.freeotp.util.TOKEN_CODE_GROUPING_KEY
import org.fedorahosted.freeotp.util.TOKEN_TIMER_STYLE_KEY
import org.fedorahosted.freeotp.util.ThemeMode
import org.fedorahosted.freeotp.util.TokenCodeGrouping
import org.fedorahosted.freeotp.util.TokenTimerStyle
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class ThemeModeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @After
    fun tearDown() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .remove(THEME_MODE_KEY)
            .remove(DARK_MODE_KEY)
            .remove(TOKEN_TIMER_STYLE_KEY)
            .remove(TOKEN_CODE_GROUPING_KEY)
            .commit()
        getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    @Test
    fun testThemeSubmenuSwitchSystemLightDark() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(THEME_MODE_KEY).remove(DARK_MODE_KEY).commit()
        val settings = Settings(prefs)

        ActivityScenario.launch(MainActivity::class.java).use {
            // Default is System
            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = true, lightChecked = false, darkChecked = false)

            // Select Light
            composeRule.onNode(radioRowMatcher(R.string.theme_light)).performClick()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.LIGHT)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = true, darkChecked = false)

            // Select Dark
            composeRule.onNode(radioRowMatcher(R.string.theme_dark)).performClick()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = false, darkChecked = true)

            // Select System
            composeRule.onNode(radioRowMatcher(R.string.theme_system)).performClick()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = true, lightChecked = false, darkChecked = false)

            // Back from Theme submenu returns to Visual submenu, and Back from Visual returns to root menu
            composeRule.onNodeWithText(string(R.string.theme)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.visual))
            composeRule.awaitTextDisplayed(string(R.string.theme))
            composeRule.onNode(radioRowMatcher(R.string.theme_system)).assertDoesNotExist()

            composeRule.onNodeWithText(string(R.string.visual)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.import_text))
            composeRule.awaitTextDisplayed(string(R.string.visual))
            composeRule.onNodeWithText(string(R.string.theme)).assertDoesNotExist()
        }
    }

    @Test
    fun testLegacyUseDarkThemeUncheckedUpgradesToSystem() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .remove(THEME_MODE_KEY)
            .putBoolean(DARK_MODE_KEY, false)
            .commit()
        val settings = Settings(prefs)
        getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(settings.themeMode.nightMode)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = true, lightChecked = false, darkChecked = false)

            // Back from Theme submenu returns to Visual submenu, and Back from Visual returns to root menu
            composeRule.onNodeWithText(string(R.string.theme)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.visual))
            composeRule.awaitTextDisplayed(string(R.string.theme))
            composeRule.onNode(radioRowMatcher(R.string.theme_system)).assertDoesNotExist()

            composeRule.onNodeWithText(string(R.string.visual)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.import_text))
            composeRule.awaitTextDisplayed(string(R.string.visual))
            composeRule.onNodeWithText(string(R.string.theme)).assertDoesNotExist()
        }
    }

    @Test
    fun testLegacyUseDarkThemeCheckedUpgradesToDark() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .remove(THEME_MODE_KEY)
            .putBoolean(DARK_MODE_KEY, true)
            .commit()
        val settings = Settings(prefs)
        getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(settings.themeMode.nightMode)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = false, darkChecked = true)
        }
    }

    @Test
    fun testTimerStyleSwitchBetweenCircleAndBottomProgressBar() {
        val context = getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(TOKEN_TIMER_STYLE_KEY).commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var totpId = 0L
            var hotpId = 0L
            scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                    totpId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.TOTP,
                            issuer = "TimerTotp",
                            label = "alice@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                        )
                    )
                    hotpId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.HOTP,
                            issuer = "TimerHotp",
                            label = "bob@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                        )
                    )
                }
            }

            try {
                composeRule.awaitTagDisplayed(UiTestTags.tokenCardTag(totpId))

                // 1. Default timer style in Visual -> Timer style is Circle
                openTimerStyleSubmenu()
                assertRadioChecked(R.string.timer_style_circle, true)
                assertRadioChecked(R.string.timer_style_progress_bar, false)

                // Back from Timer style returns to Visual submenu
                composeRule.onNodeWithText(string(R.string.token_timer_style)).performClick()
                composeRule.awaitTextDisplayed(string(R.string.visual))
                composeRule.awaitTextDisplayed(string(R.string.theme))
                composeRule.awaitTextDisplayed(string(R.string.token_timer_style))
                composeRule.onNode(radioRowMatcher(R.string.timer_style_circle)).assertDoesNotExist()
                // Dismiss menu by selecting Circle explicitly
                composeRule.onNodeWithText(string(R.string.token_timer_style)).performClick()
                composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.timer_style_circle))
                composeRule.onNode(radioRowMatcher(R.string.timer_style_circle)).performClick()

                // 2. Reveal TOTP code in Circle style -> shows circular ring, no bottom progress bar
                val unrevealedHeight = composeRule.onNodeWithTag(UiTestTags.tokenCardTag(totpId))
                    .fetchSemanticsNode().boundsInRoot.height
                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(totpId)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.tokenProgressRingTag(totpId), useUnmergedTree = true)
                composeRule.onNodeWithTag(UiTestTags.tokenProgressRingTag(totpId), useUnmergedTree = true)
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(UiTestTags.tokenProgressBarTag(totpId), useUnmergedTree = true)
                    .assertDoesNotExist()

                // 3. Switch to Progress bar style -> live-switches to thin bar at bottom of card with zero height change
                openTimerStyleSubmenu()
                composeRule.onNode(radioRowMatcher(R.string.timer_style_progress_bar)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.tokenProgressBarTag(totpId), useUnmergedTree = true)

                scenario.onActivity { activity ->
                    assertThat(activity.settings.tokenTimerStyle).isEqualTo(TokenTimerStyle.PROGRESS_BAR)
                }

                composeRule.onNodeWithTag(UiTestTags.tokenProgressRingTag(totpId), useUnmergedTree = true)
                    .assertDoesNotExist()
                composeRule.onNodeWithTag(UiTestTags.tokenProgressBarTag(totpId), useUnmergedTree = true)
                    .assertIsDisplayed()

                val cardBounds = composeRule.onNodeWithTag(UiTestTags.tokenCardTag(totpId))
                    .fetchSemanticsNode().boundsInRoot
                val barBounds = composeRule.onNodeWithTag(UiTestTags.tokenProgressBarTag(totpId), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot

                assertThat(cardBounds.height).isWithin(1f).of(unrevealedHeight)
                assertThat(barBounds.height).isAtMost(6f * density)
                assertThat(cardBounds.bottom - barBounds.bottom).isAtMost(4f * density)

                // 4. Reveal HOTP code in Progress bar style -> also shows thin bottom progress bar
                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(hotpId)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.tokenProgressBarTag(hotpId), useUnmergedTree = true)
                composeRule.onNodeWithTag(UiTestTags.tokenProgressBarTag(hotpId), useUnmergedTree = true)
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(UiTestTags.tokenProgressRingTag(hotpId), useUnmergedTree = true)
                    .assertDoesNotExist()

                // 5. Switch back to Circle style -> restores ring and hides bottom progress bar
                openTimerStyleSubmenu()
                assertRadioChecked(R.string.timer_style_circle, false)
                assertRadioChecked(R.string.timer_style_progress_bar, true)
                composeRule.onNode(radioRowMatcher(R.string.timer_style_circle)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.tokenProgressRingTag(totpId), useUnmergedTree = true)

                composeRule.onNodeWithTag(UiTestTags.tokenProgressRingTag(totpId), useUnmergedTree = true)
                    .assertIsDisplayed()
                composeRule.onNodeWithTag(UiTestTags.tokenProgressBarTag(totpId), useUnmergedTree = true)
                    .assertDoesNotExist()
            } finally {
                scenario.onActivity { activity ->
                    runBlocking {
                        activity.otpTokenDatabase.otpTokenDao().deleteAll()
                    }
                }
            }
        }
    }

    @Test
    fun testCodeGroupingSwitchBetweenTwoGroupsThreeGroupsAndNoGrouping() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(TOKEN_CODE_GROUPING_KEY).commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var sixDigitId = 0L
            var sevenCharSteamId = 0L
            var eightDigitId = 0L
            var unrevealedId = 0L
            scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().deleteAll()
                    sixDigitId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.HOTP,
                            issuer = "GroupingSix",
                            label = "six@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                            digits = 6,
                            initialCounter = 1L,
                        )
                    )
                    sevenCharSteamId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.HOTP,
                            issuer = "Steam",
                            label = "steam7@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                            digits = 7,
                            initialCounter = 1L,
                        )
                    )
                    eightDigitId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.HOTP,
                            issuer = "GroupingEight",
                            label = "eight@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                            digits = 8,
                            initialCounter = 1L,
                        )
                    )
                    unrevealedId = activity.otpTokenDatabase.otpTokenDao().insert(
                        OtpTokenFactory.create(
                            type = OtpTokenType.TOTP,
                            issuer = "GroupingPlaceholder",
                            label = "placeholder@example.com",
                            secret = "JBSWY3DPEHPK3PXP",
                            digits = 6,
                        )
                    )
                }
            }

            try {
                composeRule.awaitTagDisplayed(UiTestTags.tokenCardTag(sixDigitId))

                // 1. Default in Visual -> Code grouping is Two groups (current style)
                openCodeGroupingSubmenu()
                assertRadioChecked(R.string.code_grouping_two_groups, true)
                assertRadioChecked(R.string.code_grouping_three_groups, false)
                assertRadioChecked(R.string.code_grouping_none, false)

                // Back from Code grouping returns to Visual submenu
                composeRule.onNodeWithText(string(R.string.token_code_grouping)).performClick()
                composeRule.awaitTextDisplayed(string(R.string.visual))
                composeRule.awaitTextDisplayed(string(R.string.theme))
                composeRule.awaitTextDisplayed(string(R.string.token_timer_style))
                composeRule.awaitTextDisplayed(string(R.string.token_code_grouping))
                composeRule.onNode(radioRowMatcher(R.string.code_grouping_two_groups)).assertDoesNotExist()

                // Dismiss menu by selecting Two groups explicitly
                composeRule.onNodeWithText(string(R.string.token_code_grouping)).performClick()
                composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.code_grouping_two_groups))
                composeRule.onNode(radioRowMatcher(R.string.code_grouping_two_groups)).performClick()

                // 2. Unrevealed placeholders default to Two groups
                assertThat(readTokenCodeText(sixDigitId)).isEqualTo("--- ---")
                assertThat(readTokenCodeText(sevenCharSteamId)).isEqualTo("--- ----")
                assertThat(readTokenCodeText(eightDigitId)).isEqualTo("---- ----")
                assertThat(readTokenCodeText(unrevealedId)).isEqualTo("--- ---")

                // 3. Reveal 6-digit, 7-character Steam, and 8-digit HOTP codes -> grouped into two groups
                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(sixDigitId)).performClick()
                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(sixDigitId).matches(Regex("""^\d{3} \d{3}$"""))
                }
                val rawSix = readTokenCodeText(sixDigitId).replace(" ", "")

                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(sevenCharSteamId)).performClick()
                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(sevenCharSteamId).matches(Regex("""^[A-Z0-9]{3} [A-Z0-9]{4}$"""))
                }
                val rawSeven = readTokenCodeText(sevenCharSteamId).replace(" ", "")

                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(eightDigitId)).performClick()
                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(eightDigitId).matches(Regex("""^\d{4} \d{4}$"""))
                }
                val rawEight = readTokenCodeText(eightDigitId).replace(" ", "")

                // 4. Switch to Three groups -> formats 6 as 2-2-2, 7 as 3-2-2, and 8 as 3-3-2
                openCodeGroupingSubmenu()
                composeRule.onNode(radioRowMatcher(R.string.code_grouping_three_groups)).performClick()

                val expectedThreeSix = "${rawSix.substring(0, 2)} ${rawSix.substring(2, 4)} ${rawSix.substring(4)}"
                val expectedThreeSeven = "${rawSeven.substring(0, 3)} ${rawSeven.substring(3, 5)} ${rawSeven.substring(5)}"
                val expectedThreeEight = "${rawEight.substring(0, 3)} ${rawEight.substring(3, 6)} ${rawEight.substring(6)}"

                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(sixDigitId) == expectedThreeSix
                }
                scenario.onActivity { activity ->
                    assertThat(activity.settings.tokenCodeGrouping).isEqualTo(TokenCodeGrouping.THREE_GROUPS)
                }
                assertThat(readTokenCodeText(sixDigitId)).matches("""^\d{2} \d{2} \d{2}$""")
                assertThat(readTokenCodeText(sevenCharSteamId)).isEqualTo(expectedThreeSeven)
                assertThat(readTokenCodeText(sevenCharSteamId)).matches("""^[A-Z0-9]{3} [A-Z0-9]{2} [A-Z0-9]{2}$""")
                assertThat(readTokenCodeText(eightDigitId)).isEqualTo(expectedThreeEight)
                assertThat(readTokenCodeText(eightDigitId)).matches("""^\d{3} \d{3} \d{2}$""")
                assertThat(readTokenCodeText(unrevealedId)).isEqualTo("-- -- --")

                // 5. Switch to No grouping -> live-updates revealed codes and unrevealed placeholder
                openCodeGroupingSubmenu()
                assertRadioChecked(R.string.code_grouping_two_groups, false)
                assertRadioChecked(R.string.code_grouping_three_groups, true)
                assertRadioChecked(R.string.code_grouping_none, false)
                composeRule.onNode(radioRowMatcher(R.string.code_grouping_none)).performClick()

                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(sixDigitId) == rawSix
                }
                scenario.onActivity { activity ->
                    assertThat(activity.settings.tokenCodeGrouping).isEqualTo(TokenCodeGrouping.NONE)
                }
                assertThat(readTokenCodeText(sevenCharSteamId)).isEqualTo(rawSeven)
                assertThat(readTokenCodeText(eightDigitId)).isEqualTo(rawEight)
                assertThat(readTokenCodeText(unrevealedId)).isEqualTo("------")

                // 6. Switch back to Two groups -> restores two-group display
                openCodeGroupingSubmenu()
                assertRadioChecked(R.string.code_grouping_two_groups, false)
                assertRadioChecked(R.string.code_grouping_three_groups, false)
                assertRadioChecked(R.string.code_grouping_none, true)
                composeRule.onNode(radioRowMatcher(R.string.code_grouping_two_groups)).performClick()

                composeRule.waitUntil(5_000L) {
                    readTokenCodeText(sixDigitId) == "${rawSix.substring(0, 3)} ${rawSix.substring(3)}"
                }
                assertThat(readTokenCodeText(sevenCharSteamId)).isEqualTo("${rawSeven.substring(0, 3)} ${rawSeven.substring(3)}")
                assertThat(readTokenCodeText(eightDigitId)).isEqualTo("${rawEight.substring(0, 4)} ${rawEight.substring(4)}")
                assertThat(readTokenCodeText(unrevealedId)).isEqualTo("--- ---")
            } finally {
                scenario.onActivity { activity ->
                    runBlocking {
                        activity.otpTokenDatabase.otpTokenDao().deleteAll()
                    }
                }
            }
        }
    }

    private fun readTokenCodeText(tokenId: Long): String {
        val node = composeRule.onNodeWithTag(
            UiTestTags.tokenCodeTag(tokenId),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        return node.config[SemanticsProperties.Text].joinToString("") { it.text }
    }

    private fun openCodeGroupingSubmenu() {
        composeRule.openOverflowMenu()
        composeRule.awaitTextDisplayed(string(R.string.visual))
        composeRule.onNodeWithText(string(R.string.visual)).performClick()
        composeRule.awaitTextDisplayed(string(R.string.token_code_grouping))
        composeRule.onNodeWithText(string(R.string.token_code_grouping)).performClick()
        composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.code_grouping_two_groups))
    }

    private fun openTimerStyleSubmenu() {
        composeRule.openOverflowMenu()
        composeRule.awaitTextDisplayed(string(R.string.visual))
        composeRule.onNodeWithText(string(R.string.visual)).performClick()
        composeRule.awaitTextDisplayed(string(R.string.token_timer_style))
        composeRule.onNodeWithText(string(R.string.token_timer_style)).performClick()
        composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.timer_style_circle))
    }

    private fun openThemeSubmenu() {
        composeRule.openOverflowMenu()
        composeRule.awaitTextDisplayed(string(R.string.visual))
        composeRule.onNodeWithText(string(R.string.visual)).performClick()
        composeRule.awaitTextDisplayed(string(R.string.theme))
        composeRule.onNodeWithText(string(R.string.theme)).performClick()
        composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.theme_system))
    }

    private fun radioRowMatcher(titleRes: Int): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasText(string(titleRes))
    }

    private fun assertThemeRadioStates(
        systemChecked: Boolean,
        lightChecked: Boolean,
        darkChecked: Boolean,
    ) {
        assertRadioChecked(R.string.theme_system, systemChecked)
        assertRadioChecked(R.string.theme_light, lightChecked)
        assertRadioChecked(R.string.theme_dark, darkChecked)
    }

    private fun assertRadioChecked(titleRes: Int, expectedChecked: Boolean) {
        val node = composeRule.onNode(radioRowMatcher(titleRes))
        if (expectedChecked) {
            node.assertIsSelected()
        } else {
            node.assertIsNotSelected()
        }
    }
}
