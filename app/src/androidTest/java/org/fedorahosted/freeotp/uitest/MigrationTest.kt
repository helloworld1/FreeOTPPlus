package org.fedorahosted.freeotp.uitest

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.launchActivity
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.legacy.TokenPersistence
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.TokenViewHolder
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
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

    @After
    fun cleanUp() {
        activityScenario?.close()
    }


}