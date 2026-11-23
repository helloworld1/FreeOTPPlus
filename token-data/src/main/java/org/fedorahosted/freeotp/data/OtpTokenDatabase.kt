package org.fedorahosted.freeotp.data

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.Converters

@Database(entities = [OtpToken::class], version = 4, exportSchema = false)
@ColumnTypeConverters(Converters::class)
abstract class OtpTokenDatabase : RoomDatabase() {
    abstract fun otpTokenDao(): OtpTokenDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE otp_tokens ADD COLUMN category TEXT")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
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
                connection.execSQL(
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
                connection.prepare(
                    "SELECT `id`, `category` FROM `otp_tokens` WHERE `category` IS NOT NULL"
                ).use { selectStmt ->
                    connection.prepare(
                        "UPDATE `otp_tokens_new` SET `categories` = ? WHERE `id` = ?"
                    ).use { updateStmt ->
                        while (selectStmt.step()) {
                            val id = selectStmt.getLong(0)
                            val rawCategory = if (selectStmt.isNull(1)) null else selectStmt.getText(1)
                            val normalized = CategoryUtil.normalizeCategories(listOf(rawCategory))
                            if (normalized.isNotEmpty()) {
                                updateStmt.reset()
                                updateStmt.clearBindings()
                                updateStmt.bindText(1, CategoryUtil.toJson(normalized))
                                updateStmt.bindLong(2, id)
                                updateStmt.step()
                            }
                        }
                    }
                }
                connection.execSQL("DROP TABLE `otp_tokens`")
                connection.execSQL("ALTER TABLE `otp_tokens_new` RENAME TO `otp_tokens`")
            }
        }

        // Preserve all synced HOTP counters >= 0 unchanged; normalize only legacy unused -1 counters to 0.
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("UPDATE `otp_tokens` SET `counter` = 0 WHERE `tokenType` = 'HOTP' AND `counter` < 0")
            }
        }
    }
}