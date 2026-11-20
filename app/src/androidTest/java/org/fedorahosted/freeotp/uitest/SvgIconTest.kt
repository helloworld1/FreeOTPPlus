package org.fedorahosted.freeotp.uitest

import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.util.ImageUtil
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
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
        val svgFile = File(context.cacheDir, "direct-token-icon.svg")
        svgFile.writeText(TELEKOM_SVG, StandardCharsets.UTF_8)

        try {
            val decoded = Glide.with(context)
                .asBitmap()
                .load(svgFile.toUri().toString())
                .submit(256, 256)
                .get()
            assertThat(decoded).isNotNull()
            assertThat(decoded.width).isEqualTo(256)
            assertThat(decoded.height).isEqualTo(256)
            assertThat(Color.red(decoded.getPixel(4, 4))).isEqualTo(0xE2)

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    runBlocking {
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
}
