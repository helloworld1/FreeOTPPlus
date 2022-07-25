package org.fedorahosted.freeotp.data

import android.net.Uri
import android.util.Log
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.common.encryption.EncryptDecrypt
import org.fedorahosted.freeotp.common.encryption.EncryptionType
import org.fedorahosted.freeotp.common.util.Settings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtpTokenService @Inject constructor(
    val database: OtpTokenDatabase,
    val encryptDecrypt: EncryptDecrypt,
    val settings: Settings
) {

    suspend fun deleteById(id: Long) {
        database.otpTokenDao().deleteById(id)
    }

    suspend fun update(otpToken: OtpToken) {
        database.otpTokenDao().update(otpToken)
    }

    suspend fun update(otpTokenList: List<OtpToken>) {
        database.otpTokenDao().update(otpTokenList)
    }

    fun getLastOrdinal(): Long? {
        return database.otpTokenDao().getLastOrdinal()
    }

    fun getDecrypted(id: Long): Flow<OtpToken?> {
        return database.otpTokenDao().get(id).mapLatest { token ->
            if (token != null) {
                decryptToken(token, settings.password)
            }
            token
        }
    }

    fun getAllDecrypted(password: String? = null): Flow<List<OtpToken>> {
        return database.otpTokenDao().getAll().mapLatest { tokens ->
            tokens.map {
                decryptToken(it, password ?: settings.password)
            }
        }
    }

    suspend fun insertEncrypted(otpToken: OtpToken) {
        database.otpTokenDao().insert(encryptToken(otpToken, settings.password))
    }

    suspend fun insertAllEncrypted(otpTokens: List<OtpToken>, password: String?) {
        database.otpTokenDao().insertAll(otpTokens.map { encryptToken(it, password) })
    }

    @Transaction
    suspend fun move(tokenId1: Long, tokenId2: Long) {
        withContext(Dispatchers.IO) {
            val token1 = getDecrypted(tokenId1).first()
            val token2 = getDecrypted(tokenId2).first()

            if (token1 == null || token2 == null) {
                return@withContext
            }

            database.otpTokenDao().updateOrdinal(tokenId1, token2.ordinal)
            database.otpTokenDao().updateOrdinal(tokenId2, token1.ordinal)
        }
    }

    /**
     * This incrementCounter won't trigger Flow collect by using raw query
     * We do not want increment count triggering flow because it can refresh the token
     */
    suspend fun incrementCounter(id: Long) {
        database.otpTokenDao().incrementCounterRaw(
            SimpleSQLiteQuery(
                "update otp_tokens set counter = counter + 1 where id = ?",
                arrayOf(id)
            )
        )
    }


    suspend fun decryptToken(otpToken: OtpToken, password: String?): OtpToken {
        return otpToken.apply {
            secret = encryptDecrypt.decrypt(secret, encryptionType, password)
        }
    }

    suspend fun encryptToken(otpToken: OtpToken, password: String?): OtpToken {
        return otpToken.apply {
            secret = encryptDecrypt.encrypt(secret, encryptionType, password)
        }
    }

    fun createFromUri(uri: Uri): OtpToken {
        //TODO AES
        return OtpTokenFactory.createFromUri(
            uri,
            if (settings.requireAuthentication) EncryptionType.AES else EncryptionType.PLAIN_TEXT
        )
    }

    /**
     * Called when require authentication is turned off
     */
    suspend fun decryptAndUpdateAllTokens(password: String?) {
        val updatedTokens = getAllDecrypted(password).first().map { token ->
            token.copy(encryptionType = EncryptionType.PLAIN_TEXT)
        }
        update(updatedTokens)
    }

    /**
     * Called when password is changed
     */
    suspend fun reencryptAllTokens(oldPassword: String, newPassword: String) {
        val oldTokens = getAllDecrypted(oldPassword).first()
        val newTokens = oldTokens.map { token ->
            val tok = token.copy(encryptionType = EncryptionType.AES)
            encryptToken(tok, newPassword)
        }
        Log.d("test", "${oldTokens}")
        update(newTokens)
    }

}