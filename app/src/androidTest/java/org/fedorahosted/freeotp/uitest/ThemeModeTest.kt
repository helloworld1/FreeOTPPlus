package org.fedorahosted.freeotp.uitest

import android.preference.PreferenceManager
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.DARK_MODE_KEY
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.THEME_MODE_KEY
import org.fedorahosted.freeotp.util.ThemeMode
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class ThemeModeTest {

    @After
    fun tearDown() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().remove(THEME_MODE_KEY).remove(DARK_MODE_KEY).commit()
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
            onView(withText(R.string.theme_light)).perform(click())
            assertThat(settings.themeMode).isEqualTo(ThemeMode.LIGHT)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = true, darkChecked = false)

            // Select Dark
            onView(withText(R.string.theme_dark)).perform(click())
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = false, darkChecked = true)

            // Select System
            onView(withText(R.string.theme_system)).perform(click())
            assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = true, lightChecked = false, darkChecked = false)
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

    private fun openThemeSubmenu() {
        openActionBarOverflowOrOptionsMenu(getInstrumentation().targetContext)
        onView(withText(R.string.theme)).perform(click())
    }

    private fun assertThemeRadioStates(
        systemChecked: Boolean,
        lightChecked: Boolean,
        darkChecked: Boolean
    ) {
        assertRadioRowChecked(R.string.theme_system, systemChecked)
        assertRadioRowChecked(R.string.theme_light, lightChecked)
        assertRadioRowChecked(R.string.theme_dark, darkChecked)
    }

    private fun assertRadioRowChecked(titleRes: Int, expectedChecked: Boolean) {
        val checkStateMatcher = if (expectedChecked) isChecked() else isNotChecked()
        onView(
            allOf(
                isAssignableFrom(ListMenuItemView::class.java),
                hasDescendant(withText(titleRes))
            )
        ).check(
            matches(
                hasDescendant(
                    allOf(
                        isAssignableFrom(RadioButton::class.java),
                        checkStateMatcher
                    )
                )
            )
        )
    }
}
