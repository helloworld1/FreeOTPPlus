package org.fedorahosted.freeotp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.data.util.CategoryUtil
import java.util.Locale

@Dao
interface OtpTokenDao {

    @Query("select * from otp_tokens order by ordinal")
    fun getAll(): Flow<List<OtpToken>>

    @Query("select * from otp_tokens where id = :id")
    fun get(id: Long): Flow<OtpToken?>

    @Query("select ordinal from otp_tokens order by ordinal desc limit 1")
    fun getLastOrdinal(): Long?

    @Query("select categories from otp_tokens where categories != '[]'")
    fun getAllCategoryJsonRows(): Flow<List<String>>

    fun getAllCategories(): Flow<List<String>> = getAllCategoryJsonRows().map { rows ->
        val seenLower = mutableSetOf<String>()
        val distinct = mutableListOf<String>()
        for (row in rows) {
            for (cat in CategoryUtil.fromJson(row)) {
                if (seenLower.add(cat.lowercase(Locale.ROOT))) {
                    distinct.add(cat)
                }
            }
        }
        distinct.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    @Query("delete from otp_tokens where id = :id")
    suspend fun deleteById(id: Long)

    @Query("delete from otp_tokens")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(otpTokenList: List<OtpToken>)

    @Transaction
    suspend fun replaceAll(otpTokenList: List<OtpToken>) {
        deleteAll()
        insertAll(otpTokenList)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(otpTokenList: OtpToken)

    @Update
    suspend fun update(otpTokenList: OtpToken)

    @Update
    suspend fun updateAll(otpTokenList: List<OtpToken>)

    @Query("update otp_tokens set ordinal = :ordinal where id = :id")
    suspend fun updateOrdinal(id: Long, ordinal: Long)

    @Query("update otp_tokens set counter = counter + 1 where id = :id")
    suspend fun incrementCounter(id: Long)

    @Transaction
    suspend fun movePairs(pairs : List<Pair<Long,Long>>){
        for(pair in pairs.listIterator()) {
            withContext(Dispatchers.IO) {
                val token1 = get(pair.first).first()
                val token2 = get(pair.second).first()

                if (token1 == null || token2 == null) {
                    return@withContext
                }
                updateOrdinal(pair.first, token2.ordinal)
                updateOrdinal(pair.second, token1.ordinal)
            }
        }
    }
}