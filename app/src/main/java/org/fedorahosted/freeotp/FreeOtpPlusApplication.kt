package org.fedorahosted.freeotp

import android.app.Activity
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.support.HasSupportFragmentInjector
import org.fedorahosted.freeotp.module.DaggerAppComponent
import javax.inject.Inject

class FreeOtpPlusApplication: Application(), HasActivityInjector, HasSupportFragmentInjector {
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>
    @Inject lateinit var dispatchingFragmentInjector: DispatchingAndroidInjector<Fragment>
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

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun supportFragmentInjector(): AndroidInjector<Fragment> {
        return dispatchingFragmentInjector
    }


}