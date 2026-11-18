package org.fedorahosted.freeotp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.Converters

@Database(entities = [OtpToken::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class OtpTokenDatabase : RoomDatabase() {
    abstract fun otpTokenDao(): OtpTokenDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE otp_tokens ADD COLUMN category TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `otp_tokens_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ordinal` INTEGER NOT NULL,
                        `issuer` TEXT,
                        `label` TEXT NOT NULL,
                        `imagePath` TEXT,
                        `tokenType` TEXT NOT NULL,
                        `algorithm` TEXT NOT NULL,
                        `secret` TEXT NOT NULL,
                        `digits` INTEGER NOT NULL,
                        `counter` INTEGER NOT NULL,
                        `period` INTEGER NOT NULL,
                        `encryptionType` TEXT NOT NULL,
                        `categories` TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO `otp_tokens_new` (
                        `id`, `ordinal`, `issuer`, `label`, `imagePath`,
                        `tokenType`, `algorithm`, `secret`, `digits`,
                        `counter`, `period`, `encryptionType`, `categories`
                    )
                    SELECT
                        `id`, `ordinal`, `issuer`, `label`, `imagePath`,
                        `tokenType`, `algorithm`, `secret`, `digits`,
                        `counter`, `period`, `encryptionType`, '[]'
                    FROM `otp_tokens`
                    """.trimIndent()
                )
                database.query("SELECT `id`, `category` FROM `otp_tokens` WHERE `category` IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val rawCategory = if (cursor.isNull(1)) null else cursor.getString(1)
                        val normalized = CategoryUtil.normalizeCategories(listOf(rawCategory))
                        if (normalized.isNotEmpty()) {
                            val json = CategoryUtil.toJson(normalized)
                            database.execSQL(
                                "UPDATE `otp_tokens_new` SET `categories` = ? WHERE `id` = ?",
                                arrayOf<Any>(json, id)
                            )
                        }
                    }
                }
                database.execSQL("DROP TABLE `otp_tokens`")
                database.execSQL("ALTER TABLE `otp_tokens_new` RENAME TO `otp_tokens`")
            }
        }
    }
}