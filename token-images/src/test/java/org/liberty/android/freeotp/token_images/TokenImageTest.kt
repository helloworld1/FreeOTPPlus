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

    @Test
    fun matchesNewlyAddedTop30IconsWithoutFalsePositives() {
        // 1. Big Tech, AI, Social & Browsers
        assertThat(firstMatchingTokenImage("OpenAI")).isEqualTo(TokenImage.OpenAI)
        assertThat(firstMatchingTokenImage("ChatGPT")).isEqualTo(TokenImage.OpenAI)
        assertThat(firstMatchingTokenImage("Meta")).isEqualTo(TokenImage.Meta)
        assertThat(firstMatchingTokenImage("Meta Platforms")).isEqualTo(TokenImage.Meta)
        assertThat(firstMatchingTokenImage("MetaMask")).isNull()
        assertThat(firstMatchingTokenImage("Yahoo")).isEqualTo(TokenImage.Yahoo)
        assertThat(firstMatchingTokenImage("Yahoo!")).isEqualTo(TokenImage.Yahoo)
        assertThat(firstMatchingTokenImage("Samsung")).isEqualTo(TokenImage.Samsung)
        assertThat(firstMatchingTokenImage("Sony")).isEqualTo(TokenImage.Sony)

        // 2. Cloud, DevOps, Developer Tools & Domains
        assertThat(firstMatchingTokenImage("Azure")).isEqualTo(TokenImage.Azure)
        assertThat(firstMatchingTokenImage("Microsoft Azure")).isEqualTo(TokenImage.Azure)
        assertThat(firstMatchingTokenImage("Microsoft")).isEqualTo(TokenImage.Microsoft)
        assertThat(firstMatchingTokenImage("Oracle")).isEqualTo(TokenImage.Oracle)
        assertThat(firstMatchingTokenImage("Oracle Cloud")).isEqualTo(TokenImage.Oracle)
        assertThat(firstMatchingTokenImage("Auth0")).isEqualTo(TokenImage.Auth0)
        assertThat(firstMatchingTokenImage("Authelia")).isEqualTo(TokenImage.Authelia)
        assertThat(firstMatchingTokenImage("Keycloak")).isEqualTo(TokenImage.Keycloak)

        // 3. Productivity, SaaS & Collaboration
        assertThat(firstMatchingTokenImage("Notion")).isEqualTo(TokenImage.Notion)
        assertThat(firstMatchingTokenImage("Obsidian")).isEqualTo(TokenImage.Obsidian)
        assertThat(firstMatchingTokenImage("ObsidianMD")).isEqualTo(TokenImage.Obsidian)
        assertThat(firstMatchingTokenImage("Todoist")).isEqualTo(TokenImage.Todoist)
        assertThat(firstMatchingTokenImage("Asana")).isEqualTo(TokenImage.Asana)
        assertThat(firstMatchingTokenImage("ClickUp")).isEqualTo(TokenImage.ClickUp)

        // 4. Privacy, Email, Security & VPN
        assertThat(firstMatchingTokenImage("SimpleLogin")).isEqualTo(TokenImage.SimpleLogin)
        assertThat(firstMatchingTokenImage("ente")).isEqualTo(TokenImage.Ente)
        assertThat(firstMatchingTokenImage("Ente")).isEqualTo(TokenImage.Ente)
        assertThat(firstMatchingTokenImage("ente.io")).isEqualTo(TokenImage.Ente)
        assertThat(firstMatchingTokenImage("Enterprise")).isNull()
        assertThat(firstMatchingTokenImage("Center")).isNull()
        assertThat(firstMatchingTokenImage("NordVPN")).isEqualTo(TokenImage.NordVPN)
        assertThat(firstMatchingTokenImage("NordAccount")).isEqualTo(TokenImage.NordVPN)
        assertThat(firstMatchingTokenImage("ExpressVPN")).isEqualTo(TokenImage.ExpressVPN)
        assertThat(firstMatchingTokenImage("Surfshark")).isEqualTo(TokenImage.Surfshark)

        // 5. Finance, Retail, Tax, Healthcare & Government
        assertThat(firstMatchingTokenImage("eBay")).isEqualTo(TokenImage.EBay)
        assertThat(firstMatchingTokenImage("ebay.com")).isEqualTo(TokenImage.EBay)
        assertThat(firstMatchingTokenImage("TheBay")).isNull()
        assertThat(firstMatchingTokenImage("Shopify")).isEqualTo(TokenImage.Shopify)
        assertThat(firstMatchingTokenImage("Newegg")).isEqualTo(TokenImage.Newegg)
        assertThat(firstMatchingTokenImage("Rakuten")).isEqualTo(TokenImage.Rakuten)
        assertThat(firstMatchingTokenImage("Cash App")).isEqualTo(TokenImage.CashApp)
        assertThat(firstMatchingTokenImage("CashApp")).isEqualTo(TokenImage.CashApp)

        // 6. Gaming, Entertainment, Homelab & Telecom / IoT
        assertThat(firstMatchingTokenImage("Roblox")).isEqualTo(TokenImage.Roblox)
        assertThat(firstMatchingTokenImage("Activision")).isEqualTo(TokenImage.Activision)
        assertThat(firstMatchingTokenImage("Bethesda")).isEqualTo(TokenImage.Bethesda)
        assertThat(firstMatchingTokenImage("Square Enix")).isEqualTo(TokenImage.SquareEnix)
        assertThat(firstMatchingTokenImage("Squarespace")).isEqualTo(TokenImage.Squarespace)
        assertThat(firstMatchingTokenImage("CAPCOM ID")).isEqualTo(TokenImage.Capcom)
        assertThat(firstMatchingTokenImage("Capcom")).isEqualTo(TokenImage.Capcom)
    }

    @Test
    fun matchesRemaining153IconsWithoutFalsePositives() {
        // 1. Big Tech, AI, Social & Browsers
        assertThat(firstMatchingTokenImage("PlayStation")).isEqualTo(TokenImage.PlayStation)
        assertThat(firstMatchingTokenImage("PSN")).isEqualTo(TokenImage.PlayStation)
        assertThat(firstMatchingTokenImage("Tesla")).isEqualTo(TokenImage.Tesla)
        assertThat(firstMatchingTokenImage("TikTok")).isEqualTo(TokenImage.TikTok)
        assertThat(firstMatchingTokenImage("Pinterest")).isEqualTo(TokenImage.Pinterest)
        assertThat(firstMatchingTokenImage("AOL")).isEqualTo(TokenImage.AOL)
        assertThat(firstMatchingTokenImage("Paola")).isNull()
        assertThat(firstMatchingTokenImage("Brave")).isEqualTo(TokenImage.Brave)
        assertThat(firstMatchingTokenImage("Opera")).isEqualTo(TokenImage.Opera)
        assertThat(firstMatchingTokenImage("Operation")).isNull()
        assertThat(firstMatchingTokenImage("Vivaldi")).isEqualTo(TokenImage.Vivaldi)
        assertThat(firstMatchingTokenImage("Mistral")).isEqualTo(TokenImage.Mistral)
        assertThat(firstMatchingTokenImage("Substack")).isEqualTo(TokenImage.Substack)
        assertThat(firstMatchingTokenImage("Vimeo")).isEqualTo(TokenImage.Vimeo)
        assertThat(firstMatchingTokenImage("Wikipedia")).isEqualTo(TokenImage.Wikipedia)
        assertThat(firstMatchingTokenImage("Yandex")).isEqualTo(TokenImage.Yandex)
        assertThat(firstMatchingTokenImage("Yandex.Money")).isEqualTo(TokenImage.YandexMoney)
        assertThat(firstMatchingTokenImage("Yandex Money")).isEqualTo(TokenImage.YandexMoney)
        assertThat(firstMatchingTokenImage("NAVER")).isEqualTo(TokenImage.NAVER)
        assertThat(firstMatchingTokenImage("Xiaomi")).isEqualTo(TokenImage.Xiaomi)
        assertThat(firstMatchingTokenImage("Huawei")).isEqualTo(TokenImage.Huawei)
        assertThat(firstMatchingTokenImage("Lenovo")).isEqualTo(TokenImage.Lenovo)
        assertThat(firstMatchingTokenImage("HP")).isEqualTo(TokenImage.HP)
        assertThat(firstMatchingTokenImage("Hewlett Packard Enterprise")).isEqualTo(TokenImage.HP)
        assertThat(firstMatchingTokenImage("phpMyAdmin")).isEqualTo(TokenImage.phpMyAdmin)

        // 2. Cloud, DevOps, Developer Tools & Domains
        assertThat(firstMatchingTokenImage("HashiCorp")).isEqualTo(TokenImage.HashiCorp)
        assertThat(firstMatchingTokenImage("Terraform")).isEqualTo(TokenImage.Terraform)
        assertThat(firstMatchingTokenImage("Netlify")).isEqualTo(TokenImage.Netlify)
        assertThat(firstMatchingTokenImage("Render")).isEqualTo(TokenImage.Render)
        assertThat(firstMatchingTokenImage("Surrender")).isNull()
        assertThat(firstMatchingTokenImage("Railway")).isEqualTo(TokenImage.Railway)
        assertThat(firstMatchingTokenImage("Fly.io")).isEqualTo(TokenImage.Flyio)
        assertThat(firstMatchingTokenImage("Fastly")).isEqualTo(TokenImage.Fastly)
        assertThat(firstMatchingTokenImage("Akamai Connected Cloud")).isEqualTo(TokenImage.Akamai)
        assertThat(firstMatchingTokenImage("Alibaba Cloud")).isEqualTo(TokenImage.AlibabaCloud)
        assertThat(firstMatchingTokenImage("Aliyun")).isEqualTo(TokenImage.AlibabaCloud)
        assertThat(firstMatchingTokenImage("PostgreSQL")).isEqualTo(TokenImage.PostgreSQL)
        assertThat(firstMatchingTokenImage("Expo")).isEqualTo(TokenImage.Expo)
        assertThat(firstMatchingTokenImage("Export")).isNull()
        assertThat(firstMatchingTokenImage("sourcehut")).isEqualTo(TokenImage.SourceHut)
        assertThat(firstMatchingTokenImage("sr.ht")).isEqualTo(TokenImage.SourceHut)
        assertThat(firstMatchingTokenImage("RubyGems.org")).isEqualTo(TokenImage.RubyGems)
        assertThat(firstMatchingTokenImage("cPanel")).isEqualTo(TokenImage.CPanel)
        assertThat(firstMatchingTokenImage("WHMCS")).isEqualTo(TokenImage.WHMCS)
        assertThat(firstMatchingTokenImage("WHM")).isEqualTo(TokenImage.WHMCS)
        assertThat(firstMatchingTokenImage("Palo Alto Networks")).isEqualTo(TokenImage.PaloAltoNetworks)
        assertThat(firstMatchingTokenImage("VMware")).isEqualTo(TokenImage.VMware)
        assertThat(firstMatchingTokenImage("VMWare")).isEqualTo(TokenImage.VMware)
        assertThat(firstMatchingTokenImage("Red Hat")).isEqualTo(TokenImage.RedHat)
        assertThat(firstMatchingTokenImage("Arch Linux")).isEqualTo(TokenImage.ArchLinux)
        assertThat(firstMatchingTokenImage("MATLAB")).isEqualTo(TokenImage.MathWorks)

        // 3. Productivity, SaaS & Collaboration
        assertThat(firstMatchingTokenImage("Box")).isEqualTo(TokenImage.Box)
        assertThat(firstMatchingTokenImage("Box.com")).isEqualTo(TokenImage.Box)
        assertThat(firstMatchingTokenImage("Dropbox")).isEqualTo(TokenImage.Dropbox)
        assertThat(firstMatchingTokenImage("Mailbox")).isEqualTo(TokenImage.Mailbox)
        assertThat(firstMatchingTokenImage("Mapbox")).isEqualTo(TokenImage.Mapbox)
        assertThat(firstMatchingTokenImage("Sandbox")).isNull()
        assertThat(firstMatchingTokenImage("Coda")).isEqualTo(TokenImage.Coda)
        assertThat(firstMatchingTokenImage("Shortcut")).isEqualTo(TokenImage.Shortcut)
        assertThat(firstMatchingTokenImage("Wix")).isEqualTo(TokenImage.Wix)
        assertThat(firstMatchingTokenImage("HubSpot")).isEqualTo(TokenImage.HubSpot)
        assertThat(firstMatchingTokenImage("Hubspot")).isEqualTo(TokenImage.HubSpot)
        assertThat(firstMatchingTokenImage("Cal.com")).isEqualTo(TokenImage.CalCom)
        assertThat(firstMatchingTokenImage("Rocket.Chat")).isEqualTo(TokenImage.RocketChat)

        // 4. Privacy, Email, Security & VPN
        assertThat(firstMatchingTokenImage("Windscribe")).isEqualTo(TokenImage.Windscribe)
        assertThat(firstMatchingTokenImage("IVPN")).isEqualTo(TokenImage.IVPN)
        assertThat(firstMatchingTokenImage("Control D")).isEqualTo(TokenImage.ControlD)
        assertThat(firstMatchingTokenImage("HEY")).isEqualTo(TokenImage.HEY)
        assertThat(firstMatchingTokenImage("hey.com")).isEqualTo(TokenImage.HEY)
        assertThat(firstMatchingTokenImage("They")).isNull()
        assertThat(firstMatchingTokenImage("mail.com")).isEqualTo(TokenImage.MailCom)
        assertThat(firstMatchingTokenImage("WEB.DE")).isEqualTo(TokenImage.WebDe)
        assertThat(firstMatchingTokenImage("ESET")).isEqualTo(TokenImage.ESET)
        assertThat(firstMatchingTokenImage("Reset")).isNull()
        assertThat(firstMatchingTokenImage("VirusTotal")).isEqualTo(TokenImage.VirusTotal)

        // 5. Finance, Retail, Tax, Healthcare & Government
        assertThat(firstMatchingTokenImage("Wise")).isEqualTo(TokenImage.Wise)
        assertThat(firstMatchingTokenImage("TransferWise")).isEqualTo(TokenImage.Wise)
        assertThat(firstMatchingTokenImage("ConnectWise")).isEqualTo(TokenImage.ConnectWiseManage)
        assertThat(firstMatchingTokenImage("Clockwise")).isNull()
        assertThat(firstMatchingTokenImage("Square")).isEqualTo(TokenImage.Square)
        assertThat(firstMatchingTokenImage("Square Enix")).isEqualTo(TokenImage.SquareEnix)
        assertThat(firstMatchingTokenImage("Squarespace")).isEqualTo(TokenImage.Squarespace)
        assertThat(firstMatchingTokenImage("Interactive Brokers")).isEqualTo(TokenImage.InteractiveBrokers)
        assertThat(firstMatchingTokenImage("IBKR")).isEqualTo(TokenImage.InteractiveBrokers)
        assertThat(firstMatchingTokenImage("SoFi")).isEqualTo(TokenImage.SoFi)
        assertThat(firstMatchingTokenImage("Sofia")).isNull()
        assertThat(firstMatchingTokenImage("YNAB")).isEqualTo(TokenImage.YNAB)
        assertThat(firstMatchingTokenImage("You Need A Budget")).isEqualTo(TokenImage.YNAB)
        assertThat(firstMatchingTokenImage("H&R Block")).isEqualTo(TokenImage.HRBlock)
        assertThat(firstMatchingTokenImage("ID.me")).isEqualTo(TokenImage.IDme)
        assertThat(firstMatchingTokenImage("USCIS")).isEqualTo(TokenImage.USCIS)
        assertThat(firstMatchingTokenImage("Global Entry")).isEqualTo(TokenImage.GlobalEntry)
        assertThat(firstMatchingTokenImage("NHS")).isEqualTo(TokenImage.NHS)
        assertThat(firstMatchingTokenImage("OKX")).isEqualTo(TokenImage.OKX)
        assertThat(firstMatchingTokenImage("BitPay")).isEqualTo(TokenImage.BitPay)
        assertThat(firstMatchingTokenImage("Bitpay")).isEqualTo(TokenImage.BitPay)

        // 6. Gaming, Entertainment, Homelab & Telecom / IoT
        assertThat(firstMatchingTokenImage("Gaijin")).isEqualTo(TokenImage.Gaijin)
        assertThat(firstMatchingTokenImage("Gaijin Entertainment")).isEqualTo(TokenImage.Gaijin)
        assertThat(firstMatchingTokenImage("Nexus Mods")).isEqualTo(TokenImage.NexusMods)
        assertThat(firstMatchingTokenImage("Plex")).isEqualTo(TokenImage.Plex)
        assertThat(firstMatchingTokenImage("Duplex")).isNull()
        assertThat(firstMatchingTokenImage("QNAP")).isEqualTo(TokenImage.QNAP)
        assertThat(firstMatchingTokenImage("Uptime Kuma")).isEqualTo(TokenImage.UptimeKuma)
        assertThat(firstMatchingTokenImage("Paperless-ngx")).isEqualTo(TokenImage.PaperlessNgx)
        assertThat(firstMatchingTokenImage("TP-Link")).isEqualTo(TokenImage.TPLink)
        assertThat(firstMatchingTokenImage("Philips Hue")).isEqualTo(TokenImage.Philips)
        assertThat(firstMatchingTokenImage("T-Mobile")).isEqualTo(TokenImage.TMobile)
        assertThat(firstMatchingTokenImage("Vodafone")).isEqualTo(TokenImage.Vodafone)
    }

    @Test
    fun matchesLabelFallbackWhenIssuerIsBlankAndModernAliases() {
        // When issuer is blank/null, all built-in icons fall back to matching label
        assertThat(firstMatchingTokenImage(null, "Google (alice@example.com)")).isEqualTo(TokenImage.Google)
        assertThat(firstMatchingTokenImage("", "AWS production")).isEqualTo(TokenImage.AmazonWebServices)
        assertThat(firstMatchingTokenImage("   ", "Discord personal")).isEqualTo(TokenImage.Discord)

        // When issuer is non-blank and unmatched, non-GitHub icons do not bleed into label
        assertThat(firstMatchingTokenImage("Acme Internal", "alice@google.com")).isNull()
        assertThat(firstMatchingTokenImage("Custom Service", "user@github.com")).isEqualTo(TokenImage.GitHub)

        // Modern rebrand aliases
        assertThat(firstMatchingTokenImage("Microsoft 365")).isEqualTo(TokenImage.Office)
        assertThat(firstMatchingTokenImage("Office 365")).isEqualTo(TokenImage.Office)
        assertThat(firstMatchingTokenImage("Microsoft")).isEqualTo(TokenImage.Microsoft)
        assertThat(firstMatchingTokenImage("EA App")).isEqualTo(TokenImage.Origin)
        assertThat(firstMatchingTokenImage("Equinix Metal")).isEqualTo(TokenImage.Packet)
        assertThat(firstMatchingTokenImage("x.com")).isEqualTo(TokenImage.Twitter)
    }

    @Test
    fun fromNameResolvesBuiltInIconsExactlyAndCaseInsensitively() {
        assertThat(TokenImage.fromName("GitHub")).isEqualTo(TokenImage.GitHub)
        assertThat(TokenImage.fromName("github")).isEqualTo(TokenImage.GitHub)
        assertThat(TokenImage.fromName("  AmazonWebServices  ")).isEqualTo(TokenImage.AmazonWebServices)
        assertThat(TokenImage.fromName("OnePassword")).isEqualTo(TokenImage.OnePassword)
        assertThat(TokenImage.fromName(null)).isNull()
        assertThat(TokenImage.fromName("")).isNull()
        assertThat(TokenImage.fromName("   ")).isNull()
        assertThat(TokenImage.fromName("DoesNotExist")).isNull()
    }
}
