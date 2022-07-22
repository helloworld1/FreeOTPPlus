package org.fedorahosted.freeotp.data

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

@Dao
abstract class OtpTokenDao(val database: OtpTokenDatabase) {

    @Query("select * from otp_tokens order by ordinal")
    abstract fun getAll(): Flow<List<OtpToken>>

    @Query("select * from otp_tokens where id = :id")
    abstract fun get(id: Long): Flow<OtpToken?>

    @Query("select ordinal from otp_tokens order by ordinal desc limit 1")
    abstract fun getLastOrdinal(): Long?

    @Query("delete from otp_tokens where id = :id")
    abstract suspend fun deleteById(id: Long): Void

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(otpTokenList: List<OtpToken>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(otpToken: OtpToken)

    @Update
    abstract suspend fun update(otpTokenList: OtpToken)

    @Query("update otp_tokens set ordinal = :ordinal where id = :id")
    abstract suspend fun updateOrdinal(id: Long, ordinal: Long)

    @RawQuery
    abstract suspend fun incrementCounterRaw(query: SupportSQLiteQuery): Int

}