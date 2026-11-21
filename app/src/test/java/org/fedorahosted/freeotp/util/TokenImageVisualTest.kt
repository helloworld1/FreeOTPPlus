package org.fedorahosted.freeotp.util

import com.amulyakhare.textdrawable.util.ColorGenerator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class TokenImageVisualTest {

    private fun srgbToLinear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b)
    }

    private fun contrastRatio(argb1: Int, argb2: Int): Double {
        val l1 = relativeLuminance(argb1)
        val l2 = relativeLuminance(argb2)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    @Test
    fun extractInitialGlyphHandlesAsciiWhitespaceAndEmojiSurrogatePairs() {
        assertThat(extractInitialGlyph("github")).isEqualTo("G")
        assertThat(extractInitialGlyph("  acme")).isEqualTo("A")
        assertThat(extractInitialGlyph("123Bank")).isEqualTo("1")
        assertThat(extractInitialGlyph("🔑 Vault")).isEqualTo("🔑")
        assertThat(extractInitialGlyph("🏢 Corp")).isEqualTo("🏢")
        assertThat(extractInitialGlyph("   ")).isEmpty()
    }

    @Test
    fun materialPaletteMeetsWcagAaWhiteTextAndCardContrastAcrossAllKeys() {
        val white = 0xFFFFFFFF.toInt()
        val lightCard = 0xFFE6E8EB.toInt()
        val darkCard = 0xFF282A2D.toInt()

        val sampledColors = mutableSetOf<Int>()
        for (i in 0 until 200) {
            sampledColors.add(ColorGenerator.MATERIAL.getColor("issuer_$i"))
        }
        // Also ensure Integer.MIN_VALUE hashCode does not crash
        val minIntKey = object {
            override fun hashCode(): Int = Int.MIN_VALUE
        }
        sampledColors.add(ColorGenerator.MATERIAL.getColor(minIntKey))

        assertThat(sampledColors.size).isEqualTo(17)
        for (color in sampledColors) {
            val hex = String.format("#%06X", color and 0xFFFFFF)
            assertWithMessage("White text contrast for $hex")
                .that(contrastRatio(color, white))
                .isAtLeast(4.5)
            assertWithMessage("Light card contrast for $hex")
                .that(contrastRatio(color, lightCard))
                .isAtLeast(3.5)
            assertWithMessage("Dark card contrast for $hex")
                .that(contrastRatio(color, darkCard))
                .isAtLeast(2.2)
        }
    }
}
