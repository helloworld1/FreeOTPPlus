package org.fedorahosted.freeotp.module

import dagger.Module
import dagger.android.ContributesAndroidInjector
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.AddActivity
import org.fedorahosted.freeotp.ui.DeleteActivity
import org.fedorahosted.freeotp.ui.EditActivity
import org.fedorahosted.freeotp.ui.ScanTokenActivity

@Module
abstract class ActivityModule {

    @ContributesAndroidInjector
    abstract fun contributeMainActivityInjector(): MainActivity

    @ContributesAndroidInjector
    abstract fun contributeDeleteActivityInjector(): DeleteActivity

    @ContributesAndroidInjector
    abstract fun contributeEditActivityInjector(): EditActivity

    @ContributesAndroidInjector
    abstract fun contributeAddActivityInjector(): AddActivity

    @ContributesAndroidInjector
    abstract fun contributeScanTokenActivityInjector(): ScanTokenActivity
}
