package org.fedorahosted.freeotp.module

import dagger.Component
import dagger.android.AndroidInjector
import dagger.android.support.AndroidSupportInjectionModule;
import org.fedorahosted.freeotp.FreeOtpPlusApplication
import javax.inject.Singleton

@Component(modules = [AndroidSupportInjectionModule::class, AppModule::class, ActivityModule::class])
@Singleton
interface AppComponent: AndroidInjector<FreeOtpPlusApplication> {
     @Component.Factory
     abstract class Builder: AndroidInjector.Factory<FreeOtpPlusApplication>
}