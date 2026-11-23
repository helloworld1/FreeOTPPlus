package org.fedorahosted.freeotp.data

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.fedorahosted.freeotp.data.util.CategoryUtil
import java.util.Locale

@Dao
interface OtpTokenDao {

    @Query("SELECT * FROM otp_tokens ORDER BY ordinal")
    fun getAll(): Flow<List<OtpToken>>

    @Query("SELECT * FROM otp_tokens WHERE id = :id")
    fun get(id: Long): Flow<OtpToken?>

    @Query("SELECT ordinal FROM otp_tokens ORDER BY ordinal DESC LIMIT 1")
    suspend fun getLastOrdinal(): Long?

    @Query("SELECT categories FROM otp_tokens WHERE categories != '[]'")
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

    @Query("DELETE FROM otp_tokens WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM otp_tokens")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(otpTokenList: List<OtpToken>)

    @Transaction
    suspend fun replaceAll(otpTokenList: List<OtpToken>) {
        deleteAll()
        insertAll(otpTokenList)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(otpToken: OtpToken)

    @Update
    suspend fun update(otpToken: OtpToken)

    @Update
    suspend fun updateAll(otpTokenList: List<OtpToken>)

    @Query("UPDATE otp_tokens SET ordinal = :ordinal WHERE id = :id")
    suspend fun updateOrdinal(id: Long, ordinal: Long)

    @Query("UPDATE otp_tokens SET counter = counter + 1 WHERE id = :id AND counter < 9223372036854775807")
    suspend fun incrementCounter(id: Long)

    @Transaction
    suspend fun sortAll(order: TokenSortOrder) {
        val current = getAll().first()
        if (current.size <= 1) return
        updateAll(order.sort(current))
    }

    @Transaction
    suspend fun movePairs(pairs: List<Pair<Long, Long>>) {
        for ((firstId, secondId) in pairs) {
            val token1 = get(firstId).first()
            val token2 = get(secondId).first()
            if (token1 == null || token2 == null) {
                continue
            }
            updateOrdinal(firstId, token2.ordinal)
            updateOrdinal(secondId, token1.ordinal)
        }
    }
}