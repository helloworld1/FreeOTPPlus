package org.fedorahosted.freeotp.util

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.min

private const val BADGE_CORNER_FRACTION = 0.17f

internal fun extractInitialGlyph(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val firstCodePoint = trimmed.codePointAt(0)
    return String(Character.toChars(firstCodePoint)).uppercase(Locale.getDefault())
}

internal fun matchIssuerWithTokenThumbnail(issuer: String?, label: String?): Int? {
    return TokenImage.entries.firstOrNull {
        it.matchToken(issuer, label)
    }?.resource
}

/**
 * Clips custom user images to concentric rounded corners matching built-in badges and,
 * in Dark Mode, adds a subtle light backing plate if the uploaded transparent bitmap consists
 * almost entirely of dark monochrome strokes (e.g. black SVGs/PNGs that would vanish on #282A2D).
 */
internal class TokenCustomImageTransformation(
    private val isNightMode: Boolean,
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int,
    ): Bitmap {
        val width = toTransform.width.coerceAtLeast(1)
        val height = toTransform.height.coerceAtLeast(1)
        val needsDarkModeBacking = isNightMode && isDarkMonochromeOnTransparent(toTransform)
        val outW = if (needsDarkModeBacking) maxOf(width, height) else width
        val outH = if (needsDarkModeBacking) maxOf(width, height) else height
        val result = pool.get(outW, outH, Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.TRANSPARENT)

        val canvas = Canvas(result)
        val radius = min(outW, outH) * BADGE_CORNER_FRACTION
        val rect = RectF(0f, 0f, outW.toFloat(), outH.toFloat())

        if (needsDarkModeBacking) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0xF2, 0xF4, 0xF7)
            }
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            val pad = min(outW, outH) * 0.14f
            val availW = (outW - 2f * pad).coerceAtLeast(1f)
            val availH = (outH - 2f * pad).coerceAtLeast(1f)
            val scale = min(availW / width.toFloat(), availH / height.toFloat())
            val drawW = width * scale
            val drawH = height * scale
            val left = (outW - drawW) / 2f
            val top = (outH - drawH) / 2f
            val dst = RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(toTransform, null, dst, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(toTransform, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        return result
    }

    override fun equals(other: Any?): Boolean {
        return other is TokenCustomImageTransformation && other.isNightMode == isNightMode
    }

    override fun hashCode(): Int {
        return ID.hashCode() * 31 + if (isNightMode) 1 else 0
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(if (isNightMode) 1.toByte() else 0.toByte())
    }

    companion object {
        private const val ID = "org.fedorahosted.freeotp.util.TokenCustomImageTransformation.v1"
        private val ID_BYTES = ID.toByteArray(Charsets.UTF_8)

        internal fun isDarkMonochromeOnTransparent(bitmap: Bitmap): Boolean {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) return false
            val stepX = (w / 32).coerceAtLeast(1)
            val stepY = (h / 32).coerceAtLeast(1)
            var totalSamples = 0
            var transparentSamples = 0
            var opaqueSamples = 0
            var darkOpaqueSamples = 0

            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val pixel = bitmap.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    totalSamples++
                    if (alpha < 32) {
                        transparentSamples++
                    } else if (alpha >= 128) {
                        opaqueSamples++
                        val r = Color.red(pixel) / 255.0
                        val g = Color.green(pixel) / 255.0
                        val b = Color.blue(pixel) / 255.0
                        val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                        if (luma < 0.22) {
                            darkOpaqueSamples++
                        }
                    }
                    x += stepX
                }
                y += stepY
            }
            if (totalSamples == 0 || opaqueSamples == 0) return false
            val transparentFraction = transparentSamples.toDouble() / totalSamples.toDouble()
            val darkFraction = darkOpaqueSamples.toDouble() / opaqueSamples.toDouble()
            return transparentFraction >= 0.10 && darkFraction >= 0.75
        }
    }
}
