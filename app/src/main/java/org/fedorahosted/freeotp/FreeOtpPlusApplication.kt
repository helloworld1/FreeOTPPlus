package org.fedorahosted.freeotp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@HiltAndroidApp
class FreeOtpPlusApplication: Application() {
    @Inject lateinit var settings: Settings

    private var screenshotMode = false

    override fun onCreate() {
        super.onCreate()
        if (settings.darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Block screenshots and screen recording across all screens by default;
                // only debug builds may opt into screenshot mode via the debug shortcut.
                if (BuildConfig.DEBUG && activity is MainActivity) {
                    screenshotMode = activity.intent?.extras?.getBoolean(MainActivity.SCREENSHOT_MODE_EXTRA) == true
                }
                if (!(BuildConfig.DEBUG && screenshotMode)) {
                    activity.window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
