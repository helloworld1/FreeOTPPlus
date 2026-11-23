package org.fedorahosted.freeotp.data.module

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Singleton
    @Provides
    fun database(@ApplicationContext context: Context): OtpTokenDatabase =
        Room.databaseBuilder(context, OtpTokenDatabase::class.java, "otp-token-db")
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(
                OtpTokenDatabase.MIGRATION_1_2,
                OtpTokenDatabase.MIGRATION_2_3,
                OtpTokenDatabase.MIGRATION_3_4
            )
            .build()

    @Singleton
    @Provides
    fun gson(): Gson = Gson()
}
