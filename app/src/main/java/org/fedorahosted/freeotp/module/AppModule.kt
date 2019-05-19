package org.fedorahosted.freeotp.module

import android.content.Context
import dagger.Binds
import dagger.Module
import org.fedorahosted.freeotp.FreeOtpPlusApplication
import javax.inject.Singleton

@Module
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindContext(application: FreeOtpPlusApplication): Context;
}