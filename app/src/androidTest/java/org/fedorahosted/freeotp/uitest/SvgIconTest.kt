package org.fedorahosted.freeotp.uitest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.bumptech.glide.Glide
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.components.TokenAvatarModel
import org.fedorahosted.freeotp.ui.components.TokenBadgePalette
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.resolveTokenAvatarModel
import org.fedorahosted.freeotp.uitest.TestWaitUtil.assertCardAtPositionHasText
import org.fedorahosted.freeotp.uitest.TestWaitUtil.awaitTokenCardCount
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.TokenCustomImageTransformation
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.liberty.android.freeotp.token_images.TokenImage
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4ClassRunner::class)
class SvgIconTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    companion object {
        // Exact SVG reported in https://github.com/helloworld1/FreeOTPPlus/issues/293
        private const val TELEKOM_SVG =
            """<?xml version="1.0" encoding="UTF-8"?><svg id="Ebene_1" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 60 60"><rect width="60" height="60" fill="#e20074"/><path d="M22.67,35.28h-6.67v-6.6h6.67v6.6Zm-6.67-21.78v11.22h2v-.33c0-5.28,3-8.58,8.67-8.58h.33v23.76c0,3.3-1.33,4.62-4.67,4.62h-1v2.31h17.33v-2.31h-1c-3.33,0-4.67-1.32-4.67-4.62V15.81h.33c5.67,0,8.67,3.3,8.67,8.58v.33h2V13.5H16Zm21.33,21.78h6.67v-6.6h-6.67v6.6Z" fill="#fff"/></svg>"""
    }

    @Test
    fun testTelekomSvgFromIssue293SavedViaImageUtil() {
        val context = getInstrumentation().targetContext
        val svgFile = File(context.cacheDir, "telekom-logo.svg")
        svgFile.writeText(TELEKOM_SVG, StandardCharsets.UTF_8)

        val imageUtil = ImageUtil(context)
        val savedUri = runBlocking {
            imageUtil.saveImageUriToFile(svgFile.toUri())
        }

        val savedFile = savedUri.toFile()
        try {
            assertThat(savedFile.exists()).isTrue()
            val bitmap = BitmapFactory.decodeFile(savedFile.absolutePath)
            assertThat(bitmap).isNotNull()
            assertThat(bitmap.width).isEqualTo(512)
            assertThat(bitmap.height).isEqualTo(512)

            val cornerPixel = bitmap.getPixel(8, 8)
            assertThat(Color.red(cornerPixel)).isEqualTo(0xE2)
            assertThat(Color.green(cornerPixel)).isEqualTo(0x00)
            assertThat(Color.blue(cornerPixel)).isEqualTo(0x74)

            val centerPixel = bitmap.getPixel(256, 256)
            assertThat(Color.red(centerPixel)).isGreaterThan(240)
            assertThat(Color.green(centerPixel)).isGreaterThan(240)
            assertThat(Color.blue(centerPixel)).isGreaterThan(240)
        } finally {
            savedFile.delete()
            svgFile.delete()
        }
    }

    @Test
    fun testSvgzWithoutViewBoxPreservesAspectRatio() {
        val context = getInstrumentation().targetContext
        val wideSvg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="#0088cc"/></svg>"""
        val svgzFile = File(context.cacheDir, "wide-banner.svgz")
        GZIPOutputStream(svgzFile.outputStream()).use { gz ->
            gz.write(wideSvg.toByteArray(StandardCharsets.UTF_8))
        }

        val imageUtil = ImageUtil(context)
        val savedUri = runBlocking {
            imageUtil.saveImageUriToFile(svgzFile.toUri())
        }

        val savedFile = savedUri.toFile()
        try {
            val bitmap = BitmapFactory.decodeFile(savedFile.absolutePath)
            assertThat(bitmap).isNotNull()
            assertThat(bitmap.width).isEqualTo(512)
            assertThat(bitmap.height).isEqualTo(256)
        } finally {
            savedFile.delete()
            svgzFile.delete()
        }
    }

    @Test
    fun testGlideDecodesSvgDirectlyAndRendersTokenCard() {
        val context = getInstrumentation().targetContext
        val svgFile = File(context.filesDir, "direct-token-icon.svg")
        svgFile.writeText(TELEKOM_SVG, StandardCharsets.UTF_8)

        try {
            val decoded = Glide.with(context)
                .asBitmap()
                .load(svgFile)
                .submit(256, 256)
                .get()
            assertThat(decoded).isNotNull()
            assertThat(decoded.width).isEqualTo(256)
            assertThat(decoded.height).isEqualTo(256)
            assertThat(Color.red(decoded.getPixel(4, 4))).isEqualTo(0xE2)

            var insertedId = 0L
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    runBlocking {
                        activity.otpTokenDatabase.otpTokenDao().deleteAll()
                        activity.otpTokenDatabase.otpTokenDao().insert(
                            TestData.OTP_TOTP_TOKEN_2.copy(
                                id = 0L,
                                ordinal = 0L,
                                issuer = "Telekom",
                                label = "user@telekom.de",
                                imagePath = svgFile.toUri().toString(),
                            )
                        )
                        insertedId = activity.otpTokenDatabase.otpTokenDao().getAll().first().single().id
                    }
                }

                composeRule.awaitTokenCardCount(1)
                composeRule.assertCardAtPositionHasText(0, "Telekom")
                composeRule.onNodeWithTag(UiTestTags.tokenAvatarTag(insertedId), useUnmergedTree = true)
                    .assertIsDisplayed()
            }
        } finally {
            svgFile.delete()
        }
    }

    @Test
    fun testManifestOmitsNetworkAndStoragePermissionsAndStripsExternalImageParams() {
        val context = getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val pkgInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = pkgInfo.requestedPermissions.orEmpty().toSet()
        assertThat(requested).contains(Manifest.permission.CAMERA)
        assertThat(requested).doesNotContain(Manifest.permission.INTERNET)
        assertThat(requested).doesNotContain(Manifest.permission.ACCESS_NETWORK_STATE)
        assertThat(requested).doesNotContain(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(requested).doesNotContain(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        val fromExternalQr = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=https%3A%2F%2Fevil.example%2Ftrack.png")
        )
        assertThat(fromExternalQr.imagePath).isNull()

        val fromExternalLocal = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=file%3A%2F%2F%2Fdata%2Fdata%2Fpkg%2Fdatabases%2Fotp-token-db")
        )
        assertThat(fromExternalLocal.imagePath).isNull()

        val fromBackup = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=file%3A%2F%2F%2Fdata%2Fuser%2F0%2Fpkg%2Ffiles%2Fimage_1.png"),
            allowImageParam = true,
        )
        assertThat(
            TokenImageBackupUtil.sanitizeImportedImagePath(fromBackup.imagePath, context.filesDir)
        ).isEqualTo(File(context.filesDir, "image_1.png").toUri().toString())
    }

    @Test
    fun testMaliciousXxeSvgRejectedCleanly() {
        val context = getInstrumentation().targetContext
        val xxeFile = File(context.cacheDir, "malicious.svg")
        xxeFile.writeText(
            """<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/hosts">]><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><text>&xxe;</text></svg>""",
            StandardCharsets.UTF_8,
        )
        val imageUtil = ImageUtil(context)
        try {
            assertThrows(IOException::class.java) {
                runBlocking {
                    imageUtil.saveImageUriToFile(xxeFile.toUri())
                }
            }
        } finally {
            xxeFile.delete()
        }
    }

    @Test
    fun testAllBuiltInTokenImageVectorDrawablesInflateAndRender() {
        val context = getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        try {
            val allResIds = TokenImage.entries.map { it.resource } + listOf(
                R.drawable.logo,
                R.drawable.edit_16px,
            )
            for (resId in allResIds) {
                val drawable = AppCompatResources.getDrawable(context, resId)
                assertThat(drawable).isNotNull()
                bitmap.eraseColor(Color.TRANSPARENT)
                drawable!!.setBounds(0, 0, 128, 128)
                drawable.draw(canvas)
                var nonTransparent = 0
                for (y in 24 until 104 step 8) {
                    for (x in 24 until 104 step 8) {
                        if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                            nonTransparent++
                        }
                    }
                }
                assertThat(nonTransparent).isGreaterThan(0)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun testBadgePaletteDeterminismAndDarkMonochromeCustomImageDetection() {
        val darkMono = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val colorful = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            // 1. TokenBadgePalette maps keys deterministically across all 17 Material colors
            val c1 = TokenBadgePalette.getColor("acme cloud")
            val c2 = TokenBadgePalette.getColor("acme cloud")
            assertThat(c1).isEqualTo(c2)
            assertThat(Color.alpha(c1)).isEqualTo(255)

            // 2. Dark monochrome transparent bitmap is detected for Dark Mode backing, colorful is not
            darkMono.eraseColor(Color.TRANSPARENT)
            val darkCanvas = Canvas(darkMono)
            val blackPaint = android.graphics.Paint().apply { color = Color.BLACK }
            darkCanvas.drawCircle(64f, 64f, 36f, blackPaint)
            assertThat(
                TokenCustomImageTransformation.isDarkMonochromeOnTransparent(darkMono)
            ).isTrue()

            colorful.eraseColor(Color.TRANSPARENT)
            val colorCanvas = Canvas(colorful)
            val bluePaint = android.graphics.Paint().apply { color = Color.rgb(0x1E, 0x88, 0xE5) }
            colorCanvas.drawCircle(64f, 64f, 36f, bluePaint)
            assertThat(
                TokenCustomImageTransformation.isDarkMonochromeOnTransparent(colorful)
            ).isFalse()
        } finally {
            darkMono.recycle()
            colorful.recycle()
        }
    }

    @Test
    fun testBuiltInIconRefAndResolveTokenAvatarModel() {
        val context = getInstrumentation().targetContext

        // Explicit builtin:<Name> reference overrides unmatched issuer and resolves BuiltInVector
        val builtInModel = resolveTokenAvatarModel(
            TokenImageBackupUtil.toBuiltInImagePath(TokenImage.GitHub.name),
            "Unmatched Custom Service",
            "user@example.com",
            context.filesDir,
        )
        assertThat(builtInModel).isEqualTo(TokenAvatarModel.BuiltInVector(TokenImage.GitHub.resource))

        // Unknown future builtin:<Name> falls back cleanly to InitialGlyph without crashing
        val unknownBuiltIn = resolveTokenAvatarModel(
            "builtin:FutureUnknownServiceIcon",
            "Unmatched Custom Service",
            "user@example.com",
            context.filesDir,
        )
        assertThat(unknownBuiltIn).isInstanceOf(TokenAvatarModel.InitialGlyph::class.java)
        assertThat((unknownBuiltIn as TokenAvatarModel.InitialGlyph).glyph).isEqualTo("U")

        // Matched issuer resolves MatchedServiceVector
        val matched = resolveTokenAvatarModel(null, "GitHub", "user@example.com", context.filesDir)
        assertThat(matched).isEqualTo(TokenAvatarModel.MatchedServiceVector(TokenImage.GitHub.resource))

        // Unmatched issuer with emoji resolves InitialGlyph with full surrogate pair
        val emojiModel = resolveTokenAvatarModel(null, "🔑 CustomVault", "user@example.com", context.filesDir)
        assertThat(emojiModel).isInstanceOf(TokenAvatarModel.InitialGlyph::class.java)
        assertThat((emojiModel as TokenAvatarModel.InitialGlyph).glyph).isEqualTo("🔑")

        // Blank issuer & label resolves DefaultLogo
        val blankModel = resolveTokenAvatarModel(null, "", "", context.filesDir)
        assertThat(blankModel).isEqualTo(TokenAvatarModel.DefaultLogo)
    }
}
