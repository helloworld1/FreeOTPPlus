package org.fedorahosted.freeotp.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.fedorahosted.freeotp.data.util.Converters

@Database(entities = [OtpToken::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class OtpTokenDatabase : RoomDatabase() {
    abstract fun otpTokenDao(): OtpTokenDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE otp_tokens ADD COLUMN category TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE otp_tokens ADD COLUMN alias TEXT")
                db.execSQL("ALTER TABLE otp_tokens ADD COLUMN iconKey TEXT")
            }
        }
    }
}
