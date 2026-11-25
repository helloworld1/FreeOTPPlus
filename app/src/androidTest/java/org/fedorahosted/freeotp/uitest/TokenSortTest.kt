package org.fedorahosted.freeotp.uitest

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
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
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class TokenSortTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    @Test
    fun testSortMenuPositionedBetweenExportAndTheme_andCancelPreservesManualOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    dao.deleteAll()
                    dao.insertAll(
                        listOf(
                            TestData.OTP_HOTP_TOKEN_1.copy(
                                id = 1L,
                                ordinal = 10L,
                                issuer = "Slack",
                                label = "zoe@slack.com",
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 2L,
                                ordinal = 20L,
                                issuer = "Amazon",
                                label = "amy@amazon.com",
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 3L,
                                ordinal = 30L,
                                issuer = "GitHub",
                                label = "mike@github.com",
                            ),
                        )
                    )
                }
            }
            composeRule.awaitTokenCardCount(3)

            // Verify menu ordering: Export -> Sort -> Theme
            composeRule.openOverflowMenu()
            composeRule.awaitTagDisplayed(UiTestTags.OVERFLOW_MENU)
            val menuItemTitles = composeRule.onNodeWithTag(UiTestTags.OVERFLOW_MENU)
                .onChildren()
                .fetchSemanticsNodes()
                .mapNotNull { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                }
            val exportIdx = menuItemTitles.indexOf(string(R.string.export_text))
            val sortIdx = menuItemTitles.indexOf(string(R.string.sort))
            val themeIdx = menuItemTitles.indexOf(string(R.string.theme))
            assertThat(exportIdx).isAtLeast(0)
            assertThat(sortIdx).isEqualTo(exportIdx + 1)
            assertThat(themeIdx).isEqualTo(sortIdx + 1)

            // Select Sort -> Sort by Account Ascending -> Cancel keeps manual order [Slack, Amazon, GitHub]
            composeRule.onNodeWithText(string(R.string.sort)).performClick()
            composeRule.onNodeWithText(string(R.string.sort_by_account_asc)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.sort_tokens_warning_title))
            composeRule.onNodeWithText(string(R.string.sort_tokens_warning_message)).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()

            assertTokenOrder("Slack", "Amazon", "GitHub")

            // 1. Sort by Account Ascending -> [amy@amazon.com, mike@github.com, zoe@slack.com]
            openSortSubmenu()
            composeRule.onNodeWithText(string(R.string.sort_by_account_asc)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.DIALOG_POSITIVE_BUTTON)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertTokenOrder("amy@amazon.com", "mike@github.com", "zoe@slack.com")

            // 2. Sort by Account Descending -> [zoe@slack.com, mike@github.com, amy@amazon.com]
            openSortSubmenu()
            composeRule.onNodeWithText(string(R.string.sort_by_account_desc)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.DIALOG_POSITIVE_BUTTON)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertTokenOrder("zoe@slack.com", "mike@github.com", "amy@amazon.com")

            // 3. Sort by Issuer Ascending -> [Amazon, GitHub, Slack]
            openSortSubmenu()
            composeRule.onNodeWithText(string(R.string.sort_by_issuer_asc)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.DIALOG_POSITIVE_BUTTON)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertTokenOrder("Amazon", "GitHub", "Slack")

            // 4. Sort by Issuer Descending -> [Slack, GitHub, Amazon]
            openSortSubmenu()
            composeRule.onNodeWithText(string(R.string.sort_by_issuer_desc)).performClick()
            composeRule.awaitTagDisplayed(UiTestTags.DIALOG_POSITIVE_BUTTON)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            assertTokenOrder("Slack", "GitHub", "Amazon")

            scenario.onActivity { activity ->
                val persistedIssuers = runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().getAll().first().map { it.issuer }
                }
                assertThat(persistedIssuers).containsExactly("Slack", "GitHub", "Amazon").inOrder()
            }
        }
    }

    @Test
    fun testLongPressDragTokenToTopAndBottomWithoutViewportJumping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                runBlocking {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    dao.deleteAll()
                    dao.insertAll(
                        listOf(
                            TestData.OTP_HOTP_TOKEN_1.copy(
                                id = 1L,
                                ordinal = 10L,
                                issuer = "Alpha",
                                label = "alpha@example.com",
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 2L,
                                ordinal = 20L,
                                issuer = "Bravo",
                                label = "bravo@example.com",
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 3L,
                                ordinal = 30L,
                                issuer = "Charlie",
                                label = "charlie@example.com",
                            ),
                        )
                    )
                }
            }
            composeRule.awaitTokenCardCount(3)
            assertTokenOrder("Alpha", "Bravo", "Charlie")

            // 1. Long-press Charlie (index 2) and drag smoothly to Alpha's slot at the top (index 0)
            dragCardInList(sourceTokenId = 3L, targetTokenId = 1L)

            assertTokenOrder("Charlie", "Alpha", "Bravo")
            val topBoundsAfterDragUp = composeRule.onNodeWithTag(UiTestTags.tokenCardTag(3L))
                .fetchSemanticsNode()
                .boundsInRoot
            val listBounds = composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST)
                .fetchSemanticsNode()
                .boundsInRoot
            // Verify index 0 stayed anchored at the top of the list (no viewport scroll jump)
            assertThat(topBoundsAfterDragUp.top).isAtLeast(listBounds.top)
            assertThat(topBoundsAfterDragUp.top - listBounds.top).isLessThan(60f)

            scenario.onActivity { activity ->
                val persistedAfterUp = runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().getAll().first().map { it.issuer }
                }
                assertThat(persistedAfterUp).containsExactly("Charlie", "Alpha", "Bravo").inOrder()
            }

            // 2. Long-press Charlie (now at index 0) and drag down to Bravo's slot (index 2)
            dragCardInList(sourceTokenId = 3L, targetTokenId = 2L)

            assertTokenOrder("Alpha", "Bravo", "Charlie")
            scenario.onActivity { activity ->
                val persistedAfterDown = runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().getAll().first().map { it.issuer }
                }
                assertThat(persistedAfterDown).containsExactly("Alpha", "Bravo", "Charlie").inOrder()
            }
        }
    }

    private fun dragCardInList(sourceTokenId: Long, targetTokenId: Long) {
        val listBounds = composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST)
            .fetchSemanticsNode()
            .boundsInRoot
        val sourceCenterInList = composeRule.onNodeWithTag(UiTestTags.tokenCardTag(sourceTokenId))
            .fetchSemanticsNode()
            .boundsInRoot
            .center - listBounds.topLeft
        val targetCenterInList = composeRule.onNodeWithTag(UiTestTags.tokenCardTag(targetTokenId))
            .fetchSemanticsNode()
            .boundsInRoot
            .center - listBounds.topLeft

        val totalDelta = targetCenterInList - sourceCenterInList
        val steps = 16
        val stepDelta = totalDelta / steps.toFloat()

        // Start long-press on source card
        composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST).performTouchInput {
            down(sourceCenterInList)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 150L)
            moveBy(Offset(0f, if (totalDelta.y < 0f) -4f else 4f), delayMillis = 16L)
        }
        composeRule.waitForIdle()

        // Move across real Compose layout frames so intermediate swaps and top-slot anchoring execute
        repeat(steps) {
            composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST).performTouchInput {
                moveBy(stepDelta, delayMillis = 16L)
            }
            composeRule.waitForIdle()
        }

        // Small hover movement at destination to verify zero oscillation before finger lift
        composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST).performTouchInput {
            moveBy(Offset(0f, -6f), delayMillis = 16L)
            moveBy(Offset(0f, 6f), delayMillis = 16L)
            up()
        }
        composeRule.waitForIdle()
    }

    private fun openSortSubmenu() {
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.sort)).performClick()
    }

    private fun assertTokenOrder(vararg expectedTexts: String) {
        expectedTexts.forEachIndexed { idx, expected ->
            composeRule.assertCardAtPositionHasText(idx, expected)
        }
    }
}
