package org.fedorahosted.freeotp.util

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

class SvgUtilTest {

    companion object {
        // Exact SVG reported in https://github.com/helloworld1/FreeOTPPlus/issues/293
        const val TELEKOM_SVG =
            """<?xml version="1.0" encoding="UTF-8"?><svg id="Ebene_1" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 60 60"><rect width="60" height="60" fill="#e20074"/><path d="M22.67,35.28h-6.67v-6.6h6.67v6.6Zm-6.67-21.78v11.22h2v-.33c0-5.28,3-8.58,8.67-8.58h.33v23.76c0,3.3-1.33,4.62-4.67,4.62h-1v2.31h17.33v-2.31h-1c-3.33,0-4.67-1.32-4.67-4.62V15.81h.33c5.67,0,8.67,3.3,8.67,8.58v.33h2V13.5H16Zm21.33,21.78h6.67v-6.6h-6.67v6.6Z" fill="#fff"/></svg>"""
    }

    @Test
    fun looksLikeSvg_detectsPlainAndGzippedSvgAndPreservesStreamPosition() {
        val rawBytes = TELEKOM_SVG.toByteArray(StandardCharsets.UTF_8)
        val plainStream = BufferedInputStream(ByteArrayInputStream(rawBytes))
        assertThat(SvgUtil.looksLikeSvg(plainStream)).isTrue()
        // Stream position must still be at byte 0 after sniffing
        assertThat(plainStream.readBytes()).isEqualTo(rawBytes)

        val svgzBytes = gzip(rawBytes)
        val gzStream = BufferedInputStream(ByteArrayInputStream(svgzBytes))
        assertThat(SvgUtil.looksLikeSvg(gzStream)).isTrue()
        assertThat(gzStream.readBytes()).isEqualTo(svgzBytes)
    }

    @Test
    fun looksLikeSvg_rejectsRasterFormatsPlainTextAndXxePayloads() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThat(SvgUtil.looksLikeSvg(ByteArrayInputStream(pngHeader))).isFalse()

        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertThat(SvgUtil.looksLikeSvg(ByteArrayInputStream(jpegHeader))).isFalse()

        val notSvg = "<html><body>not an svg</body></html>".toByteArray(StandardCharsets.UTF_8)
        assertThat(SvgUtil.looksLikeSvg(ByteArrayInputStream(notSvg))).isFalse()

        val xxeSvg = """<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><svg>&xxe;</svg>"""
            .toByteArray(StandardCharsets.UTF_8)
        assertThat(SvgUtil.looksLikeSvg(ByteArrayInputStream(xxeSvg))).isFalse()
    }

    @Test
    fun readAndSanitizeSvgXmlBytes_enforcesSizeAndEntityGuardsEvenForGzipBombs() {
        val xxeSvg = """<!DOCTYPE svg [<!ENTITY lol "lol">]><svg/>""".toByteArray(StandardCharsets.UTF_8)
        assertThrows(IOException::class.java) {
            SvgUtil.readAndSanitizeSvgXmlBytes(ByteArrayInputStream(xxeSvg))
        }

        // Gzip bomb: tiny compressed payload expanding past maxBytes
        val hugeExpandedSvg = ("<svg>" + " ".repeat(16_384) + "</svg>").toByteArray(StandardCharsets.UTF_8)
        val compressed = gzip(hugeExpandedSvg)
        assertThat(compressed.size).isLessThan(1024)
        assertThrows(IOException::class.java) {
            SvgUtil.readAndSanitizeSvgXmlBytes(ByteArrayInputStream(compressed), maxBytes = 4096)
        }
    }

    @Test
    fun computeTargetDimensions_preservesAspectRatio() {
        assertThat(SvgUtil.computeTargetDimensions(60f, 60f, 512, 512)).isEqualTo(512 to 512)
        assertThat(SvgUtil.computeTargetDimensions(200f, 100f, 512, 512)).isEqualTo(512 to 256)
        assertThat(SvgUtil.computeTargetDimensions(50f, 100f, 512, 512)).isEqualTo(256 to 512)
    }

    private fun gzip(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }
}
