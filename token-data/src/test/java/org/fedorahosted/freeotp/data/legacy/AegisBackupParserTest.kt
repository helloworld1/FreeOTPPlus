package org.fedorahosted.freeotp.data.legacy

import android.content.SharedPreferences
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

class AegisBackupParserTest {

    private val gson = Gson()

    @Test
    fun scrypt_matchesRfc7914AndUpstreamAegisTestVectors() {
        // RFC 7914 Section 12 official vectors
        val v1 = AegisBackupParser.scrypt(
            passphraseBytes = ByteArray(0),
            salt = ByteArray(0),
            n = 16,
            r = 1,
            p = 1,
            dkLen = 64
        )
        assertThat(AegisBackupParser.toHex(v1)).isEqualTo(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906"
        )

        val v2 = AegisBackupParser.scrypt(
            passphraseBytes = "password".toByteArray(StandardCharsets.UTF_8),
            salt = "NaCl".toByteArray(StandardCharsets.UTF_8),
            n = 1024,
            r = 8,
            p = 16,
            dkLen = 64
        )
        assertThat(AegisBackupParser.toHex(v2)).isEqualTo(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640"
        )

        val v3 = AegisBackupParser.scrypt(
            passphraseBytes = "pleaseletmein".toByteArray(StandardCharsets.UTF_8),
            salt = "SodiumChloride".toByteArray(StandardCharsets.UTF_8),
            n = 16384,
            r = 8,
            p = 1,
            dkLen = 64
        )
        assertThat(AegisBackupParser.toHex(v3)).isEqualTo(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
                "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887"
        )

        // Upstream Aegis SCryptTest.testTrailingNullCollision vector (N=32768, r=1, p=8, dkLen=32)
        val aegisVec = AegisBackupParser.scrypt(
            passphraseBytes = "test".toByteArray(StandardCharsets.UTF_8),
            salt = ByteArray(0),
            n = 32768,
            r = 1,
            p = 8,
            dkLen = 32
        )
        assertThat(AegisBackupParser.toHex(aegisVec)).isEqualTo(
            "41cd8110d0c66ede16f97ce84fd8e2bd2269c9318532a01437789dfbadd1392e"
        )
    }

    @Test
    fun parsePlainGroupedV2_parsesUpstreamAegisEntriesGroupsAndSteamGuard() {
        // Exact upstream test fixture from beemdevelopment/Aegis (`aegis_plain_grouped_v2.json`)
        val plainV2Json = """
            {
                "version": 1,
                "header": {
                    "slots": null,
                    "params": null
                },
                "db": {
                    "version": 2,
                    "entries": [
                        {
                            "type": "totp",
                            "uuid": "3ae6f1ad-2e65-4ed2-a953-1ec0dff2386d",
                            "name": "Mason",
                            "issuer": "Deno",
                            "icon": null,
                            "icon_mime": null,
                            "group": "group1",
                            "info": {
                                "secret": "4SJHB4GSD43FZBAI7C2HLRJGPQ",
                                "algo": "SHA1",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "totp",
                            "uuid": "84b55971-a3d2-4173-a5bb-0aea113dbc17",
                            "name": "James",
                            "issuer": "SPDX",
                            "icon": null,
                            "icon_mime": null,
                            "group": null,
                            "info": {
                                "secret": "5OM4WOOGPLQEF6UGN3CPEOOLWU",
                                "algo": "SHA256",
                                "digits": 7,
                                "period": 20
                            }
                        },
                        {
                            "type": "totp",
                            "uuid": "3deaff2e-f181-4837-80e1-fdf0c54e9363",
                            "name": "Elijah",
                            "issuer": "Airbnb",
                            "icon": null,
                            "icon_mime": null,
                            "group": "group1",
                            "info": {
                                "secret": "7ELGJSGXNCCTV3O6LKJWYFV2RA",
                                "algo": "SHA512",
                                "digits": 8,
                                "period": 50
                            }
                        },
                        {
                            "type": "hotp",
                            "uuid": "0a8c0571-ff6f-4b02-aa4b-50553b4fb4fe",
                            "name": "James",
                            "issuer": "Issuu",
                            "icon": null,
                            "icon_mime": null,
                            "group": "group2",
                            "info": {
                                "secret": "YOOMIXWS5GN6RTBPUFFWKTW5M4",
                                "algo": "SHA1",
                                "digits": 6,
                                "counter": 1
                            }
                        },
                        {
                            "type": "hotp",
                            "uuid": "03e572f2-8ebd-44b0-a57e-e958af74815d",
                            "name": "Benjamin",
                            "issuer": "Air Canada",
                            "icon": null,
                            "icon_mime": null,
                            "group": null,
                            "info": {
                                "secret": "KUVJJOM753IHTNDSZVCNKL7GII",
                                "algo": "SHA256",
                                "digits": 7,
                                "counter": 50
                            }
                        },
                        {
                            "type": "hotp",
                            "uuid": "b25f8815-007f-40f7-a700-ce058ac05435",
                            "name": "Mason",
                            "issuer": "WWE",
                            "icon": null,
                            "icon_mime": null,
                            "group": null,
                            "info": {
                                "secret": "5VAML3X35THCEBVRLV24CGBKOY",
                                "algo": "SHA512",
                                "digits": 8,
                                "counter": 10300
                            }
                        },
                        {
                            "type": "steam",
                            "uuid": "5b11ae3b-6fc3-4d46-8ca7-cf0aea7de920",
                            "name": "Sophia",
                            "issuer": "Boeing",
                            "icon": null,
                            "icon_mime": null,
                            "group": null,
                            "info": {
                                "secret": "JRZCL47CMXVOQMNPZR2F7J4RGI",
                                "algo": "SHA1",
                                "digits": 5,
                                "period": 30
                            }
                        }
                    ]
                }
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        assertThat(AegisBackupParser.inspectBackup(plainV2Json, gson).requiresPassword).isFalse()
        val tokens = AegisBackupParser.parseBackup(plainV2Json, null, null, gson)
        assertThat(tokens).hasSize(7)

        assertThat(tokens[0].issuer).isEqualTo("Deno")
        assertThat(tokens[0].label).isEqualTo("Mason")
        assertThat(tokens[0].categories).containsExactly("group1")
        assertThat(tokens[0].algorithm).isEqualTo("SHA1")
        assertThat(tokens[0].digits).isEqualTo(6)
        assertThat(tokens[0].period).isEqualTo(30)

        assertThat(tokens[1].issuer).isEqualTo("SPDX")
        assertThat(tokens[1].algorithm).isEqualTo("SHA256")
        assertThat(tokens[1].digits).isEqualTo(7)
        assertThat(tokens[1].period).isEqualTo(20)
        assertThat(tokens[1].categories).isEmpty()

        assertThat(tokens[2].issuer).isEqualTo("Airbnb")
        assertThat(tokens[2].algorithm).isEqualTo("SHA512")
        assertThat(tokens[2].digits).isEqualTo(8)
        assertThat(tokens[2].period).isEqualTo(50)
        assertThat(tokens[2].categories).containsExactly("group1")

        assertThat(tokens[3].tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(tokens[3].issuer).isEqualTo("Issuu")
        assertThat(tokens[3].counter).isEqualTo(1L)
        assertThat(tokens[3].categories).containsExactly("group2")

        assertThat(tokens[4].tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(tokens[4].counter).isEqualTo(50L)

        assertThat(tokens[5].tokenType).isEqualTo(OtpTokenType.HOTP)
        assertThat(tokens[5].counter).isEqualTo(10300L)

        // Steam entry with custom issuer "Boeing" preserves Steam Guard generation via issuer="Steam"
        val steamToken = tokens[6]
        assertThat(steamToken.tokenType).isEqualTo(OtpTokenType.TOTP)
        assertThat(steamToken.issuer).isEqualTo("Steam")
        assertThat(steamToken.label).isEqualTo("Boeing (Sophia)")
        assertThat(steamToken.digits).isEqualTo(5)
        val steamCode = TokenCodeUtil().generateTokenCode(steamToken).currentCode
        assertThat(steamCode).isNotNull()
        assertThat(steamCode).hasLength(5)
        steamCode!!.forEach { ch ->
            assertThat("23456789BCDFGHJKMNPQRTVWXY").contains(ch.toString())
        }
    }

    @Test
    fun parsePlainV3AndRawDecryptPyJson_mapsMultiGroupsSavesDeduplicatedIconsAndSkipsXxeIcons() {
        val tempDir = Files.createTempDirectory("aegis_icons_test").toFile()
        try {
            // Minimal valid 1x1 PNG
            val validPngBytes = Base64.Default.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
            )
            val validPngB64 = Base64.Default.encode(validPngBytes)
            val validSvgB64 = Base64.Default.encode(
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><circle cx="8" cy="8" r="6"/></svg>"""
                    .toByteArray(StandardCharsets.UTF_8)
            )
            val xxeSvgB64 = Base64.Default.encode(
                """<?xml version="1.0"?><!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/hosts">]><svg>&xxe;</svg>"""
                    .toByteArray(StandardCharsets.UTF_8)
            )

            // Raw decrypted `db` JSON (as produced by Aegis's `docs/decrypt.py`)
            val rawDbJson = """
                {
                    "version": 3,
                    "groups": [
                        { "uuid": "11111111-2222-3333-4444-555555555555", "name": "Work" },
                        { "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "name": "Cloud" }
                    ],
                    "entries": [
                        {
                            "type": "totp",
                            "uuid": "e1",
                            "name": "alice@corp.example",
                            "issuer": "GitHub",
                            "icon": "$validPngB64",
                            "icon_mime": "image/png",
                            "groups": [
                                "11111111-2222-3333-4444-555555555555",
                                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
                            ],
                            "info": {
                                "secret": "JBSWY3DPEHPK3PXP",
                                "algo": "SHA256",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "totp",
                            "uuid": "e2",
                            "name": "bob@corp.example",
                            "issuer": "GitHub",
                            "icon": "$validPngB64",
                            "icon_mime": "image/png",
                            "groups": ["11111111-2222-3333-4444-555555555555"],
                            "info": {
                                "secret": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                                "algo": "SHA1",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "totp",
                            "uuid": "e3",
                            "name": "svg@corp.example",
                            "issuer": "GitLab",
                            "icon": "$validSvgB64",
                            "icon_mime": "image/svg+xml",
                            "groups": [],
                            "info": {
                                "secret": "JBSWY3DPEHPK3PXP",
                                "algo": "SHA1",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "totp",
                            "uuid": "e4",
                            "name": "evil@corp.example",
                            "issuer": "EvilSvg",
                            "icon": "$xxeSvgB64",
                            "icon_mime": "image/svg+xml",
                            "groups": [],
                            "info": {
                                "secret": "JBSWY3DPEHPK3PXP",
                                "algo": "SHA1",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "motp",
                            "uuid": "e5",
                            "name": "unsupported_motp",
                            "issuer": "Motp",
                            "info": { "secret": "0123456789abcdef", "algo": "MD5", "digits": 6, "period": 10 }
                        }
                    ]
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)

            assertThat(AegisBackupParser.inspectBackup(rawDbJson, gson).requiresPassword).isFalse()
            val tokens = AegisBackupParser.parseBackup(rawDbJson, null, tempDir, gson)
            assertThat(tokens).hasSize(4)

            assertThat(tokens[0].categories).containsExactly("Work", "Cloud").inOrder()
            assertThat(tokens[1].categories).containsExactly("Work")

            // Identical PNG icons are deduplicated to the exact same file inside tempDir
            assertThat(tokens[0].imagePath).isNotNull()
            assertThat(tokens[0].imagePath).isEqualTo(tokens[1].imagePath)
            val savedPngFile = File(URI(tokens[0].imagePath!!))
            assertThat(savedPngFile.parentFile.canonicalFile).isEqualTo(tempDir.canonicalFile)
            assertThat(savedPngFile.extension).isEqualTo("png")
            assertThat(savedPngFile.readBytes()).isEqualTo(validPngBytes)

            // Valid SVG icon saved safely
            assertThat(tokens[2].imagePath).isNotNull()
            val savedSvgFile = File(URI(tokens[2].imagePath!!))
            assertThat(savedSvgFile.extension).isEqualTo("svg")
            assertThat(savedSvgFile.exists()).isTrue()

            // XXE SVG icon is rejected without failing token import
            assertThat(tokens[3].issuer).isEqualTo("EvilSvg")
            assertThat(tokens[3].imagePath).isNull()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun parseEncryptedVault_roundTripsUnicodePasswordWrongPasswordAndLegacyUnrepairedSlot() {
        val dbJson = """
            {
                "version": 3,
                "groups": [{ "uuid": "g1", "name": "Security" }],
                "entries": [
                    {
                        "type": "totp",
                        "uuid": "u1",
                        "name": "sec@example.com",
                        "issuer": "Cloudflare",
                        "groups": ["g1"],
                        "info": {
                            "secret": "JBSWY3DPEHPK3PXP",
                            "algo": "SHA256",
                            "digits": 6,
                            "period": 30
                        }
                    }
                ]
            }
        """.trimIndent()
        val unicodePassword = "Aegis-火尸木火-🔐-Vault!"
        val encryptedBytes = AegisBackupParser.createEncryptedAegisVaultBytesForTesting(
            password = unicodePassword,
            dbJsonObject = dbJson,
            scryptN = 1024,
            scryptR = 8,
            scryptP = 1
        )

        assertThat(AegisBackupParser.inspectBackup(encryptedBytes, gson).requiresPassword).isTrue()

        // Wrong / empty / null password throws InvalidEncryptionKeyException
        assertThrows(InvalidEncryptionKeyException::class.java) {
            AegisBackupParser.parseBackup(encryptedBytes, "WrongPassword".toCharArray(), null, gson)
        }
        assertThrows(InvalidEncryptionKeyException::class.java) {
            AegisBackupParser.parseBackup(encryptedBytes, CharArray(0), null, gson)
        }
        assertThrows(InvalidEncryptionKeyException::class.java) {
            AegisBackupParser.parseBackup(encryptedBytes, null, null, gson)
        }

        // Correct Unicode password unlocks and parses entries + groups
        val parsed = AegisBackupParser.parseBackup(encryptedBytes, unicodePassword.toCharArray(), null, gson)
        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].issuer).isEqualTo("Cloudflare")
        assertThat(parsed[0].label).isEqualTo("sec@example.com")
        assertThat(parsed[0].categories).containsExactly("Security")
        assertThat(parsed[0].secret).isEqualTo("JBSWY3DPEHPK3PXP")

        // Tampering with encrypted `db` ciphertext while keeping slot valid throws IllegalArgumentException
        val tamperedRoot = gson.fromJson(encryptedBytes.toString(StandardCharsets.UTF_8), JsonObject::class.java)
        tamperedRoot.addProperty("db", Base64.Default.encode(ByteArray(32) { 0x42 }))
        val tamperedBytes = gson.toJson(tamperedRoot).toByteArray(StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            AegisBackupParser.parseBackup(tamperedBytes, unicodePassword.toCharArray(), null, gson)
        }

        // Legacy un-repaired (`repaired == false`) >64-byte password slot using `toBytesOld` trailing nulls
        val longAsciiPassword = "A".repeat(70) // UTF-8 encode allocates >70-byte backing array with trailing 0x00s
        val legacyPaddedBytes = AegisBackupParser.encodeUtf8LegacyPadded(longAsciiPassword.toCharArray())
        assertThat(legacyPaddedBytes.size).isGreaterThan(70)

        val masterKey = ByteArray(32) { (it + 1).toByte() }
        val salt = ByteArray(32) { (it + 5).toByte() }
        val legacyDerivedKey = AegisBackupParser.scrypt(legacyPaddedBytes, salt, 1024, 8, 1, 32)

        val slotCipher = Cipher.getInstance("AES/GCM/NoPadding")
        slotCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(legacyDerivedKey, "AES"))
        val slotOut = slotCipher.doFinal(masterKey)

        val dbCipher = Cipher.getInstance("AES/GCM/NoPadding")
        dbCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
        val dbOut = dbCipher.doFinal(dbJson.toByteArray(StandardCharsets.UTF_8))

        val legacyRoot = JsonObject().apply {
            addProperty("version", 1)
            add("header", JsonObject().apply {
                add("slots", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", 1)
                        addProperty("uuid", UUID.randomUUID().toString())
                        addProperty("key", AegisBackupParser.toHex(slotOut.copyOfRange(0, 32)))
                        add("key_params", JsonObject().apply {
                            addProperty("nonce", AegisBackupParser.toHex(slotCipher.iv))
                            addProperty("tag", AegisBackupParser.toHex(slotOut.copyOfRange(32, 48)))
                        })
                        addProperty("n", 1024)
                        addProperty("r", 8)
                        addProperty("p", 1)
                        addProperty("salt", AegisBackupParser.toHex(salt))
                        addProperty("repaired", false)
                    })
                })
                add("params", JsonObject().apply {
                    addProperty("nonce", AegisBackupParser.toHex(dbCipher.iv))
                    addProperty("tag", AegisBackupParser.toHex(dbOut.copyOfRange(dbOut.size - 16, dbOut.size)))
                })
            })
            addProperty("db", Base64.Default.encode(dbOut.copyOfRange(0, dbOut.size - 16)))
        }
        val legacyVaultBytes = gson.toJson(legacyRoot).toByteArray(StandardCharsets.UTF_8)
        val legacyParsed = AegisBackupParser.parseBackup(
            legacyVaultBytes,
            longAsciiPassword.toCharArray(),
            null,
            gson
        )
        assertThat(legacyParsed).hasSize(1)
        assertThat(legacyParsed[0].issuer).isEqualTo("Cloudflare")
    }

    @Test
    fun security_rejectsExcessiveScryptMemoryBiometricOnlyVaultAndMalformedJson() {
        // 1. Excessive scrypt memory (N=524288, r=8 => 512 MB > 256 MB cap)
        assertThrows(IllegalArgumentException::class.java) {
            AegisBackupParser.scrypt(ByteArray(4), ByteArray(32), 524288, 8, 1, 32)
        }
        // 2. Non-power-of-two N
        assertThrows(IllegalArgumentException::class.java) {
            AegisBackupParser.scrypt(ByteArray(4), ByteArray(32), 1000, 8, 1, 32)
        }
        // 3. Encrypted vault containing ONLY a biometric slot (type 2) and no password slot
        val bioOnlyVault = """
            {
                "version": 1,
                "header": {
                    "slots": [{
                        "type": 2,
                        "uuid": "01234567-89ab-cdef-0123-456789abcdef",
                        "key": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        "key_params": {
                            "nonce": "0123456789abcdef01234567",
                            "tag": "0123456789abcdef0123456789abcdef"
                        }
                    }],
                    "params": {
                        "nonce": "0123456789abcdef01234567",
                        "tag": "0123456789abcdef0123456789abcdef"
                    }
                },
                "db": "AAAA"
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) {
            AegisBackupParser.inspectBackup(bioOnlyVault, gson)
        }

        // 4. Non-Aegis JSON object
        assertThrows(IllegalArgumentException::class.java) {
            AegisBackupParser.inspectBackup("""{"tokens":[]}""".toByteArray(StandardCharsets.UTF_8), gson)
        }
    }

    @Test
    fun storeImportedAegisTokens_appendTopAndReplaceAllPreserveCategoriesInEncryptedVault(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder<OtpTokenDatabase>()
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        try {
            val prefs = SimpleMemoryPrefs()
            val cryptoManager = TokenCryptoManager(prefs, db)
            val dao = db.otpTokenDao()

            val existing = OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "ExistingVault",
                label = "vault@example.com",
                secret = "JBSWY3DPEHPK3PXP",
                categories = listOf("Personal")
            ).copy(id = 1L, ordinal = 5L)
            dao.insert(existing)

            val setupMaterial = cryptoManager.deriveSetupMaterial("FreeOtpPlusVaultKey123!".toCharArray())
            cryptoManager.enableTokenEncryption(setupMaterial, null)

            val aegisDbJson = """
                {
                    "version": 3,
                    "groups": [{ "uuid": "g1", "name": "Work" }],
                    "entries": [
                        {
                            "type": "totp",
                            "uuid": "u1",
                            "name": "alice",
                            "issuer": "GitHub",
                            "groups": ["g1"],
                            "info": { "secret": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "algo": "SHA256", "digits": 6, "period": 30 }
                        },
                        {
                            "type": "hotp",
                            "uuid": "u2",
                            "name": "bob",
                            "issuer": "Red Hat",
                            "groups": [],
                            "info": { "secret": "JBSWY3DPEHPK3PXP", "algo": "SHA1", "digits": 6, "counter": 9 }
                        }
                    ]
                }
            """.trimIndent()
            val aegisBytes = AegisBackupParser.createEncryptedAegisVaultBytesForTesting(
                password = "AegisExportPassword!",
                dbJsonObject = aegisDbJson
            )
            val parsedPlain = AegisBackupParser.parseBackup(aegisBytes, "AegisExportPassword!".toCharArray(), null, gson)

            // APPEND places Aegis tokens at the TOP (ordinals 3, 4) before ExistingVault (ordinal 5)
            ImportExportUtil.storeImportedFreeOtpTokens(
                plaintextTokens = parsedPlain,
                mode = TokenImportMode.APPEND,
                filesDir = null,
                otpTokenDatabase = db,
                tokenCryptoManager = cryptoManager,
                gson = gson
            )
            val afterAppend = dao.getAll().first()
            assertThat(afterAppend.map { it.issuer }).containsExactly("GitHub", "Red Hat", "ExistingVault").inOrder()
            assertThat(afterAppend.map { it.ordinal }).containsExactly(3L, 4L, 5L).inOrder()
            assertThat(afterAppend[0].categories).containsExactly("Work")
            assertThat(afterAppend[0].encryptionType).isEqualTo(EncryptionType.ENCRYPTED)
            assertThat(cryptoManager.decryptSecret(afterAppend[0])).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

            // REPLACE_ALL replaces all tokens with the 2 Aegis tokens
            ImportExportUtil.storeImportedFreeOtpTokens(
                plaintextTokens = parsedPlain,
                mode = TokenImportMode.REPLACE_ALL,
                filesDir = null,
                otpTokenDatabase = db,
                tokenCryptoManager = cryptoManager,
                gson = gson
            )
            val afterReplace = dao.getAll().first()
            assertThat(afterReplace.map { it.issuer }).containsExactly("GitHub", "Red Hat").inOrder()
            assertThat(afterReplace.map { it.ordinal }).containsExactly(1L, 2L).inOrder()
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
