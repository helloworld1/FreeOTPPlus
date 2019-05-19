package org.fedorahosted.freeotp.module

import dagger.Module
import dagger.android.ContributesAndroidInjector
import org.fedorahosted.freeotp.MainActivity

@Module
abstract class ActivityModule {

    @ContributesAndroidInjector
    abstract fun contributeMainActivityInjector(): MainActivity

}
