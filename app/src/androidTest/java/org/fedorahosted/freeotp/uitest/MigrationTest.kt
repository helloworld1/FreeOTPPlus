package org.fedorahosted.freeotp.uitest

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.launchActivity
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class MigrationTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var activityScenario: ActivityScenario<MainActivity>? = null

    private fun string(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs: SharedPreferences = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val globalPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString(
                "microsoft.com:token1totp",
                "{\"algo\":\"SHA1\",\"counter\":0,\"digits\":6,\"issuerExt\":\"microsoft.com\",\"label\":\"account1totp\",\"period\":30,\"secret\":[0,0,13,111,-99],\"type\":\"TOTP\"}",
            )
            .putString(
                "github.com:token2hotp",
                "{\"algo\":\"SHA256\",\"counter\":15,\"digits\":6,\"issuerExt\":\"github.com\",\"label\":\"account2hotp\",\"period\":30,\"secret\":[8,66,29,111,-99],\"type\":\"HOTP\"}",
            )
            .putString("tokenOrder", "[\"microsoft.com:token1totp\",\"github.com:token2hotp\"]")
            .commit()

        globalPrefs.edit().putBoolean("tokenMigrated", false).commit()
        activityScenario = launchActivity()
        composeRule.awaitTokenCardCount(2)
    }

    @Test
    fun testTokenContentAndOrder() {
        composeRule.assertCardAtPositionHasText(0, "account1totp")
        composeRule.assertCardAtPositionHasText(0, "microsoft.com")
        composeRule.assertCardAtPositionHasText(1, "account2hotp")
        composeRule.assertCardAtPositionHasText(1, "github.com")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyPrefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)
        val legacyXml = File(context.applicationInfo.dataDir, "shared_prefs/tokens.xml")
        assertThat(legacyPrefs.all).isEmpty()
        assertThat(legacyXml.exists()).isFalse()
    }

    @Test
    fun testLeftoverLegacyTokensPurgedWhenAlreadyMigrated() {
        composeRule.assertCardAtPositionHasText(0, "account1totp")
        activityScenario?.close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyPrefs = context.getSharedPreferences("tokens", Context.MODE_PRIVATE)
        @Suppress("DEPRECATION")
        val globalPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        legacyPrefs.edit()
            .putString(
                "leftover:secret",
                "{\"algo\":\"SHA1\",\"counter\":0,\"digits\":6,\"issuerExt\":\"leftover\",\"label\":\"secret\",\"period\":30,\"secret\":[0,0,13,111,-99],\"type\":\"TOTP\"}",
            )
            .putString("tokenOrder", "[\"leftover:secret\"]")
            .commit()
        assertThat(globalPrefs.getBoolean("tokenMigrated", false)).isTrue()

        val legacyXml = File(context.applicationInfo.dataDir, "shared_prefs/tokens.xml")
        assertThat(legacyXml.exists()).isTrue()

        activityScenario = launchActivity()
        composeRule.awaitTokenCardCount(2)

        val deadline = System.currentTimeMillis() + 5_000L
        while (legacyXml.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertThat(context.getSharedPreferences("tokens", Context.MODE_PRIVATE).all).isEmpty()
        assertThat(legacyXml.exists()).isFalse()
    }

    @Test
    fun testTotpToken() {
        val firstTokenId = getMigratedTokenIdAt(0)
        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(firstTokenId)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).assert(hasText("microsoft.com"))
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).assert(hasText("account1totp"))
        composeRule.onNodeWithTag(UiTestTags.DETAIL_ALGORITHM).performScrollTo().assertTextEquals("SHA1")
        composeRule.onNodeWithTag(UiTestTags.DETAIL_DIGITS).performScrollTo().assertTextEquals("6")
        composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
            .performScrollTo()
            .assertTextEquals(string(R.string.click_to_display))
            .performClick()
        composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET).assertTextEquals("AAAA2345")
    }

    @Test
    fun testHotpToken() {
        val secondTokenId = getMigratedTokenIdAt(1)
        composeRule.onNodeWithTag(UiTestTags.TOKEN_LIST).onChildren()[1].assert(hasText("15"))

        composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(secondTokenId)).performClick()
        composeRule.onNodeWithText(string(R.string.edit)).performClick()

        composeRule.awaitTagDisplayed(UiTestTags.ISSUER_INPUT)
        composeRule.onNodeWithTag(UiTestTags.ISSUER_INPUT).assert(hasText("github.com"))
        composeRule.onNodeWithTag(UiTestTags.LABEL_INPUT).assert(hasText("account2hotp"))
        composeRule.onNodeWithTag(UiTestTags.DETAIL_ALGORITHM).performScrollTo().assertTextEquals("SHA256")
        composeRule.onNodeWithTag(UiTestTags.DETAIL_DIGITS).performScrollTo().assertTextEquals("6")
        composeRule.onNodeWithTag(UiTestTags.DETAIL_INTERVAL_OR_COUNTER).performScrollTo().assertTextEquals("15")
        composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET)
            .performScrollTo()
            .assertTextEquals(string(R.string.click_to_display))
            .performClick()
        composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET).assertTextEquals("BBBB2345")
    }

    private fun getMigratedTokenIdAt(index: Int): Long {
        var tokenId = 0L
        activityScenario?.onActivity { activity ->
            tokenId = runBlocking {
                activity.otpTokenDatabase.otpTokenDao().getAll().first()[index].id
            }
        }
        return tokenId
    }

    @After
    fun cleanUp() {
        activityScenario?.onActivity { activity ->
            runBlocking {
                activity.otpTokenDatabase.otpTokenDao().deleteAll()
            }
        }
        activityScenario?.close()
    }
}
