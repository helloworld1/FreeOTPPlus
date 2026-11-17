package org.fedorahosted.freeotp.util

import android.content.SharedPreferences
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
import javax.inject.Inject
import javax.inject.Singleton

private const val DARK_MODE_KEY = "darkMode"
private const val COPY_TO_CLIPBOARD_KEY = "copyToClipboard"

@Singleton
class Settings @Inject constructor(val sharedPreferences: SharedPreferences) {
    var darkMode: Boolean
        get() = sharedPreferences.getBoolean(DARK_MODE_KEY, false)
        set(value) = sharedPreferences.edit().putBoolean(DARK_MODE_KEY, value).apply()

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
}
