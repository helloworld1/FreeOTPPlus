package org.fedorahosted.freeotp.uitest

import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuItemCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.android.material.color.MaterialColors
import com.google.common.truth.Truth.assertThat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class CategoryFilterTintTest {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TestDbEntryPoint {
        fun database(): OtpTokenDatabase
    }

    @Test
    fun testFilterIconTintedWhenCategoryOtherThanAllSelected() {
        val context = getInstrumentation().targetContext.applicationContext
        val db = EntryPointAccessors.fromApplication(context, TestDbEntryPoint::class.java).database()
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
            db.otpTokenDao().insert(TestData.OTP_HOTP_TOKEN_1.copy(id = 0L, ordinal = 0L, category = "Work"))
            db.otpTokenDao().insert(TestData.OTP_TOTP_TOKEN_2.copy(id = 0L, ordinal = 1L, category = null))
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Initially "All categories" is selected -> default control normal tint
            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedNormal = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorControlNormal)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedNormal)
            }

            // Select "Uncategorized" -> highlighted with colorPrimary
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.uncategorized)).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedActive = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedActive)
            }

            // Select custom category "Work" -> still highlighted with colorPrimary
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText("Work")).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedActive = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorPrimary)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedActive)
            }

            // Select "All categories" -> restored to colorControlNormal
            onView(withId(R.id.action_filter)).perform(click())
            onView(withText(R.string.all_categories)).perform(click())

            scenario.onActivity { activity ->
                val toolbar = activity.findViewById<Toolbar>(R.id.toolbar)
                val filterItem = toolbar.menu.findItem(R.id.action_filter)
                val expectedNormal = MaterialColors.getColor(toolbar, androidx.appcompat.R.attr.colorControlNormal)
                assertThat(MenuItemCompat.getIconTintList(filterItem)?.defaultColor).isEqualTo(expectedNormal)
            }
        }
    }
}
