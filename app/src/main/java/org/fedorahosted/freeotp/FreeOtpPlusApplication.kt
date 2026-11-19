package org.fedorahosted.freeotp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import dagger.hilt.android.HiltAndroidApp
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@HiltAndroidApp
class FreeOtpPlusApplication : Application() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var appSessionManager: AppSessionManager

    private var screenshotMode = false

    fun applySecureFlagIfNeeded(window: Window?) {
        if (!(BuildConfig.DEBUG && screenshotMode)) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (settings.darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        registerActivityLifecycleCallbacks(appSessionManager)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Block screenshots and screen recording across all screens and dialogs by default;
                // only debug builds may opt into screenshot mode via the debug shortcut.
                if (BuildConfig.DEBUG && activity is MainActivity) {
                    screenshotMode = activity.intent?.extras?.getBoolean(MainActivity.SCREENSHOT_MODE_EXTRA) == true
                }
                applySecureFlagIfNeeded(activity.window)

                if (activity is FragmentActivity) {
                    activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                        object : FragmentManager.FragmentLifecycleCallbacks() {
                            override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                                if (f is DialogFragment) {
                                    applySecureFlagIfNeeded(f.dialog?.window)
                                }
                            }
                        },
                        true
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
