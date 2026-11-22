package org.fedorahosted.freeotp.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRulesConfigTest {

    private data class RuleEntry(
        val domain: String,
        val path: String,
        val requireFlags: String = ""
    )

    private fun resolveAppFile(relativeToApp: String): File {
        val direct = File(relativeToApp)
        if (direct.exists()) return direct
        val fromRoot = File("app", relativeToApp)
        if (fromRoot.exists()) return fromRoot
        throw AssertionError("Could not locate $relativeToApp from ${File(".").absolutePath}")
    }

    private fun parseXmlRoot(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun readRuleEntries(container: Element, tagName: String): List<RuleEntry> {
        val children = container.childNodes
        val result = mutableListOf<RuleEntry>()
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            if (node.tagName == tagName) {
                result.add(
                    RuleEntry(
                        domain = node.getAttribute("domain"),
                        path = node.getAttribute("path"),
                        requireFlags = node.getAttribute("requireFlags")
                    )
                )
            }
        }
        return result
    }

    @Test
    fun manifestConfiguresAllowBackupWithExplicitBackupAndDataExtractionRules() {
        val manifestRoot = parseXmlRoot(resolveAppFile("src/main/AndroidManifest.xml"))
        val androidNs = "http://schemas.android.com/apk/res/android"
        val appElement = manifestRoot.getElementsByTagName("application").item(0) as Element

        assertThat(appElement.getAttributeNS(androidNs, "allowBackup")).isEqualTo("true")
        assertThat(appElement.getAttributeNS(androidNs, "fullBackupContent")).isEqualTo("@xml/backup_rules")
        assertThat(appElement.getAttributeNS(androidNs, "dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")
    }

    @Test
    fun backupAndDataExtractionRulesEnforceClientSideEncryptionAndExcludeLegacyTokensPrefs() {
        val expectedBaseIncludes = listOf(
            RuleEntry("database", "."),
            RuleEntry("sharedpref", "."),
            RuleEntry("file", ".")
        )
        val expectedExcludes = listOf(
            RuleEntry("sharedpref", "tokens.xml"),
            RuleEntry("sharedpref", "tokens.bak")
        )

        // API 23-27 fallback (pre-clientSideEncryption OS APIs)
        val fullBackupBase = parseXmlRoot(resolveAppFile("src/main/res/xml/backup_rules.xml"))
        assertThat(fullBackupBase.tagName).isEqualTo("full-backup-content")
        assertThat(readRuleEntries(fullBackupBase, "include")).containsExactlyElementsIn(expectedBaseIncludes)
        assertThat(readRuleEntries(fullBackupBase, "exclude")).containsExactlyElementsIn(expectedExcludes)

        // API 28 (clientSideEncryption introduced)
        val expectedV28Includes = expectedBaseIncludes.map { it.copy(requireFlags = "clientSideEncryption") }
        val fullBackupV28 = parseXmlRoot(resolveAppFile("src/main/res/xml-v28/backup_rules.xml"))
        assertThat(fullBackupV28.tagName).isEqualTo("full-backup-content")
        assertThat(readRuleEntries(fullBackupV28, "include")).containsExactlyElementsIn(expectedV28Includes)
        assertThat(readRuleEntries(fullBackupV28, "exclude")).containsExactlyElementsIn(expectedExcludes)

        // API 29-30 (both clientSideEncryption cloud backup and deviceToDeviceTransfer)
        val expectedV29Includes = expectedV28Includes +
            expectedBaseIncludes.map { it.copy(requireFlags = "deviceToDeviceTransfer") }
        val fullBackupV29 = parseXmlRoot(resolveAppFile("src/main/res/xml-v29/backup_rules.xml"))
        assertThat(fullBackupV29.tagName).isEqualTo("full-backup-content")
        assertThat(readRuleEntries(fullBackupV29, "include")).containsExactlyElementsIn(expectedV29Includes)
        assertThat(readRuleEntries(fullBackupV29, "exclude")).containsExactlyElementsIn(expectedExcludes)

        // API 31+ data extraction rules
        val extractionRoot = parseXmlRoot(resolveAppFile("src/main/res/xml/data_extraction_rules.xml"))
        assertThat(extractionRoot.tagName).isEqualTo("data-extraction-rules")

        val cloudBackup = extractionRoot.getElementsByTagName("cloud-backup").item(0) as Element
        assertThat(cloudBackup.getAttribute("disableIfNoEncryptionCapabilities")).isEqualTo("true")
        assertThat(readRuleEntries(cloudBackup, "include")).containsExactlyElementsIn(expectedBaseIncludes)
        assertThat(readRuleEntries(cloudBackup, "exclude")).containsExactlyElementsIn(expectedExcludes)

        val deviceTransfer = extractionRoot.getElementsByTagName("device-transfer").item(0) as Element
        assertThat(deviceTransfer.hasAttribute("disableIfNoEncryptionCapabilities")).isFalse()
        assertThat(readRuleEntries(deviceTransfer, "include")).containsExactlyElementsIn(expectedBaseIncludes)
        assertThat(readRuleEntries(deviceTransfer, "exclude")).containsExactlyElementsIn(expectedExcludes)
    }
}
