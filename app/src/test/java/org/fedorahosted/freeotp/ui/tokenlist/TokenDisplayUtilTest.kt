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
    fun buildPlaceholder_respectsTwoGroupsAndNoGrouping() {
        assertThat(TokenDisplayUtil.buildPlaceholder(6)).isEqualTo("--- ---")
        assertThat(TokenDisplayUtil.buildPlaceholder(6, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("--- ---")
        assertThat(TokenDisplayUtil.buildPlaceholder(7, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("--- ----")
        assertThat(TokenDisplayUtil.buildPlaceholder(8, TokenCodeGrouping.TWO_GROUPS)).isEqualTo("---- ----")

        assertThat(TokenDisplayUtil.buildPlaceholder(6, TokenCodeGrouping.NONE)).isEqualTo("------")
        assertThat(TokenDisplayUtil.buildPlaceholder(7, TokenCodeGrouping.NONE)).isEqualTo("-------")
        assertThat(TokenDisplayUtil.buildPlaceholder(8, TokenCodeGrouping.NONE)).isEqualTo("--------")
    }
}
