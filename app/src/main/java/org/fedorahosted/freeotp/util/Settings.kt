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
internal const val TOKEN_TIMER_STYLE_KEY = "tokenTimerStyle"
internal const val SELECTED_CATEGORY_KEY = "selectedCategory"
internal const val ENABLE_BIOMETRIC_UNLOCK_KEY = "enableBiometricUnlock"
internal const val COPY_TO_CLIPBOARD_KEY = "copyToClipboard"
internal const val COPY_TO_CLIPBOARD_MODE_KEY = "copyToClipboardMode"
private const val PREF_UNCATEGORIZED = "uncategorized"
private const val PREF_CATEGORY_PREFIX = "category:"
internal const val CATEGORY_UNCATEGORIZED_VALUE = "uncategorized_special_value"

enum class CopyToClipboardMode(val prefValue: String) {
    OFF("OFF"),
    ON_REVEAL("ON_REVEAL"),
    TAP_REVEALED("TAP_REVEALED");

    companion object {
        fun fromPrefValue(value: String): CopyToClipboardMode? {
            return entries.firstOrNull { it.prefValue == value }
        }
    }
}

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

enum class TokenTimerStyle(val prefValue: String) {
    CIRCLE("CIRCLE"),
    PROGRESS_BAR("PROGRESS_BAR");

    companion object {
        fun fromPrefValue(value: String): TokenTimerStyle? {
            return entries.firstOrNull { it.prefValue == value }
        }
    }
}

@Singleton
class Settings @Inject constructor(val sharedPreferences: SharedPreferences) {
    private val _themeModeFlow = MutableStateFlow(readThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow.asStateFlow()

    private val _tokenTimerStyleFlow = MutableStateFlow(readTokenTimerStyle())
    val tokenTimerStyleFlow: StateFlow<TokenTimerStyle> = _tokenTimerStyleFlow.asStateFlow()

    private val _authModeFlow = MutableStateFlow(readAuthMode())
    val authModeFlow: StateFlow<AuthMode> = _authModeFlow.asStateFlow()

    private val _copyToClipboardModeFlow = MutableStateFlow(readCopyToClipboardMode())
    val copyToClipboardModeFlow: StateFlow<CopyToClipboardMode> = _copyToClipboardModeFlow.asStateFlow()

    private val _copyToClipboardFlow = MutableStateFlow(readCopyToClipboard())
    val copyToClipboardFlow: StateFlow<Boolean> = _copyToClipboardFlow.asStateFlow()

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            null -> refreshFlows()
            THEME_MODE_KEY, DARK_MODE_KEY -> _themeModeFlow.value = readThemeMode()
            TOKEN_TIMER_STYLE_KEY -> _tokenTimerStyleFlow.value = readTokenTimerStyle()
            TokenCryptoManager.AUTH_MODE_KEY, TokenCryptoManager.REQUIRE_AUTHENTICATION_KEY ->
                _authModeFlow.value = readAuthMode()
            COPY_TO_CLIPBOARD_MODE_KEY, COPY_TO_CLIPBOARD_KEY -> {
                _copyToClipboardModeFlow.value = readCopyToClipboardMode()
                _copyToClipboardFlow.value = readCopyToClipboard()
            }
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun refreshFlows() {
        _themeModeFlow.value = readThemeMode()
        _tokenTimerStyleFlow.value = readTokenTimerStyle()
        _authModeFlow.value = readAuthMode()
        _copyToClipboardModeFlow.value = readCopyToClipboardMode()
        _copyToClipboardFlow.value = readCopyToClipboard()
    }

    private fun readTokenTimerStyle(): TokenTimerStyle {
        val raw = sharedPreferences.getString(TOKEN_TIMER_STYLE_KEY, null)
        if (raw != null) {
            TokenTimerStyle.fromPrefValue(raw)?.let { return it }
        }
        return TokenTimerStyle.CIRCLE
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

    private fun readCopyToClipboardMode(): CopyToClipboardMode {
        val raw = sharedPreferences.getString(COPY_TO_CLIPBOARD_MODE_KEY, null)
        if (raw != null) {
            CopyToClipboardMode.fromPrefValue(raw)?.let { return it }
        }
        return if (sharedPreferences.getBoolean(COPY_TO_CLIPBOARD_KEY, true)) {
            CopyToClipboardMode.ON_REVEAL
        } else {
            CopyToClipboardMode.OFF
        }
    }

    private fun readCopyToClipboard(): Boolean {
        return readCopyToClipboardMode() != CopyToClipboardMode.OFF
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

    var tokenTimerStyle: TokenTimerStyle
        get() = readTokenTimerStyle()
        set(value) {
            sharedPreferences.edit()
                .putString(TOKEN_TIMER_STYLE_KEY, value.prefValue)
                .apply()
            _tokenTimerStyleFlow.value = value
        }

    var copyToClipboardMode: CopyToClipboardMode
        get() = readCopyToClipboardMode()
        set(value) {
            val enabled = value != CopyToClipboardMode.OFF
            sharedPreferences.edit()
                .putString(COPY_TO_CLIPBOARD_MODE_KEY, value.prefValue)
                .putBoolean(COPY_TO_CLIPBOARD_KEY, enabled)
                .apply()
            _copyToClipboardModeFlow.value = value
            _copyToClipboardFlow.value = enabled
        }

    var copyToClipboard: Boolean
        get() = copyToClipboardMode != CopyToClipboardMode.OFF
        set(value) {
            copyToClipboardMode = if (value) CopyToClipboardMode.ON_REVEAL else CopyToClipboardMode.OFF
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
