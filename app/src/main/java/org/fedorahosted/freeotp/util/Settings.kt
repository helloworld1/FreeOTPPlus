package org.fedorahosted.freeotp.util

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
import javax.inject.Inject
import javax.inject.Singleton

internal const val DARK_MODE_KEY = "darkMode"
internal const val THEME_MODE_KEY = "themeMode"
internal const val SELECTED_CATEGORY_KEY = "selectedCategory"
private const val COPY_TO_CLIPBOARD_KEY = "copyToClipboard"
private const val PREF_UNCATEGORIZED = "uncategorized"
private const val PREF_CATEGORY_PREFIX = "category:"
internal const val CATEGORY_UNCATEGORIZED_VALUE = "uncategorized_special_value"

enum class ThemeMode(val prefValue: String, val nightMode: Int) {
    SYSTEM("SYSTEM", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("LIGHT", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("DARK", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromPrefValue(value: String): ThemeMode? {
            return entries.firstOrNull { it.prefValue == value }
        }
    }
}

@Singleton
class Settings @Inject constructor(val sharedPreferences: SharedPreferences) {
    var themeMode: ThemeMode
        get() {
            val raw = sharedPreferences.getString(THEME_MODE_KEY, null)
            if (raw != null) {
                ThemeMode.fromPrefValue(raw)?.let { return it }
            }
            return if (sharedPreferences.getBoolean(DARK_MODE_KEY, false)) {
                ThemeMode.DARK
            } else {
                ThemeMode.SYSTEM
            }
        }
        set(value) {
            sharedPreferences.edit()
                .putString(THEME_MODE_KEY, value.prefValue)
                .putBoolean(DARK_MODE_KEY, value == ThemeMode.DARK)
                .apply()
        }

    var darkMode: Boolean
        get() = themeMode == ThemeMode.DARK
        set(value) {
            themeMode = if (value) ThemeMode.DARK else ThemeMode.SYSTEM
        }

    var copyToClipboard: Boolean
        get() = sharedPreferences.getBoolean(COPY_TO_CLIPBOARD_KEY, true)
        set(value) = sharedPreferences.edit().putBoolean(COPY_TO_CLIPBOARD_KEY, value).apply()

    var authMode: AuthMode
        get() {
            val raw = sharedPreferences.getString(TokenCryptoManager.AUTH_MODE_KEY, null)
            if (raw != null) {
                return runCatching { AuthMode.valueOf(raw) }.getOrDefault(AuthMode.NONE)
            }
            return if (sharedPreferences.getBoolean(TokenCryptoManager.REQUIRE_AUTHENTICATION_KEY, false)) {
                AuthMode.APP_LAUNCH
            } else {
                AuthMode.NONE
            }
        }
        set(value) {
            sharedPreferences.edit()
                .putString(TokenCryptoManager.AUTH_MODE_KEY, value.name)
                .putBoolean(TokenCryptoManager.REQUIRE_AUTHENTICATION_KEY, value != AuthMode.NONE)
                .apply()
        }

    var requireAuthentication: Boolean
        get() = authMode != AuthMode.NONE
        set(value) {
            authMode = if (value) AuthMode.APP_LAUNCH else AuthMode.NONE
        }

    var selectedCategory: String?
        get() {
            val raw = sharedPreferences.getString(SELECTED_CATEGORY_KEY, null) ?: return null
            return when {
                raw == PREF_UNCATEGORIZED -> CATEGORY_UNCATEGORIZED_VALUE
                raw.startsWith(PREF_CATEGORY_PREFIX) ->
                    raw.removePrefix(PREF_CATEGORY_PREFIX).takeIf { it.isNotBlank() }
                else -> raw.takeIf { it.isNotBlank() }
            }
        }
        set(value) {
            val editor = sharedPreferences.edit()
            when {
                value.isNullOrBlank() -> editor.remove(SELECTED_CATEGORY_KEY)
                value == CATEGORY_UNCATEGORIZED_VALUE -> editor.putString(SELECTED_CATEGORY_KEY, PREF_UNCATEGORIZED)
                else -> editor.putString(SELECTED_CATEGORY_KEY, "$PREF_CATEGORY_PREFIX$value")
            }
            editor.apply()
        }
}
