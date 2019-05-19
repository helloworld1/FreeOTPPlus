package org.fedorahosted.freeotp.module

import dagger.Module
import dagger.android.ContributesAndroidInjector
import org.fedorahosted.freeotp.MainActivity
import org.fedorahosted.freeotp.add.AddActivity
import org.fedorahosted.freeotp.add.ScanActivity
import org.fedorahosted.freeotp.edit.DeleteActivity
import org.fedorahosted.freeotp.edit.EditActivity

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
    abstract fun contributeScanActivityInjector(): ScanActivity

}
