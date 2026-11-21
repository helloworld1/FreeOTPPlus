package org.fedorahosted.freeotp.uitest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.bumptech.glide.Glide
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.setTokenImagePreview
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.liberty.android.freeotp.token_images.TokenImage
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4ClassRunner::class)
class SvgIconTest {

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

            // Corner pixel is Telekom magenta (#E20074)
            val cornerPixel = bitmap.getPixel(8, 8)
            assertThat(Color.red(cornerPixel)).isEqualTo(0xE2)
            assertThat(Color.green(cornerPixel)).isEqualTo(0x00)
            assertThat(Color.blue(cornerPixel)).isEqualTo(0x74)

            // Center vertical stem of the 'T' glyph (x=30/60 -> ~256, y=30/60 -> ~256) is white (#FFFFFF)
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
                                imagePath = svgFile.toUri().toString()
                            )
                        )
                    }
                }

                onView(withId(R.id.token_list))
                    .perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, scrollTo()))
                    .check(matches(hasDescendant(withText("Telekom"))))
                    .check(matches(hasDescendant(withId(R.id.image))))
                    .check(matches(isDisplayed()))
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
            PackageManager.GET_PERMISSIONS
        )
        val requested = pkgInfo.requestedPermissions.orEmpty().toSet()
        assertThat(requested).contains(Manifest.permission.CAMERA)
        assertThat(requested).doesNotContain(Manifest.permission.INTERNET)
        assertThat(requested).doesNotContain(Manifest.permission.ACCESS_NETWORK_STATE)
        assertThat(requested).doesNotContain(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertThat(requested).doesNotContain(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        // External QR / deep-link URI parsing ignores untrusted image= parameters by default
        val fromExternalQr = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=https%3A%2F%2Fevil.example%2Ftrack.png")
        )
        assertThat(fromExternalQr.imagePath).isNull()

        val fromExternalLocal = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=file%3A%2F%2F%2Fdata%2Fdata%2Fpkg%2Fdatabases%2Fotp-token-db")
        )
        assertThat(fromExternalLocal.imagePath).isNull()

        // Internal backup restore parsing allows image= only for filename relinking inside filesDir
        val fromBackup = OtpTokenFactory.createFromUri(
            Uri.parse("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&image=file%3A%2F%2F%2Fdata%2Fuser%2F0%2Fpkg%2Ffiles%2Fimage_1.png"),
            allowImageParam = true
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
            StandardCharsets.UTF_8
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
            val allResIds = TokenImage.values().map { it.resource } + listOf(
                org.fedorahosted.freeotp.R.drawable.logo,
                org.fedorahosted.freeotp.R.drawable.edit_16px
            )
            for (resId in allResIds) {
                val drawable = AppCompatResources.getDrawable(context, resId)
                assertThat(drawable).isNotNull()
                bitmap.eraseColor(Color.TRANSPARENT)
                drawable!!.setBounds(0, 0, 128, 128)
                drawable.draw(canvas)
                // Ensure the center area drew non-transparent pixels
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
    fun testProportionalTextDrawableAndDarkMonochromeCustomImageDetection() {
        val drawable = com.amulyakhare.textdrawable.TextDrawable.builder()
            .beginConfig()
            .bold()
            .endConfig()
            .buildRoundRectProportional("A", Color.rgb(0x19, 0x76, 0xD2), 0.17f)
        val bmp = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val darkMono = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val colorful = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            // 1. Proportional TextDrawable rounds corners (corner pixel transparent) and honors setAlpha on background
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(Canvas(bmp))
            assertThat(Color.alpha(bmp.getPixel(0, 0))).isEqualTo(0)
            assertThat(Color.alpha(bmp.getPixel(64, 64))).isEqualTo(255)

            bmp.eraseColor(Color.TRANSPARENT)
            drawable.alpha = 80
            drawable.draw(Canvas(bmp))
            assertThat(Color.alpha(bmp.getPixel(32, 32))).isLessThan(120)

            // 2. Dark monochrome transparent bitmap is detected for Dark Mode backing, colorful is not
            darkMono.eraseColor(Color.TRANSPARENT)
            val darkCanvas = Canvas(darkMono)
            val blackPaint = android.graphics.Paint().apply { color = Color.BLACK }
            darkCanvas.drawCircle(64f, 64f, 36f, blackPaint)
            assertThat(
                org.fedorahosted.freeotp.util.TokenCustomImageTransformation
                    .isDarkMonochromeOnTransparent(darkMono)
            ).isTrue()

            colorful.eraseColor(Color.TRANSPARENT)
            val colorCanvas = Canvas(colorful)
            val bluePaint = android.graphics.Paint().apply { color = Color.rgb(0x1E, 0x88, 0xE5) }
            colorCanvas.drawCircle(64f, 64f, 36f, bluePaint)
            assertThat(
                org.fedorahosted.freeotp.util.TokenCustomImageTransformation
                    .isDarkMonochromeOnTransparent(colorful)
            ).isFalse()
        } finally {
            bmp.recycle()
            darkMono.recycle()
            colorful.recycle()
        }
    }

    @Test
    fun testBuiltInIconRefAndSetTokenImagePreview() {
        val context = getInstrumentation().targetContext
        getInstrumentation().runOnMainSync {
            val imageView = android.widget.ImageView(context)

            // Explicit builtin:<Name> reference overrides unmatched issuer and renders vector drawable
            imageView.setTokenImagePreview(
                TokenImageBackupUtil.toBuiltInImagePath(TokenImage.GitHub.name),
                "Unmatched Custom Service",
                "user@example.com"
            )
            assertThat(imageView.drawable).isNotNull()
            assertThat(imageView.drawable).isNotInstanceOf(com.amulyakhare.textdrawable.TextDrawable::class.java)

            // Unknown future builtin:<Name> falls back cleanly without crashing
            imageView.setTokenImagePreview(
                "builtin:FutureUnknownServiceIcon",
                "Unmatched Custom Service",
                "user@example.com"
            )
            assertThat(imageView.drawable).isInstanceOf(com.amulyakhare.textdrawable.TextDrawable::class.java)

            // Matched issuer renders built-in drawable (not TextDrawable)
            imageView.setTokenImagePreview(null, "GitHub", "user@example.com")
            assertThat(imageView.drawable).isNotNull()
            assertThat(imageView.drawable).isNotInstanceOf(com.amulyakhare.textdrawable.TextDrawable::class.java)

            // Unmatched issuer with emoji renders proportional TextDrawable without throwing
            imageView.setTokenImagePreview(null, "🔑 CustomVault", "user@example.com")
            assertThat(imageView.drawable).isInstanceOf(com.amulyakhare.textdrawable.TextDrawable::class.java)

            // Blank issuer & label renders default logo
            imageView.setTokenImagePreview(null, "", "")
            assertThat(imageView.drawable).isNotNull()
            assertThat(imageView.drawable).isNotInstanceOf(com.amulyakhare.textdrawable.TextDrawable::class.java)
        }
    }
}
