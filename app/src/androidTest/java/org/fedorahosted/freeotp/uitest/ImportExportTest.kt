package org.fedorahosted.freeotp.uitest

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.net.toUri
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.third_party.AegisBackupParser
import org.fedorahosted.freeotp.data.third_party.FreeOtpBackupParser
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class ImportExportTest {
    private val composeRule = createEmptyComposeRule()
    private val activityRule = activityScenarioRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(composeRule).around(activityRule)

    private fun string(resId: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    @Before
    fun setUp(): Unit = clearTokens()

    @After
    fun tearDown(): Unit = clearTokens()

    private fun clearTokens() = runBlocking {
        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }
        activity.otpTokenDatabase.otpTokenDao().deleteAll()
    }

    @Test
    fun testOtpTokenFactoryWithoutIssuer() {
        val token1 = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/alice@example.com?secret=JBSWY3DPEHPK3PXP")
        )
        assertThat(token1.issuer).isNull()
        assertThat(token1.label).isEqualTo("alice@example.com")

        val exportedUri1 = OtpTokenFactory.toUri(token1)
        assertThat(exportedUri1.getQueryParameter("issuer")).isNull()
        val roundTrip1 = OtpTokenFactory.createFromUri(exportedUri1)
        assertThat(roundTrip1.issuer).isNull()
        assertThat(roundTrip1.label).isEqualTo("alice@example.com")

        val tokenSingleChar = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP")
        )
        assertThat(tokenSingleChar.issuer).isNull()
        assertThat(tokenSingleChar.label).isEqualTo("a")

        val tokenColonInLabel = token1.copy(issuer = null, label = "service:alice")
        val exportedColonUri = OtpTokenFactory.toUri(tokenColonInLabel)
        val roundTripColon = OtpTokenFactory.createFromUri(exportedColonUri)
        assertThat(roundTripColon.issuer).isNull()
        assertThat(roundTripColon.label).isEqualTo("service:alice")

        val tokenColonPrefix = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/:bob@example.com?secret=JBSWY3DPEHPK3PXP&issuer=")
        )
        assertThat(tokenColonPrefix.issuer).isNull()
        assertThat(tokenColonPrefix.label).isEqualTo("bob@example.com")

        val tokenQueryIssuer = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/carol@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub")
        )
        assertThat(tokenQueryIssuer.issuer).isEqualTo("GitHub")
        assertThat(tokenQueryIssuer.label).isEqualTo("carol@example.com")
    }

    @Test
    fun testExportAndImportJsonWithoutIssuer(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jsonFile = File(context.cacheDir, "test_export_no_issuer.json")
        jsonFile.createNewFile()

        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }

        val dao = activity.otpTokenDatabase.otpTokenDao()
        val tokenNoIssuerNull = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/noissuer1@example.com?secret=JBSWY3DPEHPK3PXP")
        ).copy(id = 1, ordinal = 1, issuer = null)
        val tokenNoIssuerEmpty = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/noissuer2@example.com?secret=JBSWY3DPEHPK3PXP")
        ).copy(id = 2, ordinal = 2, issuer = "")
        val tokenWithIssuer = TestData.OTP_HOTP_TOKEN_1.copy(id = 3, ordinal = 3)

        dao.insertAll(listOf(tokenNoIssuerNull, tokenNoIssuerEmpty, tokenWithIssuer))
        activity.importFromUtil.exportJsonFile(jsonFile.toUri())

        dao.deleteById(1)
        dao.deleteById(2)
        dao.deleteById(3)
        assertThat(dao.getAll().first()).isEmpty()

        activity.importFromUtil.importJsonFile(jsonFile.toUri())
        val imported = dao.getAll().first()
        assertThat(imported).hasSize(3)
        assertThat(imported[0].issuer).isNull()
        assertThat(imported[0].label).isEqualTo("noissuer1@example.com")
        assertThat(imported[1].issuer).isNull()
        assertThat(imported[1].label).isEqualTo("noissuer2@example.com")
        assertThat(imported[2].issuer).isEqualTo("github.com")
        assertThat(imported[2].label).isEqualTo("github account 1")

        jsonFile.delete()
    }

    @Test
    fun testImportJsonVariantsWithoutIssuerExt(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jsonFile = File(context.cacheDir, "test_import_variants.json")
        jsonFile.writeText(
            """
            {
              "tokenOrder": [
                "plain_account",
                ":colon_prefixed_account",
                "InferredIssuer:inferred_account"
              ],
              "tokens": [
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "label": "plain_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "issuerExt": "",
                  "label": "colon_prefixed_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA256",
                  "counter": 0,
                  "digits": 6,
                  "label": "inferred_account",
                  "period": 30,
                  "secret": [8, 66, 29, 111, -99],
                  "type": "HOTP"
                }
              ]
            }
            """.trimIndent()
        )

        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }

        activity.importFromUtil.importJsonFile(jsonFile.toUri())
        val imported = activity.otpTokenDatabase.otpTokenDao().getAll().first()
        assertThat(imported).hasSize(3)

        assertThat(imported[0].issuer).isNull()
        assertThat(imported[0].label).isEqualTo("plain_account")

        assertThat(imported[1].issuer).isNull()
        assertThat(imported[1].label).isEqualTo("colon_prefixed_account")

        assertThat(imported[2].issuer).isEqualTo("InferredIssuer")
        assertThat(imported[2].label).isEqualTo("inferred_account")

        jsonFile.delete()
    }

    @Test
    fun testExportAndImportKeyUriWithoutIssuer(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyUriFile = File(context.cacheDir, "test_export_key_uri.txt")
        keyUriFile.createNewFile()

        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }

        val dao = activity.otpTokenDatabase.otpTokenDao()
        val tokenNoIssuer = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/uri_no_issuer@example.com?secret=JBSWY3DPEHPK3PXP")
        ).copy(id = 1, ordinal = 1)
        val tokenWithIssuer = TestData.OTP_TOTP_TOKEN_2.copy(id = 2, ordinal = 2)

        dao.insertAll(listOf(tokenNoIssuer, tokenWithIssuer))
        activity.importFromUtil.exportKeyUriFile(keyUriFile.toUri())

        dao.deleteById(1)
        dao.deleteById(2)
        assertThat(dao.getAll().first()).isEmpty()

        activity.importFromUtil.importKeyUriFile(keyUriFile.toUri())
        val imported = dao.getAll().first()
        assertThat(imported).hasSize(2)
        assertThat(imported[0].issuer).isNull()
        assertThat(imported[0].label).isEqualTo("uri_no_issuer@example.com")
        assertThat(imported[1].issuer).isEqualTo("microsoft.com")
        assertThat(imported[1].label).isEqualTo("microsoft account 1")

        keyUriFile.delete()
    }

    @Test
    fun testHotpRfc4226UriAndJsonBackupRoundTrip(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jsonFile = File(context.cacheDir, "test_hotp_rfc4226.json")
        val uriFile = File(context.cacheDir, "test_hotp_rfc4226.txt")

        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }
        val dao = activity.otpTokenDatabase.otpTokenDao()

        val initialToken = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://hotp/RFC4226:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&algorithm=SHA1&digits=6&counter=0")
        ).copy(id = 1, ordinal = 1)
        assertThat(initialToken.counter).isEqualTo(0L)
        assertThat(OtpTokenFactory.toUri(initialToken).getQueryParameter("counter")).isEqualTo("0")

        dao.insert(initialToken)
        dao.incrementCounter(1L)
        val afterFirstUse = dao.get(1L).first()!!
        assertThat(afterFirstUse.counter).isEqualTo(1L)
        assertThat(OtpTokenFactory.toUri(afterFirstUse).getQueryParameter("counter")).isEqualTo("1")

        activity.importFromUtil.exportJsonFile(jsonFile.toUri())
        activity.importFromUtil.exportKeyUriFile(uriFile.toUri())

        dao.deleteAll()
        activity.importFromUtil.importJsonFile(jsonFile.toUri())
        assertThat(dao.getAll().first().single().counter).isEqualTo(1L)

        dao.deleteAll()
        activity.importFromUtil.importKeyUriFile(uriFile.toUri())
        assertThat(dao.getAll().first().single().counter).isEqualTo(1L)

        jsonFile.delete()
        uriFile.delete()
    }

    @Test
    fun testOverwriteTruncationIssuerOnlyColonIssuerAndKeyUriCategoriesRoundTrip(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val jsonFile = File(context.cacheDir, "test_f4_overwrite.json")
        val uriFile = File(context.cacheDir, "test_f4_overwrite.txt")
        val zipFile = File(context.cacheDir, "test_f4_overwrite.zip")

        val largeJunk = "X".repeat(32 * 1024)
        jsonFile.writeText(largeJunk)
        uriFile.writeText(largeJunk)
        zipFile.writeText(largeJunk)

        lateinit var activity: MainActivity
        activityRule.scenario.onActivity { activity = it }
        val dao = activity.otpTokenDatabase.otpTokenDao()
        dao.deleteAll()

        val issuerOnlyToken = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "GitHub",
            label = "",
            secret = "JBSWY3DPEHPK3PXP",
            categories = listOf("Work"),
        ).copy(id = 1, ordinal = 1)

        val colonInIssuerToken = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "Company:Prod",
            label = "env:admin",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            categories = listOf("Work", "Infra"),
        ).copy(id = 2, ordinal = 2)

        assertThat(OtpTokenFactory.toUri(colonInIssuerToken).getQueryParameters("category")).isEmpty()

        dao.insertAll(listOf(issuerOnlyToken, colonInIssuerToken))

        activity.importFromUtil.exportJsonFile(jsonFile.toUri())
        activity.importFromUtil.exportKeyUriFile(uriFile.toUri())
        activity.importFromUtil.exportTokenImagesZip(zipFile.toUri())

        assertThat(jsonFile.length()).isLessThan(largeJunk.length.toLong())
        assertThat(uriFile.length()).isLessThan(largeJunk.length.toLong())
        assertThat(zipFile.length()).isLessThan(largeJunk.length.toLong())

        dao.deleteAll()
        activity.importFromUtil.importJsonFile(jsonFile.toUri())
        val fromJson = dao.getAll().first()
        assertThat(fromJson).hasSize(2)
        assertThat(fromJson[0].issuer).isEqualTo("GitHub")
        assertThat(fromJson[0].label).isEmpty()
        assertThat(fromJson[0].categories).containsExactly("Work")
        assertThat(fromJson[1].issuer).isEqualTo("Company:Prod")
        assertThat(fromJson[1].label).isEqualTo("env:admin")
        assertThat(fromJson[1].categories).containsExactly("Work", "Infra").inOrder()

        dao.deleteAll()
        activity.importFromUtil.importKeyUriFile(uriFile.toUri())
        val fromKeyUri = dao.getAll().first()
        assertThat(fromKeyUri).hasSize(2)
        assertThat(fromKeyUri[0].issuer).isEqualTo("GitHub")
        assertThat(fromKeyUri[0].label).isEmpty()
        assertThat(fromKeyUri[0].categories).containsExactly("Work")
        assertThat(fromKeyUri[1].issuer).isEqualTo("Company:Prod")
        assertThat(fromKeyUri[1].label).isEqualTo("env:admin")
        assertThat(fromKeyUri[1].categories).containsExactly("Work", "Infra").inOrder()

        activity.importFromUtil.importTokenImagesZip(zipFile.toUri())

        jsonFile.delete()
        uriFile.delete()
        zipFile.delete()
    }

    @Test
    fun testImportFreeOtpBackupAppendAndReplaceAllUiFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backupFile = File(context.cacheDir, "externalBackup.xml")
        val backupPassword = "FreeOtpMasterPassword!"

        val githubToken = OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "GitHub",
            label = "octocat@github.com",
            secret = "JBSWY3DPEHPK3PXP",
            algorithm = "SHA256",
            digits = 6,
            period = 30,
        )
        val redHatHotp = OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "Red Hat",
            label = "sso@redhat.com",
            secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            algorithm = "SHA1",
            digits = 6,
            period = 30,
            initialCounter = 7L,
        )
        backupFile.writeBytes(
            FreeOtpBackupParser.createV2ExternalBackupBytesForTesting(
                password = backupPassword,
                tokens = listOf(redHatHotp, githubToken),
                iterations = 1_000,
            )
        )

        try {
            // 1. Verify exact Import and Export submenu item ordering and titles
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.import_text)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.import_aegis_json))

            val importMenuTitles = composeRule.onNodeWithTag(UiTestTags.OVERFLOW_MENU)
                .onChildren()
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { s -> s.text } }
                .drop(1) // Drop back header row
            assertThat(importMenuTitles).containsExactly(
                "FreeOTP+ JSON (Preferred)",
                "Key URI",
                "Token images",
                "FreeOTP Backup XML",
                "Aegis JSON",
            ).inOrder()

            // Navigate back to root menu and check Export submenu ordering
            composeRule.onNodeWithText(string(R.string.import_text)).performClick()
            composeRule.onNodeWithText(string(R.string.export_text)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.export_token_images))
            val exportMenuTitles = composeRule.onNodeWithTag(UiTestTags.OVERFLOW_MENU)
                .onChildren()
                .fetchSemanticsNodes()
                .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { s -> s.text } }
                .drop(1)
            assertThat(exportMenuTitles).containsExactly(
                "FreeOTP+ JSON (Preferred)",
                "Key URI",
                "Token images",
            ).inOrder()
            Espresso.pressBack()

            // Seed 1 existing token in FreeOTP+
            val existingToken = OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "ExistingCorp",
                label = "existing@corp.example",
                secret = "JBSWY3DPEHPK3PXP",
            ).copy(id = 1L, ordinal = 1L)
            activityRule.scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().replaceAll(listOf(existingToken))
                }
            }
            TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)

            // 2. Trigger FreeOTP backup import dialog -> verify Append is default and warning hidden
            activityRule.scenario.onActivity { activity ->
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_FREEOTP_BACKUP,
                    backupFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_APPEND_RADIO).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.REPLACE_WARNING_TEXT).assertDoesNotExist()

            // 3. Wrong password keeps dialog open and preserves existing token
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("WrongPassword")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.awaitTextDisplayed(string(R.string.incorrect_freeotp_backup_password))
            assertThat(TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)).hasSize(1)

            // 4. Correct password with Append mode places the 2 FreeOTP tokens at the top before ExistingCorp
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement(backupPassword)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val appended = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 3)
            assertThat(appended.map { it.issuer }).containsExactly("GitHub", "Red Hat", "ExistingCorp").inOrder()
            assertThat(appended[0].algorithm).isEqualTo("SHA256")
            assertThat(appended[1].tokenType).isEqualTo(OtpTokenType.HOTP)
            assertThat(appended[1].counter).isEqualTo(7L)
            composeRule.awaitTokenCardCount(3)
            composeRule.assertCardAtPositionHasText(0, "GitHub")
            composeRule.assertCardAtPositionHasText(2, "ExistingCorp")

            // 5. Re-import with "Replace all existing tokens" -> shows warning and replaces all 3 with 2
            activityRule.scenario.onActivity { activity ->
                activity.dismissSnackbar()
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_FREEOTP_BACKUP,
                    backupFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.IMPORT_MODE_REPLACE_ALL_RADIO)
            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_REPLACE_ALL_RADIO).performClick()
            composeRule.onNodeWithTag(UiTestTags.REPLACE_WARNING_TEXT).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement(backupPassword)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val replaced = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 2)
            assertThat(replaced.map { it.issuer }).containsExactly("GitHub", "Red Hat").inOrder()
        } finally {
            backupFile.delete()
        }
    }

    @Test
    fun testImportMalformedFreeOtpBackupShowsFailureSnackbar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val badFile = File(context.cacheDir, "invalid_freeotp_backup.xml").apply {
            writeText("not a valid freeotp backup", Charsets.UTF_8)
        }
        try {
            activityRule.scenario.onActivity { activity ->
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_FREEOTP_BACKUP,
                    badFile.toUri(),
                )
            }
            composeRule.awaitTextDisplayed(string(R.string.import_freeotp_backup_failed_text))
        } finally {
            badFile.delete()
        }
    }

    @Test
    fun testImportAegisJsonPlainAndEncryptedAppendTopAndReplaceAllUiFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plainAegisFile = File(context.cacheDir, "aegis_plain.json")
        val encryptedAegisFile = File(context.cacheDir, "aegis_encrypted.json")
        val badAegisFile = File(context.cacheDir, "aegis_invalid.json")
        val aegisPassword = "AegisVaultPassword!"

        plainAegisFile.writeText(
            """
            {
                "version": 1,
                "header": { "slots": null, "params": null },
                "db": {
                    "version": 3,
                    "groups": [{ "uuid": "g-work", "name": "Work" }],
                    "entries": [
                        {
                            "type": "totp",
                            "uuid": "e-github",
                            "name": "octocat@github.com",
                            "issuer": "GitHub",
                            "groups": ["g-work"],
                            "info": {
                                "secret": "JBSWY3DPEHPK3PXP",
                                "algo": "SHA256",
                                "digits": 6,
                                "period": 30
                            }
                        },
                        {
                            "type": "steam",
                            "uuid": "e-steam",
                            "name": "gamer@steam.example",
                            "issuer": "Steam",
                            "groups": [],
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
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val encryptedDbJson = """
            {
                "version": 3,
                "groups": [{ "uuid": "g-infra", "name": "Infra" }],
                "entries": [
                    {
                        "type": "hotp",
                        "uuid": "e-cloudflare",
                        "name": "ops@cloudflare.example",
                        "issuer": "Cloudflare",
                        "groups": ["g-infra"],
                        "info": {
                            "secret": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                            "algo": "SHA1",
                            "digits": 6,
                            "counter": 12
                        }
                    }
                ]
            }
        """.trimIndent()
        encryptedAegisFile.writeBytes(
            AegisBackupParser.createEncryptedAegisVaultBytesForTesting(
                password = aegisPassword,
                dbJsonObject = encryptedDbJson,
                scryptN = 1024,
                scryptR = 8,
                scryptP = 1,
            )
        )
        badAegisFile.writeText("""{"not_aegis": true}""", Charsets.UTF_8)

        try {
            val existingToken = OtpTokenFactory.create(
                type = OtpTokenType.TOTP,
                issuer = "ExistingCorp",
                label = "existing@corp.example",
                secret = "JBSWY3DPEHPK3PXP",
            ).copy(id = 1L, ordinal = 1L)
            activityRule.scenario.onActivity { activity ->
                runBlocking {
                    activity.otpTokenDatabase.otpTokenDao().replaceAll(listOf(existingToken))
                }
            }
            TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)

            // 1. Plaintext Aegis JSON hides password field and appends to TOP of token list
            activityRule.scenario.onActivity { activity ->
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_AEGIS_JSON,
                    plainAegisFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.IMPORT_MODE_APPEND_RADIO)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_APPEND_RADIO).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val afterPlainAppend = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 3)
            assertThat(afterPlainAppend.map { it.issuer }).containsExactly("GitHub", "Steam", "ExistingCorp").inOrder()
            assertThat(afterPlainAppend[0].categories).containsExactly("Work")
            assertThat(afterPlainAppend[1].digits).isEqualTo(5)
            composeRule.awaitTokenCardCount(3)
            composeRule.assertCardAtPositionHasText(0, "GitHub")
            composeRule.assertCardAtPositionHasText(2, "ExistingCorp")

            // 2. Encrypted Aegis JSON shows password field, rejects wrong password, and Replace All replaces list
            activityRule.scenario.onActivity { activity ->
                activity.dismissSnackbar()
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_AEGIS_JSON,
                    encryptedAegisFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("WrongPassword")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.awaitTextDisplayed(string(R.string.incorrect_freeotp_backup_password))
            assertThat(TestWaitUtil.awaitStoredTokens(activityRule.scenario, 3)).hasSize(3)

            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_REPLACE_ALL_RADIO).performClick()
            composeRule.onNodeWithTag(UiTestTags.REPLACE_WARNING_TEXT).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement(aegisPassword)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val afterEncryptedReplace = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)
            assertThat(afterEncryptedReplace.single().issuer).isEqualTo("Cloudflare")
            assertThat(afterEncryptedReplace.single().tokenType).isEqualTo(OtpTokenType.HOTP)
            assertThat(afterEncryptedReplace.single().counter).isEqualTo(12L)
            assertThat(afterEncryptedReplace.single().categories).containsExactly("Infra")

            // 3. Malformed Aegis JSON shows failure Snackbar
            activityRule.scenario.onActivity { activity ->
                activity.dismissSnackbar()
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_AEGIS_JSON,
                    badAegisFile.toUri(),
                )
            }
            composeRule.awaitTextDisplayed(string(R.string.import_aegis_failed_text))
        } finally {
            plainAegisFile.delete()
            encryptedAegisFile.delete()
            badAegisFile.delete()
        }
    }

    @Test
    fun testImportFreeOtpPlusJsonPlainAndEncryptedAppendTopAndReplaceAllUiFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plainJsonFile = File(context.cacheDir, "freeotp_plus_plain.json")
        val encryptedJsonFile = File(context.cacheDir, "freeotp_plus_encrypted.json")
        val badJsonFile = File(context.cacheDir, "freeotp_plus_invalid.json")
        val encryptionKey = "BackupVaultKey123!"

        try {
            activityRule.scenario.onActivity { activity ->
                runBlocking {
                    val dao = activity.otpTokenDatabase.otpTokenDao()
                    dao.replaceAll(
                        listOf(
                            OtpTokenFactory.create(
                                type = OtpTokenType.TOTP,
                                issuer = "GitHub",
                                label = "octocat@github.com",
                                secret = "JBSWY3DPEHPK3PXP",
                                algorithm = "SHA256",
                                categories = listOf("Work"),
                            ).copy(id = 1L, ordinal = 1L),
                            OtpTokenFactory.create(
                                type = OtpTokenType.HOTP,
                                issuer = "GitLab",
                                label = "ops@gitlab.com",
                                secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                                initialCounter = 5L,
                            ).copy(id = 2L, ordinal = 2L),
                        )
                    )
                    activity.importFromUtil.exportJsonFile(plainJsonFile.toUri(), encrypt = false)

                    dao.replaceAll(
                        listOf(
                            OtpTokenFactory.create(
                                type = OtpTokenType.HOTP,
                                issuer = "Cloudflare",
                                label = "sec@cloudflare.com",
                                secret = "JBSWY3DPEHPK3PXP",
                                initialCounter = 11L,
                                categories = listOf("Infra"),
                            ).copy(id = 1L, ordinal = 1L)
                        )
                    )
                    val setupMaterial = activity.tokenCryptoManager.deriveSetupMaterial(encryptionKey.toCharArray())
                    activity.tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                    setupMaterial.clear()
                    activity.importFromUtil.exportJsonFile(encryptedJsonFile.toUri(), encrypt = true)
                    activity.tokenCryptoManager.disableTokenEncryption(AuthMode.NONE)

                    dao.replaceAll(
                        listOf(
                            OtpTokenFactory.create(
                                type = OtpTokenType.TOTP,
                                issuer = "ExistingCorp",
                                label = "existing@corp.example",
                                secret = "JBSWY3DPEHPK3PXP",
                            ).copy(id = 1L, ordinal = 1L)
                        )
                    )
                }
            }
            badJsonFile.writeText("""{"unexpected": true}""", Charsets.UTF_8)
            TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)

            // 1. Plaintext FreeOTP+ JSON hides encryption key input and appends to TOP of token list by default
            activityRule.scenario.onActivity { activity ->
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_JSON,
                    plainJsonFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.IMPORT_MODE_APPEND_RADIO)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_APPEND_RADIO).assertIsSelected()
            composeRule.onNodeWithTag(UiTestTags.REPLACE_WARNING_TEXT).assertDoesNotExist()
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val afterPlainAppend = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 3)
            assertThat(afterPlainAppend.map { it.issuer }).containsExactly("GitHub", "GitLab", "ExistingCorp").inOrder()
            assertThat(afterPlainAppend[0].categories).containsExactly("Work")
            assertThat(afterPlainAppend[1].tokenType).isEqualTo(OtpTokenType.HOTP)
            assertThat(afterPlainAppend[1].counter).isEqualTo(5L)
            composeRule.awaitTokenCardCount(3)
            composeRule.assertCardAtPositionHasText(0, "GitHub")
            composeRule.assertCardAtPositionHasText(2, "ExistingCorp")

            // 2. Encrypted FreeOTP+ JSON shows key input, rejects wrong key inline, and Replace All replaces list
            activityRule.scenario.onActivity { activity ->
                activity.dismissSnackbar()
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_JSON,
                    encryptedJsonFile.toUri(),
                )
            }
            composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("WrongKey!")
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
            composeRule.awaitTextDisplayed(string(R.string.incorrect_encryption_key))
            assertThat(TestWaitUtil.awaitStoredTokens(activityRule.scenario, 3)).hasSize(3)

            composeRule.onNodeWithTag(UiTestTags.IMPORT_MODE_REPLACE_ALL_RADIO).performClick()
            composeRule.onNodeWithTag(UiTestTags.REPLACE_WARNING_TEXT).assertIsDisplayed()
            composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement(encryptionKey)
            composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()

            val afterEncryptedReplace = TestWaitUtil.awaitStoredTokens(activityRule.scenario, 1)
            assertThat(afterEncryptedReplace.single().issuer).isEqualTo("Cloudflare")
            assertThat(afterEncryptedReplace.single().tokenType).isEqualTo(OtpTokenType.HOTP)
            assertThat(afterEncryptedReplace.single().counter).isEqualTo(11L)
            assertThat(afterEncryptedReplace.single().categories).containsExactly("Infra")

            // 3. Malformed FreeOTP+ JSON shows failure Snackbar immediately
            activityRule.scenario.onActivity { activity ->
                activity.dismissSnackbar()
                activity.onDocumentPicked(
                    MainActivity.DocumentAction.IMPORT_JSON,
                    badJsonFile.toUri(),
                )
            }
            composeRule.awaitTextDisplayed(string(R.string.import_json_failed_text))
        } finally {
            plainJsonFile.delete()
            encryptedJsonFile.delete()
            badJsonFile.delete()
        }
    }
}
