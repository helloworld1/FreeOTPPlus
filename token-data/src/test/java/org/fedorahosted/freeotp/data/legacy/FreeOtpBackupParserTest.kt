package org.fedorahosted.freeotp.data.legacy

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.google.android.apps.authenticator.Base32String
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.TokenImportMode
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.nio.charset.StandardCharsets
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class FreeOtpBackupParserTest {

    private val gson = Gson()

    @Test
    fun parseV2ExternalBackup_roundTripsTotpHotpSteamAndUnicodePassword() {
        val password = "MyM4sterPassw0rd-火尸木火"
        val githubTotp = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "GitHub",
            label = "alice@example.com",
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA256",
            digits = 6,
            period = 30,
            imagePath = "https://untrusted.example/logo.png"
        )
        val redHatHotp = OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "Red Hat",
            label = "OATH38714F5A",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            algorithm = "SHA512",
            digits = 8,
            period = 60,
            initialCounter = 42L
        )
        val steamTotp = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "Steam",
            label = "gamer1",
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA1",
            digits = 5,
            period = 30
        )

        val backupBytes = FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
            password = password,
            tokens = listOf(redHatHotp, steamTotp, githubTotp),
            iterations = 2_000,
            pbkdf2Algorithm = "PBKDF2withHmacSHA512",
            gson = gson
        )

        val inspection = FreeOtpBackupParser.inspectBackup(backupBytes)
        assertThat(inspection.requiresPassword).isTrue()

        val parsed = FreeOtpBackupParser.parseBackup(backupBytes, password.toCharArray(), gson)
        assertThat(parsed).hasSize(3)

        // Ordered deterministically by issuer/label: GitHub, Red Hat, Steam
        assertThat(parsed[0].issuer).isEqualTo("GitHub")
        assertThat(parsed[0].label).isEqualTo("alice@example.com")
        assertThat(parsed[0].tokenType).isEqualTo(OtpTokenType.TOTP)
        assertThat(parsed[0].algorithm).isEqualTo("SHA256")
        assertThat(parsed[0].digits).isEqualTo(6)
        assertThat(parsed[0].secret).isEqualTo("JBSWY3DPEHPK3PXP")

        assertThat(parsed[1].issuer).isEqualTo("Red Hat")
        assertThat(parsed[1].label).isEqualTo("OATH38714F5A")
        assertThat(parsed[1].tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(parsed[1].algorithm).isEqualTo("SHA512")
        assertThat(parsed[1].digits).isEqualTo(8)
        assertThat(parsed[1].period).isEqualTo(60)
        assertThat(parsed[1].counter).isEqualTo(42L)
        assertThat(parsed[1].secret).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

        assertThat(parsed[2].issuer).isEqualTo("Steam")
        assertThat(parsed[2].label).isEqualTo("gamer1")
        assertThat(parsed[2].digits).isEqualTo(5)

        // Verify valid OTP codes can be generated from all restored tokens
        val codeUtil = TokenCodeUtil()
        assertThat(codeUtil.generateTokenCode(parsed[0]).currentCode).hasLength(6)
        assertThat(codeUtil.generateTokenCode(parsed[1]).currentCode).hasLength(8)
        assertThat(codeUtil.generateTokenCode(parsed[2]).currentCode).hasLength(5)
    }

    @Test
    fun parseV2ExternalBackup_wrongOrEmptyPasswordThrowsInvalidEncryptionKeyException() {
        val token = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "GitLab",
            label = "dev@redhat.com",
            secret = "JBSWY3DPEHPK3PXP"
        )
        val backupBytes = FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
            password = "CorrectPassword123!",
            tokens = listOf(token),
            iterations = 1_000,
            gson = gson
        )

        assertThrows(InvalidEncryptionKeyException::class.java) {
            FreeOtpBackupParser.parseBackup(backupBytes, "WrongPassword!".toCharArray(), gson)
        }
        assertThrows(InvalidEncryptionKeyException::class.java) {
            FreeOtpBackupParser.parseBackup(backupBytes, CharArray(0), gson)
        }
        assertThrows(InvalidEncryptionKeyException::class.java) {
            FreeOtpBackupParser.parseBackup(backupBytes, null, gson)
        }
    }

    @Test
    fun parseV2ExternalBackup_corruptedTokenCiphertextThrowsIllegalArgumentNotBadPassword() {
        val password = "ValidMasterPassword"
        val token = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "GitHub",
            label = "bob",
            secret = "JBSWY3DPEHPK3PXP"
        )
        val validBytes = FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
            password = password,
            tokens = listOf(token),
            iterations = 1_000,
            gson = gson
        )
        val map = HashMap(FreeOtpBackupParser.parseBackupMap(validBytes))
        val uuid = map.keys.first { it != "masterKey" && !it.endsWith("-token") }
        // Tamper with the token's EncryptedKey JSON while keeping masterKey intact
        map[uuid] = """{"key":"{\"mCipherText\":[1,2,3,4],\"mParameters\":[4,12,0,1,2,3,4,5,6,7,8,9,10,11],\"mCipher\":\"AES/GCM/NoPadding\",\"mToken\":\"HmacSHA1\"}"}"""

        val tamperedBytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { it.writeObject(map) }
            bos.toByteArray()
        }

        assertThrows(IllegalArgumentException::class.java) {
            FreeOtpBackupParser.parseBackup(tamperedBytes, password.toCharArray(), gson)
        }
    }

    @Test
    fun pbkdf2HmacFallback_matchesJcaSecretKeyFactoryForAsciiAndUtf8Passwords() {
        val salt = ByteArray(32) { idx -> (idx * 7 + 3).toByte() }
        for (pwd in listOf("MyM4sterPassw0rd", "火尸木火1十寥日烤- café 🔐")) {
            val chars = pwd.toCharArray()
            val spec = PBEKeySpec(chars, salt, 1_500, 256)
            val jcaBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
            val fallbackBytes = FreeOtpBackupParser.pbkdf2HmacFallback(
                passphrase = chars,
                salt = salt,
                iterations = 1_500,
                derivedKeyBytes = 32,
                hmacAlgorithm = "HmacSHA512"
            )
            assertThat(fallbackBytes).isEqualTo(jcaBytes)
        }
    }

    @Test
    fun parseGcmParameterSpec_supportsDerSequenceOctetStringAndRawIv() {
        val iv = ByteArray(12) { (it + 1).toByte() }

        // 1. RFC 5084 DER SEQUENCE with explicit 16-byte ICV length: 30 11 04 0c <12B IV> 02 01 10
        val derSeqWithTag = byteArrayOf(0x30, 0x11, 0x04, 0x0C) + iv + byteArrayOf(0x02, 0x01, 0x10)
        val spec1 = FreeOtpBackupParser.parseGcmParameterSpec(derSeqWithTag)
        assertThat(spec1.iv).isEqualTo(iv)
        assertThat(spec1.tLen).isEqualTo(128)

        // 2. DER SEQUENCE omitting optional ICV length: 30 0e 04 0c <12B IV>
        val derSeqDefaultTag = byteArrayOf(0x30, 0x0E, 0x04, 0x0C) + iv
        val spec2 = FreeOtpBackupParser.parseGcmParameterSpec(derSeqDefaultTag)
        assertThat(spec2.iv).isEqualTo(iv)
        assertThat(spec2.tLen).isEqualTo(128)

        // 3. DER OCTET STRING: 04 0c <12B IV>
        val derOctet = byteArrayOf(0x04, 0x0C) + iv
        val spec3 = FreeOtpBackupParser.parseGcmParameterSpec(derOctet)
        assertThat(spec3.iv).isEqualTo(iv)
        assertThat(spec3.tLen).isEqualTo(128)

        // 4. Raw 12-byte IV
        val spec4 = FreeOtpBackupParser.parseGcmParameterSpec(iv)
        assertThat(spec4.iv).isEqualTo(iv)
        assertThat(spec4.tLen).isEqualTo(128)
    }

    @Test
    fun parseSharedPreferencesXml_supportsV2EncryptedAndV1UnencryptedMaps() {
        // 1. V2 XML (`tokenBackup.xml`) with issuerInt-only ("Red Hat") & issuerAlt/labelAlt overrides
        val password = "XmlBackupPassword"
        val baseBytes = FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
            password = password,
            tokens = listOf(
                OtpTokenFactory.create(
                    type = OtpTokenType.TOTP,
                    issuer = "OriginalIssuer",
                    label = "orig_label",
                    secret = "JBSWY3DPEHPK3PXP"
                )
            ),
            iterations = 1_000,
            gson = gson
        )
        val rawMap = FreeOtpBackupParser.parseBackupMap(baseBytes).toMutableMap()
        val tokenUuid = rawMap.keys.first { it.endsWith("-token") }
        // Simulate upstream Token JSON where issuerExt is null, issuerInt is set, and labelAlt overrides label
        rawMap[tokenUuid] = """{"algo":"SHA1","issuerInt":"Red Hat","issuerAlt":"Red Hat SSO","label":"old","labelAlt":"alice@redhat.com","type":"TOTP"}"""

        val v2Xml = buildString {
            append("""<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>""")
            append("""<boolean name="restoreComplete" value="true" />""")
            for ((k, v) in rawMap) {
                val escaped = (v as String)
                    .replace("&", "&amp;")
                    .replace("\"", "&quot;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                append("""<string name="$k">$escaped</string>""")
            }
            append("</map>")
        }
        val v2Bytes = v2Xml.toByteArray(StandardCharsets.UTF_8)
        assertThat(FreeOtpBackupParser.inspectBackup(v2Bytes).requiresPassword).isTrue()

        val v2Parsed = FreeOtpBackupParser.parseBackup(v2Bytes, password.toCharArray(), gson)
        assertThat(v2Parsed).hasSize(1)
        assertThat(v2Parsed[0].issuer).isEqualTo("Red Hat SSO")
        assertThat(v2Parsed[0].label).isEqualTo("alice@redhat.com")
        assertThat(v2Parsed[0].secret).isEqualTo("JBSWY3DPEHPK3PXP")

        // 2. V1 XML (`tokens.xml`) with unencrypted URI + legacy JSON secret array
        val secretBytes = Base32String.decode("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        val secretJsonArray = secretBytes.joinToString(",", prefix = "[", postfix = "]")
        val v1Xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="tokenOrder">[&quot;GitHub:octocat&quot;,&quot;LegacyJson&quot;]</string>
                <string name="GitHub:octocat">otpauth://totp/GitHub:octocat?secret=JBSWY3DPEHPK3PXP&amp;issuer=GitHub</string>
                <string name="LegacyJson">{&quot;algo&quot;:&quot;SHA256&quot;,&quot;issuerExt&quot;:&quot;GitLab&quot;,&quot;label&quot;:&quot;ops&quot;,&quot;digits&quot;:6,&quot;period&quot;:30,&quot;type&quot;:&quot;HOTP&quot;,&quot;counter&quot;:9,&quot;secret&quot;:$secretJsonArray}</string>
            </map>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertThat(FreeOtpBackupParser.inspectBackup(v1Xml).requiresPassword).isFalse()
        val v1Parsed = FreeOtpBackupParser.parseBackup(v1Xml, null, gson)
        assertThat(v1Parsed).hasSize(2)
        assertThat(v1Parsed[0].issuer).isEqualTo("GitHub")
        assertThat(v1Parsed[0].label).isEqualTo("octocat")
        assertThat(v1Parsed[1].issuer).isEqualTo("GitLab")
        assertThat(v1Parsed[1].tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(v1Parsed[1].counter).isEqualTo(9L)
    }

    private class DisallowedGadget : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    @Test
    fun security_rejectsDisallowedJavaSerializedClassesAndXxeXml() {
        // 1. Disallowed Serializable gadget inside HashMap
        val gadgetMap = HashMap<String, Any>()
        gadgetMap["evil"] = DisallowedGadget()
        val gadgetBytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { it.writeObject(gadgetMap) }
            bos.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            FreeOtpBackupParser.inspectBackup(gadgetBytes)
        }

        // 2. Non-Map root object (ArrayList)
        val listBytes = ByteArrayOutputStream().use { bos ->
            ObjectOutputStream(bos).use { it.writeObject(ArrayList<String>()) }
            bos.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            FreeOtpBackupParser.inspectBackup(listBytes)
        }

        // 3. XXE DOCTYPE / ENTITY in XML
        val xxeXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE map [<!ENTITY xxe SYSTEM "file:///etc/hosts">]>
            <map><string name="tokenOrder">&xxe;</string></map>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            FreeOtpBackupParser.inspectBackup(xxeXml)
        }
    }

    @Test
    fun storeImportedFreeOtpTokens_appendAndReplaceAllReEncryptInTokenEncryptedMode(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<OtpTokenDatabase>()
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        try {
            val prefs = SimpleMemoryPrefs()
            val cryptoManager = TokenCryptoManager(prefs, db)
            val dao = db.otpTokenDao()

            // Seed 1 existing token and enable FreeOTP+ vault encryption
            val existing = OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "ExistingVault",
                label = "vault@example.com",
                secret = "JBSWY3DPEHPK3PXP"
            ).copy(id = 1L, ordinal = 10L)
            dao.insert(existing)

            val setupMaterial = cryptoManager.deriveSetupMaterial("FreeOtpPlusVaultKey123!".toCharArray())
            cryptoManager.enableTokenEncryption(setupMaterial, null)

            // Parse a FreeOTP backup encrypted with a DIFFERENT password ("OldFreeOtpPwd")
            val backupBytes = FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
                password = "OldFreeOtpPwd",
                tokens = listOf(
                    OtpTokenFactory.create(
                        type = OtpTokenType.TOTP,
                        issuer = "GitHub",
                        label = "alice",
                        secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                    ),
                    OtpTokenFactory.create(
                        type = OtpTokenType.HOTP,
                        issuer = "Red Hat",
                        label = "bob",
                        secret = "JBSWY3DPEHPK3PXP",
                        initialCounter = 5L
                    )
                ),
                iterations = 1_000,
                gson = gson
            )
            val parsedPlain = FreeOtpBackupParser.parseBackup(backupBytes, "OldFreeOtpPwd".toCharArray(), gson)

            // 1. APPEND: places encrypted GitHub (8) & Red Hat (9) at the TOP of the list before ExistingVault (10)
            ImportExportUtil.storeImportedFreeOtpTokens(
                plaintextTokens = parsedPlain,
                mode = TokenImportMode.APPEND,
                filesDir = null,
                otpTokenDatabase = db,
                tokenCryptoManager = cryptoManager,
                gson = gson
            )
            val afterAppend = dao.getAll().first()
            assertThat(afterAppend).hasSize(3)
            assertThat(afterAppend.map { it.issuer }).containsExactly("GitHub", "Red Hat", "ExistingVault").inOrder()
            assertThat(afterAppend.map { it.ordinal }).containsExactly(8L, 9L, 10L).inOrder()
            afterAppend.forEach { stored ->
                assertThat(stored.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
                assertThat(stored.secret).doesNotContain("JBSWY3DPEHPK3PXP")
                assertThat(stored.secret).doesNotContain("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            }
            assertThat(cryptoManager.decryptSecret(afterAppend[0])).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
            assertThat(cryptoManager.decryptSecret(afterAppend[1])).isEqualTo("JBSWY3DPEHPK3PXP")
            assertThat(cryptoManager.decryptSecret(afterAppend[2])).isEqualTo("JBSWY3DPEHPK3PXP")

            // 2. REPLACE_ALL: clears all 3 and stores only GitHub & Red Hat re-encrypted under vault key
            ImportExportUtil.storeImportedFreeOtpTokens(
                plaintextTokens = parsedPlain,
                mode = TokenImportMode.REPLACE_ALL,
                filesDir = null,
                otpTokenDatabase = db,
                tokenCryptoManager = cryptoManager,
                gson = gson
            )
            val afterReplace = dao.getAll().first()
            assertThat(afterReplace).hasSize(2)
            assertThat(afterReplace.map { it.issuer }).containsExactly("GitHub", "Red Hat").inOrder()
            assertThat(afterReplace.map { it.ordinal }).containsExactly(1L, 2L).inOrder()
            afterReplace.forEach { stored ->
                assertThat(stored.encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
            }
            assertThat(cryptoManager.decryptSecret(afterReplace[0])).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
        } finally {
            db.close()
        }
    }

    private class SimpleMemoryPrefs : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val staged = LinkedHashMap<String, Any?>()
            private val removals = HashSet<String>()
            private var clearAll = false
            override fun putString(key: String, value: String?) = apply { if (value == null) removals.add(key) else staged[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { if (values == null) removals.add(key) else staged[key] = values }
            override fun putInt(key: String, value: Int) = apply { staged[key] = value }
            override fun putLong(key: String, value: Long) = apply { staged[key] = value }
            override fun putFloat(key: String, value: Float) = apply { staged[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { staged[key] = value }
            override fun remove(key: String) = apply { removals.add(key); staged.remove(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                map.putAll(staged)
                return true
            }
            override fun apply() { commit() }
        }
    }
}
