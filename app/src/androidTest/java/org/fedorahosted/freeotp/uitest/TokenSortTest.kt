package org.fedorahosted.freeotp.uitest

import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class TokenSortTest {

    @Test
    fun testSortMenuPositionedBetweenExportAndTheme_andCancelPreservesManualOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val titles = (0 until toolbar.menu.size()).map { toolbar.menu.getItem(it).title.toString() }
                val exportIdx = titles.indexOf(activity.getString(R.string.export_text))
                val sortIdx = titles.indexOf(activity.getString(R.string.sort))
                val themeIdx = titles.indexOf(activity.getString(R.string.theme))
                assertThat(exportIdx).isAtLeast(0)
                assertThat(sortIdx).isEqualTo(exportIdx + 1)
                assertThat(themeIdx).isEqualTo(sortIdx + 1)

                runBlocking {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    dao.deleteAll()
                    dao.insertAll(
                        listOf(
                            TestData.OTP_HOTP_TOKEN_1.copy(
                                id = 1L,
                                ordinal = 10L,
                                issuer = "Slack",
                                label = "zoe@slack.com"
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 2L,
                                ordinal = 20L,
                                issuer = "Amazon",
                                label = "amy@amazon.com"
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 3L,
                                ordinal = 30L,
                                issuer = "GitHub",
                                label = "mike@github.com"
                            )
                        )
                    )
                }
            }
            TestWaitUtil.awaitRecyclerItemCount(R.id.token_list, 3)

            // Select Sort -> Sort by Account Ascending -> Cancel keeps manual order [Slack, Amazon, GitHub]
            openSortSubmenu()
            onView(withText(R.string.sort_by_account_asc)).perform(click())
            onView(withText(R.string.sort_tokens_warning_title)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.sort_tokens_warning_message)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.cancel_text)).inRoot(isDialog()).perform(click())

            assertTokenIssuerOrder("Slack", "Amazon", "GitHub")

            // 1. Sort by Account Ascending -> [amy@amazon.com, mike@github.com, zoe@slack.com]
            openSortSubmenu()
            onView(withText(R.string.sort_by_account_asc)).perform(click())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            assertTokenAccountOrder("amy@amazon.com", "mike@github.com", "zoe@slack.com")

            // 2. Sort by Account Descending -> [zoe@slack.com, mike@github.com, amy@amazon.com]
            openSortSubmenu()
            onView(withText(R.string.sort_by_account_desc)).perform(click())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            assertTokenAccountOrder("zoe@slack.com", "mike@github.com", "amy@amazon.com")

            // 3. Sort by Issuer Ascending -> [Amazon, GitHub, Slack]
            openSortSubmenu()
            onView(withText(R.string.sort_by_issuer_asc)).perform(click())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            assertTokenIssuerOrder("Amazon", "GitHub", "Slack")

            // 4. Sort by Issuer Descending -> [Slack, GitHub, Amazon]
            openSortSubmenu()
            onView(withText(R.string.sort_by_issuer_desc)).perform(click())
            onView(withText(R.string.ok_text)).inRoot(isDialog()).perform(click())
            assertTokenIssuerOrder("Slack", "GitHub", "Amazon")

            scenario.onActivity { activity ->
                val persistedIssuers = runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().getAll().first().map { it.issuer }
                }
                assertThat(persistedIssuers).containsExactly("Slack", "GitHub", "Amazon").inOrder()
            }
        }
    }

    private fun openSortSubmenu() {
        openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
        onView(withText(R.string.sort)).perform(click())
    }

    private fun assertTokenIssuerOrder(vararg expectedIssuers: String) {
        expectedIssuers.forEachIndexed { idx, issuer ->
            TestWaitUtil.awaitViewMatching(
                withId(R.id.token_list),
                RecyclerViewAssertion.atPosition(idx, hasDescendant(withText(issuer)))
            )
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(idx, scrollTo()))
                .check(matches(RecyclerViewAssertion.atPosition(idx, hasDescendant(withText(issuer)))))
        }
    }

    private fun assertTokenAccountOrder(vararg expectedAccounts: String) {
        expectedAccounts.forEachIndexed { idx, account ->
            TestWaitUtil.awaitViewMatching(
                withId(R.id.token_list),
                RecyclerViewAssertion.atPosition(idx, hasDescendant(withText(account)))
            )
            onView(withId(R.id.token_list))
                .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(idx, scrollTo()))
                .check(matches(RecyclerViewAssertion.atPosition(idx, hasDescendant(withText(account)))))
        }
    }
}
