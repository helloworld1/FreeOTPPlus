package org.fedorahosted.freeotp.module

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.google.zxing.qrcode.QRCodeReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import org.fedorahosted.freeotp.FreeOtpPlusApplication
import javax.inject.Singleton

@Module
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun bindContext(application: FreeOtpPlusApplication): Context
    @Module
    companion object {
        @Singleton
        @JvmStatic
        @Provides
        fun sharedPreference(context: Context): SharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context)

        @Singleton
        @JvmStatic
        @Provides
        fun qrCodeReader(): QRCodeReader = QRCodeReader()
    }
}