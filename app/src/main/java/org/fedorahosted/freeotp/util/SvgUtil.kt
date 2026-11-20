package org.fedorahosted.freeotp.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

object SvgUtil {
    const val MAX_SVG_BYTES: Int = 2 * 1024 * 1024
    const val DEFAULT_RENDER_SIZE_PX: Int = 512
    const val MAX_RENDER_SIZE_PX: Int = 1024
    private const val HEADER_SNIFF_BYTES: Int = 8192

    /**
     * Peeks at the beginning of [source] (preserving stream position via mark/reset) to determine
     * whether the stream looks like an SVG or gzipped SVGZ document.
     */
    fun looksLikeSvg(source: InputStream): Boolean {
        if (!source.markSupported()) {
            return false
        }
        source.mark(HEADER_SNIFF_BYTES)
        val buffer = ByteArray(HEADER_SNIFF_BYTES)
        val totalRead = try {
            readUpTo(source, buffer, HEADER_SNIFF_BYTES)
        } catch (_: IOException) {
            return false
        } finally {
            try {
                source.reset()
            } catch (_: IOException) {
                return false
            }
        }
        if (totalRead < 4) {
            return false
        }
        if (hasKnownRasterMagic(buffer, totalRead)) {
            return false
        }

        val xmlPreviewBytes = if (isGzipMagic(buffer, totalRead)) {
            runCatching {
                GZIPInputStream(ByteArrayInputStream(buffer, 0, totalRead)).use { gz ->
                    val out = ByteArray(HEADER_SNIFF_BYTES)
                    val n = readUpTo(gz, out, HEADER_SNIFF_BYTES)
                    out.copyOf(n)
                }
            }.getOrNull() ?: return false
        } else {
            buffer.copyOf(totalRead)
        }

        return containsSafeSvgHeader(xmlPreviewBytes)
    }

    /**
     * Reads an SVG or SVGZ stream with strict size bounds, rejects XML entity declarations,
     * and renders it proportionally into an [Bitmap.Config.ARGB_8888] [Bitmap].
     */
    @Throws(IOException::class)
    fun renderSvgToBitmap(
        source: InputStream,
        requestedWidth: Int = DEFAULT_RENDER_SIZE_PX,
        requestedHeight: Int = DEFAULT_RENDER_SIZE_PX,
        bitmapProvider: (Int, Int, Bitmap.Config) -> Bitmap = { w, h, cfg ->
            Bitmap.createBitmap(w, h, cfg)
        }
    ): Bitmap {
        val xmlBytes = readAndSanitizeSvgXmlBytes(source)

        val svg = try {
            SVG.setInternalEntitiesEnabled(false)
            SVG.registerExternalFileResolver(null)
            SVG.getFromInputStream(ByteArrayInputStream(xmlBytes))
        } catch (e: SVGParseException) {
            throw IOException("Invalid SVG document", e)
        } catch (e: RuntimeException) {
            throw IOException("Failed to parse SVG document", e)
        }

        val viewBox = svg.documentViewBox
        val docW = svg.documentWidth
        val docH = svg.documentHeight
        if (viewBox == null && docW > 0f && docH > 0f) {
            svg.setDocumentViewBox(0f, 0f, docW, docH)
        }

        val effectiveBox = svg.documentViewBox
        val intrinsicW = when {
            effectiveBox != null && effectiveBox.width() > 0f -> effectiveBox.width()
            docW > 0f -> docW
            else -> DEFAULT_RENDER_SIZE_PX.toFloat()
        }
        val intrinsicH = when {
            effectiveBox != null && effectiveBox.height() > 0f -> effectiveBox.height()
            docH > 0f -> docH
            else -> DEFAULT_RENDER_SIZE_PX.toFloat()
        }

        val clampedReqW = requestedWidth.coerceIn(1, MAX_RENDER_SIZE_PX)
        val clampedReqH = requestedHeight.coerceIn(1, MAX_RENDER_SIZE_PX)
        val (outW, outH) = computeTargetDimensions(
            intrinsicW = intrinsicW,
            intrinsicH = intrinsicH,
            maxW = clampedReqW,
            maxH = clampedReqH
        )

        if (svg.documentViewBox != null && svg.documentPreserveAspectRatio == null) {
            svg.documentPreserveAspectRatio = PreserveAspectRatio.LETTERBOX
        }
        try {
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
        } catch (_: SVGParseException) {
            // Keep existing dimensions if setting percentage fails
        }

        val bitmap = bitmapProvider(outW, outH, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        svg.renderToCanvas(canvas, RectF(0f, 0f, outW.toFloat(), outH.toFloat()))
        return bitmap
    }

    internal fun computeTargetDimensions(
        intrinsicW: Float,
        intrinsicH: Float,
        maxW: Int,
        maxH: Int
    ): Pair<Int, Int> {
        val aspect = if (intrinsicW > 0f && intrinsicH > 0f && intrinsicW.isFinite() && intrinsicH.isFinite()) {
            intrinsicW / intrinsicH
        } else {
            1f
        }
        return if (maxW.toFloat() / maxH.toFloat() > aspect) {
            val h = maxH.coerceAtLeast(1)
            val w = (h * aspect).roundToInt().coerceIn(1, maxW.coerceAtLeast(1))
            w to h
        } else {
            val w = maxW.coerceAtLeast(1)
            val h = (w / aspect).roundToInt().coerceIn(1, maxH.coerceAtLeast(1))
            w to h
        }
    }

    @Throws(IOException::class)
    internal fun readAndSanitizeSvgXmlBytes(
        source: InputStream,
        maxBytes: Int = MAX_SVG_BYTES
    ): ByteArray {
        val rawBytes = readBoundedBytes(source, maxBytes)
        if (rawBytes.isEmpty()) {
            throw IOException("Empty SVG stream")
        }

        val xmlBytes = if (isGzipMagic(rawBytes, rawBytes.size)) {
            GZIPInputStream(ByteArrayInputStream(rawBytes)).use { gz ->
                readBoundedBytes(gz, maxBytes)
            }
        } else {
            rawBytes
        }

        val xmlTextLower = String(xmlBytes, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        if ("<!entity" in xmlTextLower) {
            throw IOException("SVG DTD entity declarations are not permitted")
        }
        if ("<svg" !in xmlTextLower) {
            throw IOException("Input does not contain an <svg> root element")
        }
        return xmlBytes
    }

    private fun containsSafeSvgHeader(bytes: ByteArray): Boolean {
        val preview = String(bytes, StandardCharsets.UTF_8).lowercase(Locale.ROOT)
        if ("<!entity" in preview) {
            return false
        }
        return "<svg" in preview
    }

    private fun isGzipMagic(bytes: ByteArray, length: Int): Boolean {
        return length >= 2 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte()
    }

    private fun hasKnownRasterMagic(bytes: ByteArray, length: Int): Boolean {
        if (length >= 4) {
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()
            ) return true
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) return true
            // GIF: 'G' 'I' 'F' '8'
            if (bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == '8'.code.toByte()
            ) return true
            // WEBP: 'R' 'I' 'F' 'F'
            if (bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) return true
        }
        // BMP: 'B' 'M'
        if (length >= 2 &&
            bytes[0] == 'B'.code.toByte() &&
            bytes[1] == 'M'.code.toByte()
        ) return true
        return false
    }

    private fun readUpTo(source: InputStream, buffer: ByteArray, maxLen: Int): Int {
        var offset = 0
        while (offset < maxLen) {
            val n = source.read(buffer, offset, maxLen - offset)
            if (n <= 0) break
            offset += n
        }
        return offset
    }

    @Throws(IOException::class)
    private fun readBoundedBytes(source: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = source.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) {
                throw IOException("SVG exceeds maximum size of $maxBytes bytes")
            }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
