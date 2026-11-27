package org.fedorahosted.freeotp.ui.tokenlist

import org.fedorahosted.freeotp.util.TokenCodeGrouping
import java.util.Locale

object TokenDisplayUtil {
    fun formatHotpCounter(counter: Long): String {
        return String.format(Locale.US, "%d", counter.coerceAtLeast(0L))
    }

    fun formatCodeForDisplay(
        raw: String,
        grouping: TokenCodeGrouping = TokenCodeGrouping.TWO_GROUPS,
    ): String {
        if (grouping == TokenCodeGrouping.NONE) {
            return raw
        }
        return when (raw.length) {
            6 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
            7 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
            8 -> "${raw.substring(0, 4)} ${raw.substring(4)}"
            else -> raw
        }
    }

    fun buildPlaceholder(
        digits: Int,
        grouping: TokenCodeGrouping = TokenCodeGrouping.TWO_GROUPS,
    ): String {
        val safeDigits = digits.coerceIn(4, 10)
        return formatCodeForDisplay("-".repeat(safeDigits), grouping)
    }
}
