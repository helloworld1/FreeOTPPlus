package org.fedorahosted.freeotp.util

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
import javax.inject.Inject
import javax.inject.Singleton

internal const val DARK_MODE_KEY = "darkMode"
internal const val THEME_MODE_KEY = "themeMode"
internal const val SELECTED_CATEGORY_KEY = "selectedCategory"
internal const val ENABLE_BIOMETRIC_UNLOCK_KEY = "enableBiometricUnlock"
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
    private val _themeModeFlow = MutableStateFlow(readThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    private val _authModeFlow = MutableStateFlow(readAuthMode())
    val authModeFlow: StateFlow<AuthMode> = _authModeFlow.asStateFlow()

    private val _copyToClipboardFlow = MutableStateFlow(readCopyToClipboard())
    val copyToClipboardFlow: StateFlow<Boolean> = _copyToClipboardFlow.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            null -> refreshFlows()
            THEME_MODE_KEY, DARK_MODE_KEY -> _themeModeFlow.value = readThemeMode()
            TokenCryptoManager.AUTH_MODE_KEY, TokenCryptoManager.REQUIRE_AUTHENTICATION_KEY ->
                _authModeFlow.value = readAuthMode()
            COPY_TO_CLIPBOARD_KEY -> _copyToClipboardFlow.value = readCopyToClipboard()
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun refreshFlows() {
        _themeModeFlow.value = readThemeMode()
        _authModeFlow.value = readAuthMode()
        _copyToClipboardFlow.value = readCopyToClipboard()
    }

    private fun readThemeMode(): ThemeMode {
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

    private fun readCopyToClipboard(): Boolean {
        return sharedPreferences.getBoolean(COPY_TO_CLIPBOARD_KEY, true)
    }

    private fun readAuthMode(): AuthMode {
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

    var themeMode: ThemeMode
        get() = readThemeMode()
        set(value) {
            sharedPreferences.edit()
                .putString(THEME_MODE_KEY, value.prefValue)
                .putBoolean(DARK_MODE_KEY, value == ThemeMode.DARK)
                .apply()
            _themeModeFlow.value = value
        }

    var darkMode: Boolean
        get() = themeMode == ThemeMode.DARK
        set(value) {
            themeMode = if (value) ThemeMode.DARK else ThemeMode.SYSTEM
        }

    var copyToClipboard: Boolean
        get() = readCopyToClipboard()
        set(value) {
            sharedPreferences.edit().putBoolean(COPY_TO_CLIPBOARD_KEY, value).apply()
            _copyToClipboardFlow.value = value
        }

    var authMode: AuthMode
        get() = readAuthMode()
        set(value) {
            sharedPreferences.edit()
                .putString(TokenCryptoManager.AUTH_MODE_KEY, value.name)
                .putBoolean(TokenCryptoManager.REQUIRE_AUTHENTICATION_KEY, value != AuthMode.NONE)
                .apply()
            _authModeFlow.value = value
        }

    var requireAuthentication: Boolean
        get() = authMode != AuthMode.NONE
        set(value) {
            authMode = if (value) AuthMode.APP_LAUNCH else AuthMode.NONE
        }

    var enableBiometricUnlock: Boolean
        get() = sharedPreferences.getBoolean(ENABLE_BIOMETRIC_UNLOCK_KEY, true)
        set(value) {
            sharedPreferences.edit().putBoolean(ENABLE_BIOMETRIC_UNLOCK_KEY, value).apply()
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
