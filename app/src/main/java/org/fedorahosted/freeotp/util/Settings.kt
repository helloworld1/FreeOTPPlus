package org.fedorahosted.freeotp.util

import android.content.SharedPreferences
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
}