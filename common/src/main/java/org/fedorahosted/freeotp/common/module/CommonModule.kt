package org.fedorahosted.freeotp.common.module

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.fedorahosted.freeotp.common.encryption.EncryptDecrypt
import org.fedorahosted.freeotp.common.util.Settings
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CommonModule {

    @Singleton
    @Provides
    fun encryptDecrypt(@ApplicationContext context: Context) = EncryptDecrypt(context)

    @Singleton
    @Provides
    fun settings(@ApplicationContext context: Context) = Settings(context.getSharedPreferences("tokens",Context.MODE_PRIVATE))

}
