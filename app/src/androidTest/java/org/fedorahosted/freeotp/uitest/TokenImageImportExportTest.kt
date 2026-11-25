package org.fedorahosted.freeotp.uitest

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.TokenAvatarModel
import org.fedorahosted.freeotp.ui.components.resolveTokenAvatarModel
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTextDisplayed
import org.fedorahosted.freeotp.uitest.TestWaitUtil.openOverflowMenu
import org.fedorahosted.freeotp.util.ImageUtil
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.liberty.android.freeotp.token_images.TokenImage
import java.io.File
import java.nio.charset.StandardCharsets

@RunWith(AndroidJUnit4ClassRunner::class)
class TokenImageImportExportTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private fun string(resId: Int): String =
        getInstrumentation().targetContext.getString(resId)

    companion object {
        private const val TELEKOM_SVG =
            """<?xml version="1.0" encoding="UTF-8"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 60 60"><rect width="60" height="60" fill="#e20074"/></svg>"""
    }

    @Test
    fun testImportAndExportTokenImagesMenuItemsDisplayed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.import_text)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.import_token_images))
            composeRule.onNodeWithText(string(R.string.import_token_images)).assertIsDisplayed()
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.openOverflowMenu()
            composeRule.onNodeWithText(string(R.string.export_text)).performClick()
            composeRule.awaitTextDisplayed(string(R.string.export_token_images))
            composeRule.onNodeWithText(string(R.string.export_token_images)).assertIsDisplayed()
        }
    }

    @Test
    fun testFullBackupRestoreWithTokenImagesZipForJsonAndKeyUri() {
        val context = getInstrumentation().targetContext
        val imageUtil = ImageUtil(context)

        val srcPng = File(context.cacheDir, "src_blue.png")
        val blueBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0x12, 0x67, 0xCC))
        }
        srcPng.outputStream().use { blueBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val srcSvg = File(context.cacheDir, "src_magenta.svg").apply {
            writeText(TELEKOM_SVG, StandardCharsets.UTF_8)
        }

        val jsonBackupFile = File(context.cacheDir, "tokens_backup.json")
        val keyUriBackupFile = File(context.cacheDir, "tokens_backup.txt")
        val imagesZipFile = File(context.cacheDir, "token_images_backup.zip")

        try {
            val savedBlueUri = runBlocking { imageUtil.saveImageUriToFile(srcPng.toUri()) }
            val savedMagentaUri = runBlocking { imageUtil.saveImageUriToFile(srcSvg.toUri()) }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                val dao = activity.otpTokenDatabase.otpTokenDao()

                runBlocking {
                    dao.deleteAll()
                    dao.insertAll(
                        listOf(
                            TestData.OTP_HOTP_TOKEN_1.copy(
                                id = 1L,
                                ordinal = 1L,
                                imagePath = savedBlueUri.toString(),
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 2L,
                                ordinal = 2L,
                                imagePath = savedMagentaUri.toString(),
                            ),
                        )
                    )

                    activity.importFromUtil.exportJsonFile(jsonBackupFile.toUri())
                    activity.importFromUtil.exportKeyUriFile(keyUriBackupFile.toUri())
                    activity.importFromUtil.exportTokenImagesZip(imagesZipFile.toUri())
                }
                assertThat(imagesZipFile.length()).isGreaterThan(0L)

                runBlocking { dao.deleteAll() }
                context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
                assertThat(File(Uri.parse(savedBlueUri.toString()).path!!).exists()).isFalse()
                assertThat(File(Uri.parse(savedMagentaUri.toString()).path!!).exists()).isFalse()

                runBlocking {
                    activity.importFromUtil.importJsonFile(jsonBackupFile.toUri())
                }
                scenario.onActivity { act ->
                    act.onDocumentPicked(MainActivity.DocumentAction.IMPORT_IMAGES, imagesZipFile.toUri())
                }
                composeRule.awaitTextDisplayed(string(R.string.import_succeeded_text))

                val restoredFromJson = runBlocking { dao.getAll().first() }
                assertThat(restoredFromJson).hasSize(2)
                val restoredFile1 = File(Uri.parse(restoredFromJson[0].imagePath!!).path!!)
                val restoredFile2 = File(Uri.parse(restoredFromJson[1].imagePath!!).path!!)
                assertThat(restoredFile1.isFile).isTrue()
                assertThat(restoredFile2.isFile).isTrue()

                val bmp1 = BitmapFactory.decodeFile(restoredFile1.absolutePath)
                val bmp2 = BitmapFactory.decodeFile(restoredFile2.absolutePath)
                assertThat(Color.blue(bmp1.getPixel(10, 10))).isEqualTo(0xCC)
                assertThat(Color.red(bmp2.getPixel(10, 10))).isEqualTo(0xE2)

                runBlocking {
                    dao.deleteAll()
                    activity.importFromUtil.importKeyUriFile(keyUriBackupFile.toUri())
                }
                val restoredFromKeyUri = runBlocking { dao.getAll().first() }
                assertThat(restoredFromKeyUri).hasSize(2)
                assertThat(File(Uri.parse(restoredFromKeyUri[0].imagePath!!).path!!).isFile).isTrue()
                assertThat(File(Uri.parse(restoredFromKeyUri[1].imagePath!!).path!!).isFile).isTrue()
            }
        } finally {
            srcPng.delete()
            srcSvg.delete()
            jsonBackupFile.delete()
            keyUriBackupFile.delete()
            imagesZipFile.delete()
            context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    @Test
    fun testBuiltInIconRestoresFromJsonAndKeyUriWithoutImagesZip() {
        val context = getInstrumentation().targetContext
        val jsonBackupFile = File(context.cacheDir, "tokens_builtin_backup.json")
        val keyUriBackupFile = File(context.cacheDir, "tokens_builtin_backup.txt")
        val discordRef = TokenImageBackupUtil.toBuiltInImagePath(TokenImage.Discord.name)
        val githubRef = TokenImageBackupUtil.toBuiltInImagePath(TokenImage.GitHub.name)

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { activity = it }
                val dao = activity.otpTokenDatabase.otpTokenDao()

                runBlocking {
                    dao.deleteAll()
                    dao.insertAll(
                        listOf(
                            TestData.OTP_HOTP_TOKEN_1.copy(
                                id = 1L,
                                ordinal = 1L,
                                issuer = "My Custom Server",
                                label = "ops@internal.local",
                                imagePath = discordRef,
                            ),
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 2L,
                                ordinal = 2L,
                                issuer = "Google",
                                label = "override@example.com",
                                imagePath = githubRef,
                            ),
                        )
                    )

                    activity.importFromUtil.exportJsonFile(jsonBackupFile.toUri())
                    activity.importFromUtil.exportKeyUriFile(keyUriBackupFile.toUri())
                }

                runBlocking { dao.deleteAll() }
                context.filesDir.listFiles()?.forEach { it.deleteRecursively() }

                runBlocking {
                    activity.importFromUtil.importJsonFile(jsonBackupFile.toUri())
                }
                val restoredFromJson = runBlocking { dao.getAll().first() }
                assertThat(restoredFromJson).hasSize(2)
                assertThat(restoredFromJson[0].imagePath).isEqualTo(discordRef)
                assertThat(restoredFromJson[1].imagePath).isEqualTo(githubRef)

                val resolvedModel = resolveTokenAvatarModel(
                    restoredFromJson[0].imagePath,
                    restoredFromJson[0].issuer,
                    restoredFromJson[0].label,
                    context.filesDir,
                )
                assertThat(resolvedModel).isEqualTo(TokenAvatarModel.BuiltInVector(TokenImage.Discord.resource))

                runBlocking {
                    dao.deleteAll()
                    activity.importFromUtil.importKeyUriFile(keyUriBackupFile.toUri())
                }
                val restoredFromKeyUri = runBlocking { dao.getAll().first() }
                assertThat(restoredFromKeyUri).hasSize(2)
                assertThat(restoredFromKeyUri[0].imagePath).isEqualTo(discordRef)
                assertThat(restoredFromKeyUri[1].imagePath).isEqualTo(githubRef)
            }
        } finally {
            jsonBackupFile.delete()
            keyUriBackupFile.delete()
        }
    }

    @Test
    fun testImportMalformedImagesZipShowsFailureSnackbar() {
        val context = getInstrumentation().targetContext
        val badZip = File(context.cacheDir, "invalid_images.zip").apply {
            writeText("not a valid zip archive", StandardCharsets.UTF_8)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.onDocumentPicked(MainActivity.DocumentAction.IMPORT_IMAGES, badZip.toUri())
                }
                composeRule.awaitTextDisplayed(string(R.string.import_token_images_failed_text))
            }
        } finally {
            badZip.delete()
        }
    }
}
