package org.fedorahosted.freeotp.module

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.google.zxing.qrcode.QRCodeReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.FreeOtpPlusApplication
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @JvmStatic
    @Provides
    fun sharedPreference(@ApplicationContext context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    @Singleton
    @JvmStatic
    @Provides
    fun qrCodeReader(): QRCodeReader = QRCodeReader()

    @Singleton
    @JvmStatic
    @Provides
    fun executorService(): ExecutorService = Executors.newFixedThreadPool(4)
}