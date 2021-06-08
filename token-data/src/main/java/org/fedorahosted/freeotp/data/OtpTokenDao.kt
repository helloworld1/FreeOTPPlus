package org.fedorahosted.freeotp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OtpTokenDao {

    @Query("select * from otp_tokens")
    fun getAll(): Flow<List<OtpToken>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(otpTokenList: List<OtpToken>)
}