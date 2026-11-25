package org.fedorahosted.freeotp.util

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsThemeModeTest {

    @Test
    fun defaultUnsetPreferences_resolvesToSystem() {
        val prefs = InMemorySharedPreferences()
        val settings = Settings(prefs)

        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        assertThat(settings.darkMode).isFalse()
    }

    @Test
    fun legacyDarkModeFalseWithoutThemeMode_upgradesToSystem() {
        val prefs = InMemorySharedPreferences().apply {
            edit().putBoolean(DARK_MODE_KEY, false).apply()
        }
        val settings = Settings(prefs)

        assertThat(prefs.contains(THEME_MODE_KEY)).isFalse()
        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        assertThat(settings.darkMode).isFalse()
    }

    @Test
    fun legacyDarkModeTrueWithoutThemeMode_upgradesToDark() {
        val prefs = InMemorySharedPreferences().apply {
            edit().putBoolean(DARK_MODE_KEY, true).apply()
        }
        val settings = Settings(prefs)

        assertThat(prefs.contains(THEME_MODE_KEY)).isFalse()
        assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
        assertThat(settings.darkMode).isTrue()
    }

    @Test
    fun settingSystemLightDark_persistsThemeModeAndKeepsLegacyBooleanSynced() {
        val prefs = InMemorySharedPreferences()
        val settings = Settings(prefs)

        settings.themeMode = ThemeMode.LIGHT
        assertThat(settings.themeMode).isEqualTo(ThemeMode.LIGHT)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
        assertThat(settings.darkMode).isFalse()
        assertThat(prefs.getString(THEME_MODE_KEY, null)).isEqualTo("LIGHT")
        assertThat(prefs.getBoolean(DARK_MODE_KEY, true)).isFalse()

        settings.themeMode = ThemeMode.DARK
        assertThat(settings.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
        assertThat(settings.darkMode).isTrue()
        assertThat(prefs.getString(THEME_MODE_KEY, null)).isEqualTo("DARK")
        assertThat(prefs.getBoolean(DARK_MODE_KEY, false)).isTrue()

        settings.themeMode = ThemeMode.SYSTEM
        assertThat(settings.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(settings.themeMode.nightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        assertThat(settings.darkMode).isFalse()
        assertThat(prefs.getString(THEME_MODE_KEY, null)).isEqualTo("SYSTEM")
        assertThat(prefs.getBoolean(DARK_MODE_KEY, true)).isFalse()
    }

    @Test
    fun unknownThemeModeValue_fallsBackToLegacyDarkModeBoolean() {
        val prefsUnchecked = InMemorySharedPreferences().apply {
            edit().putString(THEME_MODE_KEY, "UNKNOWN_VALUE").putBoolean(DARK_MODE_KEY, false).apply()
        }
        assertThat(Settings(prefsUnchecked).themeMode).isEqualTo(ThemeMode.SYSTEM)

        val prefsChecked = InMemorySharedPreferences().apply {
            edit().putString(THEME_MODE_KEY, "UNKNOWN_VALUE").putBoolean(DARK_MODE_KEY, true).apply()
        }
        assertThat(Settings(prefsChecked).themeMode).isEqualTo(ThemeMode.DARK)
    }

    @Test
    fun selectedCategory_persistsCategoryUncategorizedAndClearsForAllCategoriesWithoutCollision() {
        val prefs = InMemorySharedPreferences()
        val settings = Settings(prefs)

        assertThat(settings.selectedCategory).isNull()

        // Named category
        settings.selectedCategory = "Work"
        assertThat(settings.selectedCategory).isEqualTo("Work")
        assertThat(prefs.getString(SELECTED_CATEGORY_KEY, null)).isEqualTo("category:Work")

        // Built-in Uncategorized sentinel
        settings.selectedCategory = CATEGORY_UNCATEGORIZED_VALUE
        assertThat(settings.selectedCategory).isEqualTo(CATEGORY_UNCATEGORIZED_VALUE)
        assertThat(prefs.getString(SELECTED_CATEGORY_KEY, null)).isEqualTo("uncategorized")

        // User-created category literally named "uncategorized" does not collide with sentinel
        settings.selectedCategory = "uncategorized"
        assertThat(settings.selectedCategory).isEqualTo("uncategorized")
        assertThat(prefs.getString(SELECTED_CATEGORY_KEY, null)).isEqualTo("category:uncategorized")

        // Resetting to All categories (null / blank) removes preference
        settings.selectedCategory = null
        assertThat(settings.selectedCategory).isNull()
        assertThat(prefs.contains(SELECTED_CATEGORY_KEY)).isFalse()
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            if (data.containsKey(key)) data[key] as? String else defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            if (data.containsKey(key)) data[key] as? MutableSet<String> else defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { staged[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { staged[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { staged[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                for ((k, v) in staged) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
