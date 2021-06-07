package org.fedorahosted.freeotp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@HiltAndroidApp
class FreeOtpPlusApplication: Application() {
    @Inject lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        if (settings.darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
        }
    }
}