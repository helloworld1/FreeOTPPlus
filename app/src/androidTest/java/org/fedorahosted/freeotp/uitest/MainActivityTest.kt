package org.fedorahosted.freeotp.uitest

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class MainActivityTest {
    @get:Rule
    var activityRule = activityScenarioRule<MainActivity>()

    @Test
    fun testListing() {
        populateTestData()
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("github.com"))))
            .check(matches(hasDescendant(withText("github account 1"))))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(1, scrollTo()))
            .check(matches(hasDescendant(withText("microsoft.com"))))
            .check(matches(hasDescendant(withText("microsoft account 1"))))
    }

    @Test
    fun testInsertToken() {
        openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext);
        onView(withText(R.string.add_token))
            .perform(click())
        onView(withId(R.id.issuer))
            .perform(typeText("issuer1"), closeSoftKeyboard())
        onView(withId(R.id.label))
            .perform(typeText("account1"), closeSoftKeyboard())
        onView(withId(R.id.secret))
            .perform(typeText("abcd5432"))
        onView(withId(R.id.add))
            .perform(click())
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("issuer1"))))
            .check(matches(hasDescendant(withText("account1"))))
    }

    @Test
    fun testEditToken() {
        populateTestData()

        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0,
                RecyclerViewAction.clickChildViewWithId(R.id.menu)))
        onView(withText(R.string.edit))
            .perform(click())

        onView(withId(R.id.issuer))
            .perform(replaceText("new issuer"))
        onView(withId(R.id.label))
            .perform(replaceText("new account"))
        onView(withId(R.id.save))
            .perform(click())
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("new issuer"))))
            .check(matches(hasDescendant(withText("new account"))))
    }

    @Test
    fun testDeleteToken() {
        populateTestData()

        onView(withId(R.id.token_list))
            .check(RecyclerViewAssertion.childrenCount(2))

        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0,
                RecyclerViewAction.clickChildViewWithId(R.id.menu)))
        onView(withText(R.string.delete))
            .perform(click())
        onView(withId(R.id.delete))
            .perform(click())

        onView(withId(R.id.token_list))
            .check(RecyclerViewAssertion.childrenCount(1))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("microsoft.com"))))
            .check(matches(hasDescendant(withText("microsoft account 1"))))
    }

    @Test
    fun testTokenClickRevealHotp() {
        populateTestData()
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("github.com"))))
            .check(matches(hasDescendant(withText("github account 1"))))
            .check(matches(hasDescendant(withText("248759"))))
    }

    @Test
    fun testSearchToken() {
        populateTestData()
        onView(withId(R.id.search_view))
            .perform(click())
        onView(withId(R.id.search_view))
            .perform(SearchViewAction.typeSearchViewText("Microsoft"))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("microsoft.com"))))
            .check(matches(hasDescendant(withText("microsoft account 1"))))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, click()))
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