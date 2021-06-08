package org.fedorahosted.freeotp.module

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import com.google.zxing.qrcode.QRCodeReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.data.module.DataModule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module(includes = [DataModule::class])
@InstallIn(SingletonComponent::class)
object AppModule {
    @Singleton
    @Provides
    fun sharedPreference(@ApplicationContext context: Context): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    @Singleton
    @Provides
    fun qrCodeReader(): QRCodeReader = QRCodeReader()

    @Singleton
    @Provides
    fun executorService(): ExecutorService = Executors.newFixedThreadPool(4)
}