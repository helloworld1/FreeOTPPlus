package org.fedorahosted.freeotp.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokenSortOrderTest {

    private val tokens = listOf(
        makeToken(id = 1L, ordinal = 10L, issuer = "Google", label = "charlie@gmail.com"),
        makeToken(id = 2L, ordinal = 20L, issuer = "github", label = "Bob@github.com"),
        makeToken(id = 3L, ordinal = 30L, issuer = "GitHub", label = "alice@github.com"),
        makeToken(id = 4L, ordinal = 40L, issuer = null, label = "My VPN"),
        makeToken(id = 5L, ordinal = 50L, issuer = "Amazon", label = "bob@github.com")
    )

    @Test
    fun sortAccountAscending_sortsCaseInsensitivelyWithIssuerTieBreakerAndReassignsOrdinals() {
        val sorted = TokenSortOrder.ACCOUNT_ASC.sort(tokens)
        assertThat(sorted.map { it.id }).containsExactly(3L, 5L, 2L, 1L, 4L).inOrder()
        assertThat(sorted.map { it.ordinal }).containsExactly(0L, 1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun sortAccountDescending_sortsReverseAlphabetically() {
        val sorted = TokenSortOrder.ACCOUNT_DESC.sort(tokens)
        assertThat(sorted.map { it.id }).containsExactly(4L, 1L, 2L, 5L, 3L).inOrder()
        assertThat(sorted.map { it.ordinal }).containsExactly(0L, 1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun sortIssuerAscending_sortsIssuersAlphabeticallyWithBlankIssuersLast() {
        val sorted = TokenSortOrder.ISSUER_ASC.sort(tokens)
        assertThat(sorted.map { it.id }).containsExactly(5L, 3L, 2L, 1L, 4L).inOrder()
        assertThat(sorted.map { it.ordinal }).containsExactly(0L, 1L, 2L, 3L, 4L).inOrder()
    }

    @Test
    fun sortIssuerDescending_sortsIssuersReverseAlphabeticallyWithBlankIssuersLast() {
        val sorted = TokenSortOrder.ISSUER_DESC.sort(tokens)
        assertThat(sorted.map { it.id }).containsExactly(1L, 2L, 3L, 5L, 4L).inOrder()
        assertThat(sorted.map { it.ordinal }).containsExactly(0L, 1L, 2L, 3L, 4L).inOrder()
    }

    private fun makeToken(
        id: Long,
        ordinal: Long,
        issuer: String?,
        label: String
    ): OtpToken {
        return OtpToken(
            id = id,
            ordinal = ordinal,
            issuer = issuer,
            label = label,
            imagePath = null,
            tokenType = OtpTokenType.TOTP,
            algorithm = "SHA1",
            secret = "JBSWY3DPEHPK3PXP",
            digits = 6,
            counter = 0L,
            period = 30,
            encryptionType = EncryptionType.PLAIN_TEXT
        )
    }
}
