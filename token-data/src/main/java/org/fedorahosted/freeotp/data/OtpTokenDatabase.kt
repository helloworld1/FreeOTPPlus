package org.fedorahosted.freeotp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [OtpToken::class], version = 1, exportSchema = false)
abstract class OtpTokenDatabase : RoomDatabase() {
    abstract fun otpTokenDao(): OtpTokenDao
}