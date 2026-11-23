package org.fedorahosted.freeotp.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.legacy.SavedTokens
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.junit.Assert.assertThrows
import org.junit.Test

class CategoryUtilAndBackupCompatTest {

    private data class OldAppTokenJson(
        val label: String?,
        val category: String?
    )

    private data class OldAppSavedTokensJson(
        val tokens: List<OldAppTokenJson>,
        val tokenOrder: List<String>
    )

    @Test
    fun normalizeAndJsonSerialization_handlesSpecialCharactersAndDeduplicatesCaseInsensitively() {
        val raw = listOf("  Work ", "work", "Cloud, Infra", "Quote\"Test\\Path", "", "   ", "個人 🌟")
        val normalized = CategoryUtil.normalizeCategories(raw)
        assertThat(normalized).containsExactly("Work", "Cloud, Infra", "Quote\"Test\\Path", "個人 🌟").inOrder()

        val json = CategoryUtil.toJson(normalized)
        assertThat(CategoryUtil.fromJson(json)).containsExactlyElementsIn(normalized).inOrder()
        assertThat(CategoryUtil.toJson(emptyList())).isEqualTo("[]")
        assertThat(CategoryUtil.fromJson("[]")).isEmpty()
        assertThat(CategoryUtil.fromJson(null)).isEmpty()
        assertThat(CategoryUtil.formatForDisplay(listOf("Work", "Cloud"))).isEqualTo("Work • Cloud")
    }

    @Test
    fun legacyPlaintextJson_backwardAndForwardCompatible() {
        val gson = Gson()

        // 1. Backward compat: old backup with single "category" string and no "categories" array
        val oldJson = """
            {
              "tokens": [
                {
                  "issuerExt": "GitHub",
                  "label": "alice",
                  "type": "TOTP",
                  "algo": "SHA1",
                  "secret": [72, 101, 108, 108, 111],
                  "digits": 6,
                  "counter": 0,
                  "period": 30,
                  "category": "Work"
                },
                {
                  "issuerExt": "GitLab",
                  "label": "bob",
                  "type": "TOTP",
                  "algo": "SHA1",
                  "secret": [72, 101, 108, 108, 111],
                  "digits": 6,
                  "counter": 0,
                  "period": 30
                }
              ],
              "tokenOrder": ["GitHub:alice", "GitLab:bob"]
            }
        """.trimIndent()

        val importedOld = MigrationUtil.convertSavedTokens(gson.fromJson(oldJson, SavedTokens::class.java))
        assertThat(importedOld).hasSize(2)
        assertThat(importedOld[0].categories).containsExactly("Work")
        assertThat(importedOld[1].categories).isEmpty()

        // 2. Forward & round-trip compat: new backup with multiple categories
        val newBackupJson = """
            {
              "tokens": [
                {
                  "issuerExt": "GitHub",
                  "label": "alice",
                  "type": "TOTP",
                  "algo": "SHA1",
                  "secret": [72, 101, 108, 108, 111],
                  "digits": 6,
                  "counter": 0,
                  "period": 30,
                  "category": "Work",
                  "categories": ["Work", "Cloud", "DevOps"]
                }
              ],
              "tokenOrder": ["GitHub:alice"]
            }
        """.trimIndent()

        // Imported by new app -> full multi-category list preserved
        val importedNew = MigrationUtil.convertSavedTokens(gson.fromJson(newBackupJson, SavedTokens::class.java))
        assertThat(importedNew).hasSize(1)
        assertThat(importedNew[0].categories).containsExactly("Work", "Cloud", "DevOps").inOrder()

        // Imported by old app -> unknown "categories" ignored, primary "category" preserved
        val parsedByOldApp = gson.fromJson(newBackupJson, OldAppSavedTokensJson::class.java)
        assertThat(parsedByOldApp.tokens[0].category).isEqualTo("Work")
    }

    @Test
    fun encryptedJsonBackup_multiCategoryBackwardAndForwardCompatible() {
        val gson = Gson()
        val salt = CryptoEngine.generateSalt()
        val iterations = 1_500
        val backupKey = CryptoEngine.deriveKey("backupKey123".toCharArray(), salt, iterations)
        val verifier = CryptoEngine.createKeyVerifier(backupKey)
        val encryptedSecret = CryptoEngine.encryptSecret("JBSWY3DPEHPK3PXP", backupKey)

        val token = OtpToken(
            id = 1L,
            ordinal = 1L,
            issuer = "AWS",
            label = "prod",
            imagePath = "builtin:AmazonWebServices",
            tokenType = OtpTokenType.TOTP,
            algorithm = "SHA1",
            secret = encryptedSecret,
            digits = 6,
            counter = 0L,
            period = 30,
            encryptionType = EncryptionType.ENCRYPTED,
            categories = listOf("Work", "Cloud")
        )

        val exportedToken = EncryptedExportToken.fromOtpToken(token)
        assertThat(exportedToken.category).isEqualTo("Work")
        assertThat(exportedToken.categories).containsExactly("Work", "Cloud").inOrder()

        val bundleJson = gson.toJson(
            EncryptedExportBundle(
                version = 1,
                encrypted = true,
                kdfSalt = CryptoEngine.encodeBase64(salt),
                kdfIterations = iterations,
                keyVerifier = verifier,
                tokens = listOf(exportedToken)
            )
        )

        // Verify JSON contains both "category": "Work" (for old apps) and "categories": ["Work", "Cloud"] (for new apps)
        val tokenJsonObj = JsonParser.parseString(bundleJson).asJsonObject
            .getAsJsonArray("tokens")[0].asJsonObject
        assertThat(tokenJsonObj.get("category").asString).isEqualTo("Work")
        assertThat(tokenJsonObj.getAsJsonArray("categories").map { it.asString })
            .containsExactly("Work", "Cloud").inOrder()

        // Round-trip decrypt in new app preserves full multi-category list and builtin icon reference
        val decrypted = ImportExportUtil.decryptJsonBackup(gson, bundleJson, "backupKey123".toCharArray())
        assertThat(decrypted).hasSize(1)
        assertThat(decrypted[0].categories).containsExactly("Work", "Cloud").inOrder()
        assertThat(decrypted[0].imagePath).isEqualTo("builtin:AmazonWebServices")
    }

    @Test
    fun otpTokenFactoryCreate_validatesInputsAndPreservesSpecialCharacters() {
        val hotp = OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "My:Issuer",
            label = "alice+1@example.com",
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = "sha256",
            digits = 8,
            period = 30,
            initialCounter = 5L,
            imagePath = "file:///data/user/0/app/files/my+icon 1.png",
            categories = listOf(" Work ", "work", "Infra")
        )
        assertThat(hotp.tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(hotp.issuer).isEqualTo("My:Issuer")
        assertThat(hotp.label).isEqualTo("alice+1@example.com")
        assertThat(hotp.algorithm).isEqualTo("SHA256")
        assertThat(hotp.digits).isEqualTo(8)
        assertThat(hotp.counter).isEqualTo(5L)
        assertThat(hotp.imagePath).isEqualTo("file:///data/user/0/app/files/my+icon 1.png")
        assertThat(hotp.categories).containsExactly("Work", "Infra").inOrder()

        val blankIssuer = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "   ",
            label = "service:bob",
            secret = "JBSWY3DPEHPK3PXP"
        )
        assertThat(blankIssuer.issuer).isNull()
        assertThat(blankIssuer.label).isEqualTo("service:bob")
        assertThat(blankIssuer.counter).isEqualTo(0L)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = null,
                label = "alice",
                secret = "INVALID_0189!"
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "",
                label = "",
                secret = "JBSWY3DPEHPK3PXP"
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "GitHub",
                label = "alice",
                secret = "JBSWY3DPEHPK3PXP",
                algorithm = "NOT_A_MAC"
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OtpTokenFactory.create(
                type = OtpTokenType.HOTP,
                issuer = "GitHub",
                label = "alice",
                secret = "JBSWY3DPEHPK3PXP",
                initialCounter = -1L
            )
        }
    }

    @Test
    fun hotpCreationAndBackupMigration_followsRfc4226AndPreservesExistingSyncedCounts() {
        val codeUtil = TokenCodeUtil()
        val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

        // 1. Newly created HOTP token at counter = 0 generates RFC 4226 Count=0 ("755224") on first use
        val newHotp0 = OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "RFC4226",
            label = "alice",
            secret = rfcSecret,
            algorithm = "SHA1",
            digits = 6,
            initialCounter = 0L
        )
        assertThat(newHotp0.counter).isEqualTo(0L)
        assertThat(codeUtil.generateTokenCode(newHotp0).currentCode).isEqualTo("755224")
        assertThat(codeUtil.generateTokenCode(newHotp0.copy(counter = 1L)).currentCode).isEqualTo("287082")

        // 2. Newly created HOTP token at counter = 5 generates RFC 4226 Count=5 ("254676") on first use
        val newHotp5 = OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "RFC4226",
            label = "bob",
            secret = rfcSecret,
            algorithm = "SHA1",
            digits = 6,
            initialCounter = 5L
        )
        assertThat(newHotp5.counter).isEqualTo(5L)
        assertThat(codeUtil.generateTokenCode(newHotp5).currentCode).isEqualTo("254676")

        // 3. Existing legacy JSON / tokens.xml backup: active counter (e.g. 7) is preserved unchanged,
        //    while legacy unused counter (-1) is normalized to 0
        val gson = Gson()
        val legacyJson = """
            {
              "tokens": [
                {
                  "issuerExt": "SyncedServer",
                  "label": "activeUser",
                  "type": "HOTP",
                  "algo": "SHA1",
                  "secret": [49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48],
                  "digits": 6,
                  "counter": 7,
                  "period": 30
                },
                {
                  "issuerExt": "UnusedLegacy",
                  "label": "newUser",
                  "type": "HOTP",
                  "algo": "SHA1",
                  "secret": [49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48],
                  "digits": 6,
                  "counter": -1,
                  "period": 30
                }
              ],
              "tokenOrder": ["SyncedServer:activeUser", "UnusedLegacy:newUser"]
            }
        """.trimIndent()

        val migratedLegacy = MigrationUtil.convertSavedTokens(gson.fromJson(legacyJson, SavedTokens::class.java))
        assertThat(migratedLegacy).hasSize(2)
        assertThat(migratedLegacy[0].counter).isEqualTo(7L)
        assertThat(codeUtil.generateTokenCode(migratedLegacy[0]).currentCode).isEqualTo("162583") // RFC 4226 Count=7
        assertThat(migratedLegacy[1].counter).isEqualTo(0L)
        assertThat(codeUtil.generateTokenCode(migratedLegacy[1]).currentCode).isEqualTo("755224") // RFC 4226 Count=0
    }

    @Test
    fun splitIssuerAndLabel_preservesColonsInIssuerAndLabelAndSupportsIssuerOnly() {
        assertThat(OtpTokenFactory.splitIssuerAndLabel("Company:Prod:admin", "Company:Prod"))
            .isEqualTo(Triple("Company:Prod", "admin", "Company:Prod"))

        assertThat(OtpTokenFactory.splitIssuerAndLabel("Company:Prod:env:admin", "Company:Prod"))
            .isEqualTo(Triple("Company:Prod", "env:admin", "Company:Prod"))

        assertThat(OtpTokenFactory.splitIssuerAndLabel("Company:Prod:", "Company:Prod"))
            .isEqualTo(Triple("Company:Prod", "", "Company:Prod"))

        assertThat(OtpTokenFactory.splitIssuerAndLabel("GitHub:", "GitHub"))
            .isEqualTo(Triple("GitHub", "", "GitHub"))

        assertThat(OtpTokenFactory.splitIssuerAndLabel(":service:alice", null))
            .isEqualTo(Triple(null, "service:alice", ""))

        assertThat(OtpTokenFactory.splitIssuerAndLabel("alice@example.com", null))
            .isEqualTo(Triple(null, "alice@example.com", ""))
    }

    @Test
    fun inspectJsonBackupString_distinguishesPlainEncryptedAndRejectsInvalidJson() {
        val gson = Gson()

        val validPlain = """{"tokens":[],"tokenOrder":[]}"""
        assertThat(ImportExportUtil.inspectJsonBackupString(gson, validPlain).requiresPassword).isFalse()

        val validEncrypted = """
            {
              "version": 1,
              "encrypted": true,
              "kdfSalt": "YWJjZA==",
              "kdfIterations": 1500,
              "keyVerifier": "dmVyaWZpZXI=",
              "tokens": []
            }
        """.trimIndent()
        assertThat(ImportExportUtil.inspectJsonBackupString(gson, validEncrypted).requiresPassword).isTrue()

        // Rejects empty, non-JSON, missing tokens/tokenOrder (e.g. Aegis JSON), and incomplete encrypted metadata
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportUtil.inspectJsonBackupString(gson, "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportUtil.inspectJsonBackupString(gson, "not json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportUtil.inspectJsonBackupString(gson, """{"version":1,"header":{"slots":null,"params":null},"db":{}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportUtil.inspectJsonBackupString(gson, """{"encrypted":true,"kdfSalt":"","kdfIterations":0,"keyVerifier":""}""")
        }
    }
}
