package org.fedorahosted.freeotp.data.util

import androidx.room.TypeConverter
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpTokenType

class Converters {
    @TypeConverter
    fun fromOtpTokenType(value: OtpTokenType): String {
        return value.name
    }

    @TypeConverter
    fun toOtpTokenType(value: String): OtpTokenType {
        return OtpTokenType.valueOf(value)
    }

    @TypeConverter
    fun fromEncryptionType(value: EncryptionType): String {
        return value.name
    }

    @TypeConverter
    fun toEncryptionType(value: String): EncryptionType {
        return EncryptionType.valueOf(value)
    }

    @TypeConverter
    fun fromCategoryList(value: List<String>?): String {
        return CategoryUtil.toJson(value)
    }

    @TypeConverter
    fun toCategoryList(value: String?): List<String> {
        return CategoryUtil.fromJson(value)
    }
}
