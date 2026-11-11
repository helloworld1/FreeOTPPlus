package org.fedorahosted.freeotp.uitest

import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.ui.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4ClassRunner::class)
class ImportExportTest {
    @get:Rule
    var activityRule = activityScenarioRule<MainActivity>()

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

        // Single-character label without issuer
        val tokenSingleChar = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/a?secret=JBSWY3DPEHPK3PXP")
        )
        assertThat(tokenSingleChar.issuer).isNull()
        assertThat(tokenSingleChar.label).isEqualTo("a")

        // Label containing colon without issuer round-trip
        val tokenColonInLabel = token1.copy(issuer = null, label = "service:alice")
        val exportedColonUri = OtpTokenFactory.toUri(tokenColonInLabel)
        val roundTripColon = OtpTokenFactory.createFromUri(exportedColonUri)
        assertThat(roundTripColon.issuer).isNull()
        assertThat(roundTripColon.label).isEqualTo("service:alice")

        // Empty issuer prefix or empty issuer query parameter
        val tokenColonPrefix = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/:bob@example.com?secret=JBSWY3DPEHPK3PXP&issuer=")
        )
        assertThat(tokenColonPrefix.issuer).isNull()
        assertThat(tokenColonPrefix.label).isEqualTo("bob@example.com")

        // Issuer only in query parameter (no prefix in path)
        val tokenQueryIssuer = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/carol@example.com?secret=JBSWY3DPEHPK3PXP&issuer=GitHub")
        )
        assertThat(tokenQueryIssuer.issuer).isEqualTo("GitHub")
        assertThat(tokenQueryIssuer.label).isEqualTo("carol@example.com")
    }

    @Test
    fun testExportAndImportJsonWithoutIssuer() = runBlocking {
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
    fun testImportJsonVariantsWithoutIssuerExt() = runBlocking {
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
    fun testExportAndImportKeyUriWithoutIssuer() = runBlocking {
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
}
