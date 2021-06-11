package org.fedorahosted.freeotp.data.module

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.room.Room
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Singleton
    @Provides
    fun database(@ApplicationContext context:Context)
            = Room.databaseBuilder(context, OtpTokenDatabase::class.java, "otp-token-db")
        .build()

    @Singleton
    @Provides
    fun gson() = Gson()
}
