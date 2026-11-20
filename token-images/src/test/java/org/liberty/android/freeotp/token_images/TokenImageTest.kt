package org.liberty.android.freeotp.token_images

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class TokenImageTest {

    private fun firstMatchingTokenImage(issuer: String?, label: String? = "user@example.com"): TokenImage? {
        return TokenImage.values().firstOrNull { it.matchToken(issuer, label) }
    }

    @Test
    fun matchesBestBuyVariants() {
        assertThat(firstMatchingTokenImage("BestBuy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("Best Buy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("best buy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("BestBuy.com")).isEqualTo(TokenImage.BestBuy)
    }

    @Test
    fun matchesGogWithoutBreakingGogs() {
        assertThat(firstMatchingTokenImage("GOG.com")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("gog.com")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("GOG")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("GOG Galaxy")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("Gogs")).isEqualTo(TokenImage.Gogs)
    }

    @Test
    fun matchesMozillaWhileKeepingFirefoxDistinct() {
        assertThat(firstMatchingTokenImage("Mozilla")).isEqualTo(TokenImage.Mozilla)
        assertThat(firstMatchingTokenImage("Mozilla Account")).isEqualTo(TokenImage.Mozilla)
        assertThat(firstMatchingTokenImage("mozilla")).isEqualTo(TokenImage.Mozilla)
        assertThat(firstMatchingTokenImage("Firefox")).isEqualTo(TokenImage.Firefox)
    }

    @Test
    fun matchesNvidiaAcrossCasingAndLocales() {
        assertThat(firstMatchingTokenImage("Nvidia")).isEqualTo(TokenImage.Nvidia)
        assertThat(firstMatchingTokenImage("NVIDIA")).isEqualTo(TokenImage.Nvidia)
        assertThat(firstMatchingTokenImage("NVIDIA Account")).isEqualTo(TokenImage.Nvidia)

        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(firstMatchingTokenImage("NVIDIA")).isEqualTo(TokenImage.Nvidia)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun matchesEcobeeVariants() {
        assertThat(firstMatchingTokenImage("Ecobee")).isEqualTo(TokenImage.Ecobee)
        assertThat(firstMatchingTokenImage("ecobee")).isEqualTo(TokenImage.Ecobee)
    }
}
