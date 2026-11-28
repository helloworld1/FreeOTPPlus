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
        return when (grouping) {
            TokenCodeGrouping.NONE -> raw
            TokenCodeGrouping.TWO_GROUPS -> when (raw.length) {
                6 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
                7 -> "${raw.substring(0, 3)} ${raw.substring(3)}"
                8 -> "${raw.substring(0, 4)} ${raw.substring(4)}"
                9 -> "${raw.substring(0, 4)} ${raw.substring(4)}"
                10 -> "${raw.substring(0, 5)} ${raw.substring(5)}"
                else -> raw
            }
            TokenCodeGrouping.THREE_GROUPS -> {
                if (raw.length < 6) {
                    raw
                } else {
                    val base = raw.length / 3
                    val rem = raw.length % 3
                    val firstLen = base + if (rem > 0) 1 else 0
                    val secondLen = base + if (rem > 1) 1 else 0
                    "${raw.substring(0, firstLen)} ${raw.substring(firstLen, firstLen + secondLen)} ${raw.substring(firstLen + secondLen)}"
                }
            }
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
