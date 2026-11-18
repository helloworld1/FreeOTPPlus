package org.fedorahosted.freeotp.data.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale

object CategoryUtil {
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String?>?>() {}.type

    fun normalizeCategories(raw: Iterable<String?>?): List<String> {
        if (raw == null) return emptyList()
        val seenLower = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (item in raw) {
            val trimmed = item?.trim().orEmpty()
            if (trimmed.isNotEmpty() && seenLower.add(trimmed.lowercase(Locale.ROOT))) {
                result.add(trimmed)
            }
        }
        return result
    }

    fun toJson(categories: List<String>?): String {
        val normalized = normalizeCategories(categories)
        if (normalized.isEmpty()) return "[]"
        return gson.toJson(normalized)
    }

    fun fromJson(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
        if (!trimmed.startsWith("[")) {
            return normalizeCategories(listOf(trimmed))
        }
        return try {
            val parsed: List<String?>? = gson.fromJson(trimmed, stringListType)
            normalizeCategories(parsed)
        } catch (_: Exception) {
            normalizeCategories(listOf(trimmed))
        }
    }

    fun formatForDisplay(categories: List<String>): String {
        return normalizeCategories(categories).joinToString(" • ")
    }
}
