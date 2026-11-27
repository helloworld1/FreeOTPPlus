package org.fedorahosted.freeotp.uitest

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags

object TestWaitUtil {

    val isTokenCardMatcher: SemanticsMatcher = SemanticsMatcher("isTokenCard") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("token_card_") == true
    }

    fun ComposeTestRule.openOverflowMenu() {
        awaitNodeDisplayed(hasTestTag(UiTestTags.OVERFLOW_MENU_BUTTON))
        onNodeWithTag(UiTestTags.OVERFLOW_MENU_BUTTON).performClick()
    }

    fun ComposeTestRule.openAddTokenFromFab() {
        awaitTagDisplayed(UiTestTags.FAB_MENU_BUTTON)
        onNodeWithTag(UiTestTags.FAB_MENU_BUTTON).performClick()
        awaitTagDisplayed(UiTestTags.FAB_ADD_TOKEN_ITEM)
        onNodeWithTag(UiTestTags.FAB_ADD_TOKEN_ITEM).performClick()
    }

    fun ComposeTestRule.awaitNodeDisplayed(
        matcher: SemanticsMatcher,
        timeoutMs: Long = 5_000L,
        useUnmergedTree: Boolean = false,
    ) {
        waitUntil(timeoutMillis = timeoutMs) {
            onAllNodes(matcher, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val interaction = onNode(matcher, useUnmergedTree = useUnmergedTree)
        if (!interaction.isDisplayed()) {
            runCatching { interaction.performScrollTo() }
        }
        interaction.assertIsDisplayed()
    }

    fun ComposeTestRule.awaitTagDisplayed(
        tag: String,
        timeoutMs: Long = 5_000L,
        useUnmergedTree: Boolean = false,
    ) {
        awaitNodeDisplayed(
            matcher = hasTestTag(tag),
            timeoutMs = timeoutMs,
            useUnmergedTree = useUnmergedTree,
        )
    }

    fun ComposeTestRule.awaitTextDisplayed(
        text: String,
        substring: Boolean = false,
        timeoutMs: Long = 5_000L,
        useUnmergedTree: Boolean = false,
    ) {
        awaitNodeDisplayed(
            matcher = hasText(text, substring = substring),
            timeoutMs = timeoutMs,
            useUnmergedTree = useUnmergedTree,
        )
    }

    fun ComposeTestRule.awaitTokenCardCount(
        expectedCount: Int,
        timeoutMs: Long = 5_000L,
    ) {
        if (expectedCount == 0) {
            waitUntil(timeoutMillis = timeoutMs) {
                onAllNodes(isTokenCardMatcher).fetchSemanticsNodes().isEmpty()
            }
            assertThat(onAllNodes(isTokenCardMatcher).fetchSemanticsNodes()).isEmpty()
            return
        }
        waitUntil(timeoutMillis = timeoutMs) {
            onAllNodesWithTag(UiTestTags.TOKEN_LIST).fetchSemanticsNodes().isNotEmpty() &&
                onNodeWithTag(UiTestTags.TOKEN_LIST).onChildren().fetchSemanticsNodes().size == expectedCount
        }
    }

    fun ComposeTestRule.assertCardAtPositionHasText(
        index: Int,
        expectedText: String,
        substring: Boolean = false,
    ) {
        awaitNodeDisplayed(hasTestTag(UiTestTags.TOKEN_LIST))
        onNodeWithTag(UiTestTags.TOKEN_LIST).performScrollToIndex(index)
        waitUntil(timeoutMillis = 5_000L) {
            val children = onNodeWithTag(UiTestTags.TOKEN_LIST).onChildren()
            val nodes = children.fetchSemanticsNodes()
            index < nodes.size && hasText(expectedText, substring = substring).matches(nodes[index])
        }
        onNodeWithTag(UiTestTags.TOKEN_LIST)
            .onChildren()[index]
            .assert(hasText(expectedText, substring = substring))
    }

    fun ComposeTestRule.clickCardAtPosition(index: Int) {
        awaitNodeDisplayed(hasTestTag(UiTestTags.TOKEN_LIST))
        onNodeWithTag(UiTestTags.TOKEN_LIST).performScrollToIndex(index)
        onNodeWithTag(UiTestTags.TOKEN_LIST).onChildren()[index].performClick()
    }

    fun awaitStoredTokens(
        scenario: ActivityScenario<MainActivity>,
        expectedCount: Int,
        timeoutMs: Long = 5_000L,
    ): List<OtpToken> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: List<OtpToken> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                latest = runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().getAll().first()
                }
            }
            if (latest.size == expectedCount) {
                return latest
            }
            Thread.sleep(50L)
        }
        assertThat(latest).hasSize(expectedCount)
        return latest
    }
}
