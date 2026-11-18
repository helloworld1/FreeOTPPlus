package org.fedorahosted.freeotp.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.legacy.SavedTokens
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
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
            imagePath = null,
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

        // Round-trip decrypt in new app preserves full multi-category list
        val decrypted = ImportExportUtil.decryptJsonBackup(gson, bundleJson, "backupKey123".toCharArray())
        assertThat(decrypted).hasSize(1)
        assertThat(decrypted[0].categories).containsExactly("Work", "Cloud").inOrder()
    }
}
