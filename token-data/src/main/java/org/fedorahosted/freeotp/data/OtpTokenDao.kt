package org.fedorahosted.freeotp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull

@Dao
interface OtpTokenDao {

    @Query("select * from otp_tokens")
    fun getAll(): Flow<List<OtpToken>>

    @Query("select * from otp_tokens where id = :id")
    fun get(id: Int): Flow<OtpToken>

    @Query("delete from otp_tokens where id = :id")
    suspend fun deleteById(id: Int): Void

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(otpTokenList: List<OtpToken>)

    @Query("update otp_tokens set ordinal = :ordinal where id = :id")
    suspend fun updateOrdinal(id: Int, ordinal: Int)

    @Transaction
    suspend fun move(tokenId1: Int, tokenId2: Int) {
        val token1 = get(tokenId1).firstOrNull() ?: return
        val token2 = get(tokenId2).firstOrNull() ?: return

        updateOrdinal(tokenId1, token2.ordinal)
        updateOrdinal(tokenId2, token1.ordinal)
    }
}