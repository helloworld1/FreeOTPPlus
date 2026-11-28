package org.fedorahosted.freeotp.uitest

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.preference.PreferenceManager
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.navigation.FreeOtpDestination
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTagDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.TokenCodeGrouping
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4ClassRunner::class)
class MultiLocaleUiLayoutTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun resetAuthOverrides() {
        AuthenticationUtil.hasScreenLockOverride = null
        AuthenticationUtil.hasStrongBiometricOverride = null
        AuthenticationUtil.authCallbackOverride = null
        AuthenticationUtil.appLaunchAuthCallbackOverride = null
        AuthenticationUtil.biometricCryptoCallbackOverride = null
    }

    private fun applyAppLocale(languageTag: String?) {
        val ctx = getInstrumentation().targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = if (languageTag.isNullOrEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
            ctx.getSystemService(LocaleManager::class.java)?.applicationLocales = locales
        } else {
            val localeList = if (languageTag.isNullOrEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageTag)
            }
            getInstrumentation().runOnMainSync {
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        }
        Thread.sleep(350)
        composeRule.waitForIdle()
    }

    @After
    fun tearDown() {
        resetAuthOverrides()
        applyAppLocale(null)
    }

    private fun findFirstTextLayout(node: SemanticsNode): TextLayoutResult? {
        if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
            val layouts = mutableListOf<TextLayoutResult>()
            val ok = node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts) == true
            if (ok && layouts.isNotEmpty()) {
                return layouts.first()
            }
        }
        for (child in node.children) {
            findFirstTextLayout(child)?.let { return it }
        }
        return null
    }

    private fun assertSingleLineNoOverflow(localeTag: String, tag: String) {
        val node = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val layout = findFirstTextLayout(node)
        assertWithMessage("[$localeTag] Expected TextLayoutResult for tag=$tag")
            .that(layout)
            .isNotNull()
        val nonNullLayout = layout!!
        assertWithMessage("[$localeTag] Expected single line for tag=$tag")
            .that(nonNullLayout.lineCount)
            .isEqualTo(1)
        assertWithMessage("[$localeTag] Expected didExceedMaxLines=false for tag=$tag")
            .that(nonNullLayout.multiParagraph.didExceedMaxLines)
            .isFalse()
        assertWithMessage("[$localeTag] Expected isLineEllipsized(0)=false for tag=$tag")
            .that(nonNullLayout.isLineEllipsized(0))
            .isFalse()
    }

    @Test
    fun englishGermanAndArabicLayoutsPreserveRtlOtpGroupingAndSingleLineControls() {
        val targetContext = getInstrumentation().targetContext
        val locales = listOf("en-US", "de-DE", "ar-SA")

        for (localeTag in locales) {
            resetAuthOverrides()
            @Suppress("DEPRECATION")
            val prefs = PreferenceManager.getDefaultSharedPreferences(targetContext)
            prefs.edit().clear().commit()

            applyAppLocale(localeTag)

            val launchIntent = Intent(targetContext, MainActivity::class.java).apply {
                putExtra(MainActivity.SCREENSHOT_MODE_EXTRA, true)
            }

            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                var totpId = 0L
                var hotpId = 0L
                lateinit var resActivity: MainActivity

                scenario.onActivity { activity ->
                    resActivity = activity
                    activity.settings.tokenCodeGrouping = TokenCodeGrouping.TWO_GROUPS
                    runBlocking(Dispatchers.IO) {
                        activity.otpTokenDatabase.clearAllTables()
                        totpId = activity.otpTokenDatabase.otpTokenDao().insert(
                            OtpTokenFactory.create(
                                type = OtpTokenType.TOTP,
                                issuer = "Microsoft",
                                label = "alice@contoso.com",
                                secret = "JBSWY3DPEHPK3PXP",
                                digits = 6,
                                categories = listOf("Work"),
                            )
                        )
                        hotpId = activity.otpTokenDatabase.otpTokenDao().insert(
                            OtpTokenFactory.create(
                                type = OtpTokenType.HOTP,
                                issuer = "GitHub Enterprise",
                                label = "octocat@github.example.com",
                                secret = "JBSWY3DPEHPK3PXP",
                                digits = 8,
                                initialCounter = 12345L,
                            )
                        )
                    }
                }

                composeRule.waitUntil(5_000L) {
                    scenario.onActivity { resActivity = it }
                    val expectedDir = if (localeTag == "ar-SA") {
                        View.LAYOUT_DIRECTION_RTL
                    } else {
                        View.LAYOUT_DIRECTION_LTR
                    }
                    val expectedAdd = when (localeTag) {
                        "de-DE" -> "Hinzufügen"
                        "ar-SA" -> "إضافة"
                        else -> "Add"
                    }
                    resActivity.resources.configuration.layoutDirection == expectedDir &&
                        resActivity.getString(R.string.add) == expectedAdd
                }

                fun str(resId: Int): String = resActivity.getString(resId)

                // 1. Token cards: reveal 6-digit TOTP and 8-digit HOTP codes; verify single-line & strict LTR group order
                composeRule.awaitTagDisplayed(UiTestTags.tokenCardTag(totpId))
                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(totpId)).performClick()
                composeRule.onNodeWithTag(UiTestTags.tokenCardTag(hotpId)).performClick()
                composeRule.waitForIdle()

                val hotpCodeTag = UiTestTags.tokenCodeTag(hotpId)
                assertSingleLineNoOverflow(localeTag, hotpCodeTag)
                val hotpNode = composeRule.onNodeWithTag(hotpCodeTag, useUnmergedTree = true).fetchSemanticsNode()
                val hotpText = hotpNode.config[SemanticsProperties.Text].joinToString("") { it.text }
                assertWithMessage("[$localeTag] Expected 8-digit grouped HOTP code")
                    .that(hotpText)
                    .matches("^\\d{4} \\d{4}$")
                val hotpLayout = findFirstTextLayout(hotpNode)!!
                // First group (index 0) must always be visually to the left of second group (index 5), even in RTL
                assertWithMessage("[$localeTag] Grouped OTP code halves must remain in LTR visual order")
                    .that(hotpLayout.getBoundingBox(0).left)
                    .isLessThan(hotpLayout.getBoundingBox(5).left)

                val totpCodeTag = UiTestTags.tokenCodeTag(totpId)
                assertSingleLineNoOverflow(localeTag, totpCodeTag)
                val totpLayout = findFirstTextLayout(
                    composeRule.onNodeWithTag(totpCodeTag, useUnmergedTree = true).fetchSemanticsNode()
                )!!
                assertWithMessage("[$localeTag] 6-digit grouped OTP code halves must remain in LTR visual order")
                    .that(totpLayout.getBoundingBox(0).left)
                    .isLessThan(totpLayout.getBoundingBox(4).left)

                // 2. EditTokenScreen: bottom bar buttons single-line & revealed secret LTR order
                composeRule.onNodeWithTag(UiTestTags.tokenMenuButtonTag(totpId)).performClick()
                composeRule.onNodeWithText(str(R.string.edit)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.DETAIL_SECRET)
                assertSingleLineNoOverflow(localeTag, UiTestTags.RESTORE_DEFAULTS_BUTTON)
                assertSingleLineNoOverflow(localeTag, UiTestTags.CANCEL_BUTTON)
                assertSingleLineNoOverflow(localeTag, UiTestTags.SAVE_BUTTON)

                composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET).performClick()
                composeRule.waitForIdle()
                val secretLayout = findFirstTextLayout(
                    composeRule.onNodeWithTag(UiTestTags.DETAIL_SECRET, useUnmergedTree = true).fetchSemanticsNode()
                )!!
                assertWithMessage("[$localeTag] Revealed Base32 secret must render in LTR character order")
                    .that(secretLayout.getBoundingBox(0).left)
                    .isLessThan(secretLayout.getBoundingBox(15).left)
                composeRule.onNodeWithTag(UiTestTags.NAV_BACK_BUTTON).performClick()

                // 3. BuiltInIconPickerDialog: single-word brand names fit on 1 line without mid-word wrapping
                scenario.onActivity { it.navigateToRoute(FreeOtpDestination.ADD_TOKEN) }
                composeRule.awaitTagDisplayed(UiTestTags.TOKEN_AVATAR_BUTTON)
                composeRule.onNodeWithTag(UiTestTags.TOKEN_AVATAR_BUTTON).performClick()
                composeRule.awaitTextDisplayed(str(R.string.choose_builtin_icon))
                composeRule.onNodeWithText(str(R.string.choose_builtin_icon)).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.ICON_SEARCH_INPUT)
                assertSingleLineNoOverflow(localeTag, UiTestTags.builtInIconItemTag("Activision"))
                assertSingleLineNoOverflow(localeTag, UiTestTags.builtInIconItemTag("AllegroPl"))
                composeRule.onNodeWithTag(UiTestTags.DIALOG_NEGATIVE_BUTTON).performClick()
                composeRule.onNodeWithTag(UiTestTags.NAV_BACK_BUTTON).performClick()

                // 4. AboutScreen: preserves angle-bracketed email & LRM after FreeOTP+ in Arabic
                scenario.onActivity { it.navigateToRoute(FreeOtpDestination.ABOUT) }
                composeRule.awaitTagDisplayed(UiTestTags.ABOUT_VERSION)
                val aboutTokenImagesText = composeRule
                    .onNodeWithTag(UiTestTags.ABOUT_TOKEN_IMAGES, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .joinToString("") { it.text }
                assertWithMessage("[$localeTag] About token images attribution should keep email")
                    .that(aboutTokenImagesText)
                    .contains("<andotp@shadowice.org>")
                if (localeTag == "ar-SA") {
                    val aboutVersionText = composeRule
                        .onNodeWithTag(UiTestTags.ABOUT_VERSION, useUnmergedTree = true)
                        .fetchSemanticsNode()
                        .config[SemanticsProperties.Text]
                        .joinToString("") { it.text }
                    assertWithMessage("[ar-SA] FreeOTP+ must include LRM so '+' stays on the right of FreeOTP")
                        .that(aboutVersionText)
                        .contains("FreeOTP+\u200E")
                }
                composeRule.onNodeWithTag(UiTestTags.NAV_BACK_BUTTON).performClick()

                // 5. SetEncryptionKeyDialog & ExportEncryptionChoiceDialog: single-line action buttons in all locales
                AuthenticationUtil.hasStrongBiometricOverride = false
                composeRule.openOverflowMenu()
                composeRule.onNodeWithText(str(R.string.authentication_mode)).performClick()
                val encryptedRadio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and
                    hasText(str(R.string.auth_mode_token_encrypted))
                composeRule.onNode(encryptedRadio).performClick()
                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                assertSingleLineNoOverflow(localeTag, UiTestTags.DIALOG_NEGATIVE_BUTTON)
                assertSingleLineNoOverflow(localeTag, UiTestTags.DIALOG_POSITIVE_BUTTON)

                if (localeTag == "de-DE" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    assertWithMessage("[de-DE] SetEncryptionKeyDialog title should contain soft hyphen at compound boundary")
                        .that(str(R.string.set_encryption_key_title))
                        .contains("Verschlüsselungs\u00ADschlüssel")
                }

                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("layoutTestKey1")
                composeRule.onNodeWithTag(UiTestTags.SECURE_CONFIRM_KEY_INPUT).performTextReplacement("layoutTestKey1")
                composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
                composeRule.waitUntil(5_000L) {
                    resActivity.settings.authMode == AuthMode.TOKEN_ENCRYPTED &&
                        resActivity.tokenCryptoManager.isUnlocked()
                }

                scenario.onActivity { it.handleExportAction(isJson = true) }
                composeRule.awaitTagDisplayed(UiTestTags.SECURE_KEY_INPUT)
                composeRule.onNodeWithTag(UiTestTags.SECURE_KEY_INPUT).performTextReplacement("layoutTestKey1")
                composeRule.onNodeWithTag(UiTestTags.DIALOG_POSITIVE_BUTTON).performClick()
                composeRule.awaitTextDisplayed(str(R.string.export_encryption_choice_title))

                assertSingleLineNoOverflow(localeTag, UiTestTags.DIALOG_POSITIVE_BUTTON)
                assertSingleLineNoOverflow(localeTag, UiTestTags.DIALOG_NEGATIVE_BUTTON)
                assertSingleLineNoOverflow(localeTag, UiTestTags.DIALOG_NEUTRAL_BUTTON)
                composeRule.onNodeWithTag(UiTestTags.DIALOG_NEUTRAL_BUTTON).performClick()

                scenario.onActivity {
                    runBlocking(Dispatchers.IO) {
                        it.tokenCryptoManager.disableTokenEncryption(AuthMode.NONE)
                    }
                }
            }
        }
    }
}
