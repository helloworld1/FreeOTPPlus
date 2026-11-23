package org.fedorahosted.freeotp.data.util

import androidx.room3.ColumnTypeConverter
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpTokenType

class Converters {
    @ColumnTypeConverter
    fun fromOtpTokenType(value: OtpTokenType): String {
        return value.name
    }

    @ColumnTypeConverter
    fun toOtpTokenType(value: String): OtpTokenType {
        return OtpTokenType.valueOf(value)
    }

    @ColumnTypeConverter
    fun fromEncryptionType(value: EncryptionType): String {
        return value.name
    }

    @ColumnTypeConverter
    fun toEncryptionType(value: String): EncryptionType {
        return EncryptionType.valueOf(value)
    }

    @ColumnTypeConverter
    fun fromCategoryList(value: List<String>?): String {
        return CategoryUtil.toJson(value)
    }

    @ColumnTypeConverter
    fun toCategoryList(value: String?): List<String> {
        return CategoryUtil.fromJson(value)
    }
}
