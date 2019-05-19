package org.fedorahosted.freeotp

import android.app.Activity
import android.app.Application
import androidx.fragment.app.Fragment
import dagger.android.AndroidInjector
import dagger.android.DispatchingAndroidInjector
import dagger.android.HasActivityInjector
import dagger.android.support.HasSupportFragmentInjector
import org.fedorahosted.freeotp.module.DaggerAppComponent
import javax.inject.Inject

class FreeOtpPlusApplication: Application(), HasActivityInjector, HasSupportFragmentInjector {
    @Inject lateinit var dispatchingActivityInjector: DispatchingAndroidInjector<Activity>;
    @Inject lateinit var dispatchingFragmentInjector: DispatchingAndroidInjector<Fragment>;

    override fun onCreate() {
        super.onCreate()
        DaggerAppComponent.factory().create(this).inject(this)
    }

    override fun activityInjector(): AndroidInjector<Activity> {
        return dispatchingActivityInjector
    }

    override fun supportFragmentInjector(): AndroidInjector<Fragment> {
        return dispatchingFragmentInjector
    }


}