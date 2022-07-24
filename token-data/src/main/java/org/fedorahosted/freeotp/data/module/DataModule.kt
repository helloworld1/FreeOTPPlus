package org.fedorahosted.freeotp.data.module

import android.content.Context
import androidx.room.Room
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenService
import org.fedorahosted.freeotp.common.encryption.EncryptDecrypt
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Singleton
    @Provides
    fun database(@ApplicationContext context: Context) =
        Room.databaseBuilder(context, OtpTokenDatabase::class.java, "otp-token-db")
            .build()


    @Singleton
    @Provides
    fun otpTokenService(@ApplicationContext context: Context) =
        OtpTokenService(database(context), EncryptDecrypt(context))

    @Singleton
    @Provides
    fun gson() = Gson()
}
