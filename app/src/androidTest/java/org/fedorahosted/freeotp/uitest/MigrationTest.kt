package org.fedorahosted.freeotp.uitest

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.launchActivity
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class MigrationTest {
    private var activityScenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation()
            .targetContext
        val prefs: SharedPreferences = context.getSharedPreferences(
            "tokens", Context.MODE_PRIVATE)
        val globalPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putString("microsoft.com:token1totp", "{\"algo\":\"SHA1\",\"counter\":0,\"digits\":6,\"issuerExt\":\"microsoft.com\",\"label\":\"account1totp\",\"period\":30,\"secret\":[0,0,13,111,-99],\"type\":\"TOTP\"}")
            .putString("github.com:token2hotp", "{\"algo\":\"SHA256\",\"counter\":0,\"digits\":6,\"issuerExt\":\"github.com\",\"label\":\"account2hotp\",\"period\":30,\"secret\":[8,66,29,111,-99],\"type\":\"HOTP\"}")
            .putString("tokenOrder", "[\"microsoft.com:token1totp\",\"github.com:token2hotp\"]")
            .commit()

        globalPrefs.edit().putBoolean("tokenMigrated", false)
            .commit()
        activityScenario = launchActivity()
    }

    @Test
    fun testTokenContentAndOrder() {
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
            .check(matches(hasDescendant(withText("account1totp"))))
            .check(matches(hasDescendant(withText("microsoft.com"))))
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(1, scrollTo()))
            .check(matches(hasDescendant(withText("account2hotp"))))
            .check(matches(hasDescendant(withText("github.com"))))
    }

    @Test
    fun testTotpToken() {
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0,
                RecyclerViewAction.clickChildViewWithId(R.id.menu)))
        onView(withText(R.string.edit))
            .perform(click())
        onView(withId(R.id.issuer))
            .check(matches(withText("microsoft.com")))
        onView(withId(R.id.label))
            .check(matches(withText("account1totp")))
        onView(withId(R.id.algorithm))
            .check(matches(withText("SHA1")))
        onView(withId(R.id.digits))
            .check(matches(withText("6")))
        onView(withId(R.id.secret))
            .check(matches(withText("AAAA2345")))
    }

    @Test
    fun testHotpToken() {
        onView(withId(R.id.token_list))
            .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(1,
                RecyclerViewAction.clickChildViewWithId(R.id.menu)))
        onView(withText(R.string.edit))
            .perform(click())
        onView(withId(R.id.issuer))
            .check(matches(withText("github.com")))
        onView(withId(R.id.label))
            .check(matches(withText("account2hotp")))
        onView(withId(R.id.algorithm))
            .check(matches(withText("SHA256")))
        onView(withId(R.id.digits))
            .check(matches(withText("6")))
        onView(withId(R.id.secret))
            .check(matches(withText("BBBB2345")))
    }

    @After
    fun cleanUp() {
        activityScenario?.close()
    }
}
