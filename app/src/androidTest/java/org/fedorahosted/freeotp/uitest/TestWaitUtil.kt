package org.fedorahosted.freeotp.uitest

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.ui.MainActivity
import org.hamcrest.Matcher

object TestWaitUtil {
    fun awaitViewMatching(
        viewMatcher: Matcher<View>,
        assertionMatcher: Matcher<View> = isDisplayed(),
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(viewMatcher).check(matches(assertionMatcher))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
            } catch (e: AssertionError) {
                lastError = e
            }
            Thread.sleep(50L)
        }
        if (lastError != null) throw lastError
        onView(viewMatcher).check(matches(assertionMatcher))
    }

    fun awaitRecyclerItemCount(
        recyclerViewId: Int,
        expectedCount: Int,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                onView(withId(recyclerViewId)).check(RecyclerViewAssertion.childrenCount(expectedCount))
                return
            } catch (e: NoMatchingViewException) {
                lastError = e
            } catch (e: AssertionError) {
                lastError = e
            }
            Thread.sleep(50L)
        }
        if (lastError != null) throw lastError
        onView(withId(recyclerViewId)).check(RecyclerViewAssertion.childrenCount(expectedCount))
    }

    fun awaitStoredTokens(
        scenario: ActivityScenario<MainActivity>,
        expectedCount: Int,
        timeoutMs: Long = 5_000L
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
