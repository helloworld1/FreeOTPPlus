package org.fedorahosted.freeotp.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

@Singleton
class ImageUtil @Inject constructor(@ApplicationContext val context: Context) {

    suspend fun saveImageUriToFile(uri: Uri): Uri = withContext(Dispatchers.IO) {
        val bitmap = decodeUriToBitmap(uri)
        saveBitmapToFile(bitmap)
    }

    suspend fun saveDrawableResToFile(@DrawableRes drawableResId: Int): Uri = withContext(Dispatchers.IO) {
        val drawable = ContextCompat.getDrawable(context, drawableResId)?.mutate()
            ?: throw IOException("Unable to load drawable resource $drawableResId")
        val bitmap = Bitmap.createBitmap(MAX_SAVED_ICON_PX, MAX_SAVED_ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, MAX_SAVED_ICON_PX, MAX_SAVED_ICON_PX)
        drawable.draw(canvas)
        saveBitmapToFile(bitmap)
    }

    private fun saveBitmapToFile(bitmap: Bitmap): Uri {
        val outputFile = File(context.filesDir, "image_${UUID.randomUUID()}.png")

        outputFile.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IOException("Failed to compress bitmap to PNG")
            }
        }

        return outputFile.toUri()
    }

    @Throws(IOException::class)
    private fun decodeUriToBitmap(uri: Uri): Bitmap {
        val mimeType = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        val pathLower = uri.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
        val isExplicitSvg = mimeType == "image/svg+xml" ||
            pathLower.endsWith(".svg") ||
            pathLower.endsWith(".svgz")

        if (isExplicitSvg) {
            openStreamOrThrow(uri).use { stream ->
                return SvgUtil.renderSvgToBitmap(
                    source = stream,
                    requestedWidth = MAX_SAVED_ICON_PX,
                    requestedHeight = MAX_SAVED_ICON_PX
                )
            }
        }

        // Sniff stream first in case a file provider returns application/octet-stream for an .svg
        val sniffedSvg = openStreamOrThrow(uri).use { raw ->
            val buffered = BufferedInputStream(raw, 16 * 1024)
            if (SvgUtil.looksLikeSvg(buffered)) {
                SvgUtil.renderSvgToBitmap(
                    source = buffered,
                    requestedWidth = MAX_SAVED_ICON_PX,
                    requestedHeight = MAX_SAVED_ICON_PX
                )
            } else {
                null
            }
        }
        if (sniffedSvg != null) {
            return sniffedSvg
        }

        // Two-pass raster decode with downsampling so large camera photos never exhaust memory
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStreamOrThrow(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            throw IOException("Unsupported or corrupt image at $uri")
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = boundsOpts.outWidth,
                height = boundsOpts.outHeight,
                reqWidth = MAX_SAVED_ICON_PX,
                reqHeight = MAX_SAVED_ICON_PX
            )
        }
        val decoded = openStreamOrThrow(uri).use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: throw IOException("Failed to decode raster image at $uri")

        return scaleDownIfNeeded(decoded, MAX_SAVED_ICON_PX)
    }

    @Throws(IOException::class)
    private fun openStreamOrThrow(uri: Uri) =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("Unable to open input stream for $uri")

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val largestSide = max(bitmap.width, bitmap.height)
        if (largestSide <= maxDimension) {
            return bitmap
        }
        val scale = maxDimension.toFloat() / largestSide.toFloat()
        val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    companion object {
        internal const val MAX_SAVED_ICON_PX = 512
    }
}
