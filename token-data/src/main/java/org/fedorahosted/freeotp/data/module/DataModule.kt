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
import org.fedorahosted.freeotp.common.module.CommonModule
import org.fedorahosted.freeotp.common.util.Settings
import javax.inject.Inject
import javax.inject.Singleton

@Module(includes = [CommonModule::class])
@InstallIn(SingletonComponent::class)
object DataModule {
    @Singleton
    @Provides
    fun database(@ApplicationContext context: Context) =
        Room.databaseBuilder(context, OtpTokenDatabase::class.java, "otp-token-db")
            .build()


    @Singleton
    @Provides
    fun gson() = Gson()
}
