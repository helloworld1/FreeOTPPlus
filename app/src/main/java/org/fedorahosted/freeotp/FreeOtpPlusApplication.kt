package org.fedorahosted.freeotp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasAndroidInjector
import org.fedorahosted.freeotp.module.DaggerAppComponent
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

class FreeOtpPlusApplication: Application(), HasAndroidInjector {
    @Inject lateinit var androidInjector: DispatchingAndroidInjector<Any>
    @Inject lateinit var settings: Settings

    override fun onCreate() {
        super.onCreate()
        DaggerAppComponent.factory().create(this).inject(this)
        if (settings.darkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
        }
    }

    override fun androidInjector(): AndroidInjector<Any> = androidInjector
}