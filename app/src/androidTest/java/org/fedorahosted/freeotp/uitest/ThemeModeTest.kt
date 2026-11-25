package org.fedorahosted.freeotp.uitest

import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitNodeDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.DARK_MODE_KEY
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.THEME_MODE_KEY
import org.fedorahosted.freeotp.util.ThemeMode
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class ThemeModeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

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
            composeRule.onNode(radioRowMatcher(R.string.theme_light)).performClick()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.LIGHT)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = true, darkChecked = false)

            // Select Dark
            composeRule.onNode(radioRowMatcher(R.string.theme_dark)).performClick()
            assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
            assertThat(AppCompatDelegate.getDefaultNightMode()).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)

            openThemeSubmenu()
            assertThemeRadioStates(systemChecked = false, lightChecked = false, darkChecked = true)

            // Select System
            composeRule.onNode(radioRowMatcher(R.string.theme_system)).performClick()
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
        composeRule.openOverflowMenu()
        composeRule.onNodeWithText(string(R.string.theme)).performClick()
        composeRule.awaitNodeDisplayed(radioRowMatcher(R.string.theme_system))
    }

    private fun radioRowMatcher(titleRes: Int): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
            hasText(string(titleRes))
    }

    private fun assertThemeRadioStates(
        systemChecked: Boolean,
        lightChecked: Boolean,
        darkChecked: Boolean,
    ) {
        assertRadioChecked(R.string.theme_system, systemChecked)
        assertRadioChecked(R.string.theme_light, lightChecked)
        assertRadioChecked(R.string.theme_dark, darkChecked)
    }

    private fun assertRadioChecked(titleRes: Int, expectedChecked: Boolean) {
        val node = composeRule.onNode(radioRowMatcher(titleRes))
        if (expectedChecked) {
            node.assertIsSelected()
        } else {
            node.assertIsNotSelected()
        }
    }
}
