package org.liberty.android.freeotp.token_images

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Locale

class TokenImageTest {

    private fun firstMatchingTokenImage(issuer: String?, label: String? = "user@example.com"): TokenImage? {
        return TokenImage.values().firstOrNull { it.matchToken(issuer, label) }
    }

    @Test
    fun noEnumEntryCandidateIsShadowedByEarlierEntry() {
        for (entry in TokenImage.values()) {
            val candidates = listOf(entry.issuer ?: entry.name) + entry.altIssuers
            for (candidate in candidates) {
                assertWithMessage("Candidate '$candidate' of ${entry.name}")
                    .that(firstMatchingTokenImage(candidate, label = null))
                    .isEqualTo(entry)
            }
        }
    }

    @Test
    fun matchesBestBuyVariants() {
        assertThat(firstMatchingTokenImage("BestBuy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("Best Buy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("best buy")).isEqualTo(TokenImage.BestBuy)
        assertThat(firstMatchingTokenImage("BestBuy.com")).isEqualTo(TokenImage.BestBuy)
    }

    @Test
    fun matchesGogWithoutBreakingGogsOrSubstringFalsePositives() {
        assertThat(firstMatchingTokenImage("GOG.com")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("gog.com")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("GOG")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("GOG Galaxy")).isEqualTo(TokenImage.GOG)
        assertThat(firstMatchingTokenImage("Gogs")).isEqualTo(TokenImage.Gogs)
        assertThat(firstMatchingTokenImage("viagogo")).isNull()
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

    @Test
    fun matchesUsMobileVariants() {
        assertThat(firstMatchingTokenImage("US Mobile")).isEqualTo(TokenImage.USMobile)
        assertThat(firstMatchingTokenImage("us mobile")).isEqualTo(TokenImage.USMobile)
        assertThat(firstMatchingTokenImage("USMobile")).isEqualTo(TokenImage.USMobile)
        assertThat(firstMatchingTokenImage("usmobile.com")).isEqualTo(TokenImage.USMobile)
    }

    @Test
    fun matchesUbiquitiAndPreservesUbiquitiNetworks() {
        assertThat(firstMatchingTokenImage("Ubiquiti")).isEqualTo(TokenImage.Ubiquiti)
        assertThat(firstMatchingTokenImage("Ubiquiti Inc.")).isEqualTo(TokenImage.Ubiquiti)
        assertThat(firstMatchingTokenImage("UniFi")).isEqualTo(TokenImage.Ubiquiti)
        assertThat(firstMatchingTokenImage("UI.com")).isEqualTo(TokenImage.Ubiquiti)
        assertThat(firstMatchingTokenImage("UbiquitiNetworks")).isEqualTo(TokenImage.UbiquitiNetworks)
    }

    @Test
    fun matchesMorganStanleyVariants() {
        assertThat(firstMatchingTokenImage("Morgan Stanley")).isEqualTo(TokenImage.MorganStanley)
        assertThat(firstMatchingTokenImage("morgan stanley")).isEqualTo(TokenImage.MorganStanley)
        assertThat(firstMatchingTokenImage("MorganStanley")).isEqualTo(TokenImage.MorganStanley)
    }

    @Test
    fun matchesPunctuatedNumberedAndSpacedIssuers() {
        assertThat(firstMatchingTokenImage("1Password")).isEqualTo(TokenImage.OnePassword)
        assertThat(firstMatchingTokenImage("1&1")).isEqualTo(TokenImage.OneAndOne)
        assertThat(firstMatchingTokenImage("23andMe")).isEqualTo(TokenImage.TwentyThreeAndMe)
        assertThat(firstMatchingTokenImage("Battle.net")).isEqualTo(TokenImage.BattleNet)
        assertThat(firstMatchingTokenImage("Electronic Arts")).isEqualTo(TokenImage.ElectronicArts)
        assertThat(firstMatchingTokenImage("Epic Games")).isEqualTo(TokenImage.EpicGames)
        assertThat(firstMatchingTokenImage("GOV.UK")).isEqualTo(TokenImage.GovUK)
        assertThat(firstMatchingTokenImage("Home Assistant")).isEqualTo(TokenImage.HomeAssistant)
        assertThat(firstMatchingTokenImage("Humble Bundle")).isEqualTo(TokenImage.HumbleBundle)
        assertThat(firstMatchingTokenImage("Hurricane Electric")).isEqualTo(TokenImage.HurricaneElectric)
        assertThat(firstMatchingTokenImage("itch.io")).isEqualTo(TokenImage.Itchio)
        assertThat(firstMatchingTokenImage("Name.com")).isEqualTo(TokenImage.NameCom)
        assertThat(firstMatchingTokenImage("Private Internet Access")).isEqualTo(TokenImage.PrivateInternetAccess)
        assertThat(firstMatchingTokenImage("RIPE NCC")).isEqualTo(TokenImage.RipeNNC)
        assertThat(firstMatchingTokenImage("Standard Notes")).isEqualTo(TokenImage.StandardNotes)
        assertThat(firstMatchingTokenImage("TU Wien")).isEqualTo(TokenImage.TUWien)
    }

    @Test
    fun matchesServiceRebrandsAndPreviouslyShadowedEntries() {
        assertThat(firstMatchingTokenImage("addy.io")).isEqualTo(TokenImage.Anonaddy)
        assertThat(firstMatchingTokenImage("Blockchain.com")).isEqualTo(TokenImage.BlockchainInfo)
        assertThat(firstMatchingTokenImage("Privacy.com")).isEqualTo(TokenImage.PayWithPrivacy)
        assertThat(firstMatchingTokenImage("Proton")).isEqualTo(TokenImage.ProtonMail)
        assertThat(firstMatchingTokenImage("Proton Mail")).isEqualTo(TokenImage.ProtonMail)
        assertThat(firstMatchingTokenImage("Tuta")).isEqualTo(TokenImage.Tutanota)
        assertThat(firstMatchingTokenImage("Roberts Space Industries")).isEqualTo(TokenImage.StarCitizen)

        assertThat(firstMatchingTokenImage("Amazon Web Services")).isEqualTo(TokenImage.AmazonWebServices)
        assertThat(firstMatchingTokenImage("AWS")).isEqualTo(TokenImage.AmazonWebServices)
        assertThat(firstMatchingTokenImage("Amazon")).isEqualTo(TokenImage.Amazon)

        assertThat(firstMatchingTokenImage("LocalBitcoins")).isEqualTo(TokenImage.LocalBitcoins)
        assertThat(firstMatchingTokenImage("Bitcoin")).isEqualTo(TokenImage.Bitcoin)

        assertThat(firstMatchingTokenImage("Microsoft Teams")).isEqualTo(TokenImage.MicrosoftTeams)
        assertThat(firstMatchingTokenImage("Microsoft")).isEqualTo(TokenImage.Microsoft)

        assertThat(firstMatchingTokenImage("Pushover")).isEqualTo(TokenImage.Pushover)
        assertThat(firstMatchingTokenImage("Hover")).isEqualTo(TokenImage.Hover)

        assertThat(firstMatchingTokenImage("Xerox")).isEqualTo(TokenImage.Xerox)
        assertThat(firstMatchingTokenImage("Xero")).isEqualTo(TokenImage.Xero)
    }

    @Test
    fun wordBoundaryPreventsShortAcronymFalsePositives() {
        assertThat(firstMatchingTokenImage("ING")).isEqualTo(TokenImage.ING)
        assertThat(firstMatchingTokenImage("ING Bank")).isEqualTo(TokenImage.ING)
        assertThat(firstMatchingTokenImage("ING-DiBa")).isEqualTo(TokenImage.ING)
        assertThat(firstMatchingTokenImage("Wargaming.net")).isEqualTo(TokenImage.Wargaming)
        assertThat(firstMatchingTokenImage("Xing")).isEqualTo(TokenImage.Xing)
        assertThat(firstMatchingTokenImage("Parsec")).isEqualTo(TokenImage.Parsecgaming)
        assertThat(firstMatchingTokenImage("Hostinger")).isNull()
        assertThat(firstMatchingTokenImage("Ring")).isNull()

        assertThat(firstMatchingTokenImage("Git")).isEqualTo(TokenImage.Git)
        assertThat(firstMatchingTokenImage("git.example.com")).isEqualTo(TokenImage.Git)
        assertThat(firstMatchingTokenImage("Logitech")).isNull()
    }
}
