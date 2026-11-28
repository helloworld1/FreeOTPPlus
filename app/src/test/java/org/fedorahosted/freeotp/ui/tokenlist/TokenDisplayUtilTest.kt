package org.fedorahosted.freeotp.ui.tokenlist

import com.google.common.truth.Truth.assertThat
import org.fedorahosted.freeotp.util.TokenCodeGrouping
import org.junit.Test

class TokenDisplayUtilTest {

    @Test
    fun formatCodeForDisplay_twoGroupsIsDefaultAndFormats6And7And8Digits() {
        assertThat(TokenDisplayUtil.formatCodeForDisplay("123456")).isEqualTo("123 456")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("123456", TokenCodeGrouping.TWO_GROUPS))
            .isEqualTo("123 456")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("1234567", TokenCodeGrouping.TWO_GROUPS))
            .isEqualTo("123 4567")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("12345678", TokenCodeGrouping.TWO_GROUPS))
            .isEqualTo("1234 5678")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("23456", TokenCodeGrouping.TWO_GROUPS))
            .isEqualTo("23456")
    }

    @Test
    fun formatCodeForDisplay_threeGroupsFormats6And7And8DigitsAndCharacters() {
        // Numeric 6, 7, 8 digits
        assertThat(TokenDisplayUtil.formatCodeForDisplay("123456", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("12 34 56")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("1234567", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("123 45 67")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("12345678", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("123 456 78")

        // Alphanumeric 6, 7, 8 characters (e.g. Steam / character tokens)
        assertThat(TokenDisplayUtil.formatCodeForDisplay("2B4F9X", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("2B 4F 9X")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("2B4F9XK", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("2B4 F9 XK")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("2B4F9XK7", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("2B4 F9X K7")

        // 5-char tokens stay contiguous without orphan 1-char groups
        assertThat(TokenDisplayUtil.formatCodeForDisplay("23456", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("23456")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("R7K2M", TokenCodeGrouping.THREE_GROUPS))
            .isEqualTo("R7K2M")
    }

    @Test
    fun formatCodeForDisplay_noGroupingReturnsContiguousCode() {
        assertThat(TokenDisplayUtil.formatCodeForDisplay("123456", TokenCodeGrouping.NONE))
            .isEqualTo("123456")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("1234567", TokenCodeGrouping.NONE))
            .isEqualTo("1234567")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("12345678", TokenCodeGrouping.NONE))
            .isEqualTo("12345678")
        assertThat(TokenDisplayUtil.formatCodeForDisplay("23456", TokenCodeGrouping.NONE))
            .isEqualTo("23456")
    }

    @Test
    fun buildPlaceholder_respectsTwoGroupsThreeGroupsAndNoGrouping() {
        assertThat(TokenDisplayUtil.buildPlaceholder(6)).isEqualTo("--- ---")
        assertThat(TokenDisplayUtil.buildPlaceholder(6, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("--- ---")
        assertThat(TokenDisplayUtil.buildPlaceholder(7, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("--- ----")
        assertThat(TokenDisplayUtil.buildPlaceholder(8, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("---- ----")

        assertThat(TokenDisplayUtil.buildPlaceholder(6, TokenCodeGrouping.THREE_GROUPS)).isEqualTo("-- -- --")
        assertThat(TokenDisplayUtil.buildPlaceholder(7, TokenCodeGrouping.THREE_GROUPS)).isEqualTo("--- -- --")
        assertThat(TokenDisplayUtil.buildPlaceholder(8, TokenCodeGrouping.THREE_GROUPS)).isEqualTo("--- --- --")

        assertThat(TokenDisplayUtil.buildPlaceholder(6, TokenCodeGrouping.NONE)).isEqualTo("------")
        assertThat(TokenDisplayUtil.buildPlaceholder(7, TokenCodeGrouping.NONE)).isEqualTo("-------")
        assertThat(TokenDisplayUtil.buildPlaceholder(8, TokenCodeGrouping.NONE)).isEqualTo("--------")
    }
}
