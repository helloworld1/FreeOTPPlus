package org.fedorahosted.freeotp.data.legacy

import org.fedorahosted.freeotp.data.util.Base32String
import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class Base32AndLegacyTokenTest {

    @Test
    fun base32String_rfc4648AndRfc4226VectorsAndFormatting() {
        val vectors = listOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI",
            "12345678901234567890" to "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        )

        for ((plain, expectedBase32) in vectors) {
            val plainBytes = plain.toByteArray(StandardCharsets.US_ASCII)
            assertThat(Base32String.encode(plainBytes)).isEqualTo(expectedBase32)
            assertThat(Base32String.decode(expectedBase32)).isEqualTo(plainBytes)
        }

        // Case-insensitivity, hyphens, whitespace/NBSP, and trailing '=' padding
        val formatted = "  gezd-gnbv\tgy3t\nqojq\u00A0GEZD-GNBV-GY3T-QOJQ====  "
        assertThat(Base32String.decode(formatted))
            .isEqualTo("12345678901234567890".toByteArray(StandardCharsets.US_ASCII))

        assertThat(Base32String.decode(null)).isEmpty()
        assertThat(Base32String.decode("   --- === ")).isEmpty()

        // Full 0x00..0xFF byte value coverage (including negative signed bytes -128..-1)
        val allByteValues = ByteArray(256) { it.toByte() }
        val encodedAll = Base32String.encode(allByteValues)
        assertThat(Base32String.decode(encodedAll)).isEqualTo(allByteValues)

        for (invalid in listOf("0", "1", "8", "9", "JBSWY3DP!")) {
            assertThrows(Base32String.DecodingException::class.java) {
                Base32String.decode(invalid)
            }
        }
    }

    @Test
    fun legacyToken_gsonSerializationPreservesExactWireSchemaAndEqualsHashCode() {
        val gson = Gson()
        val legacyJson = """
            {
              "issuerInt": "GitHub",
              "issuerExt": "GitHub",
              "issuerAlt": "GitHub Enterprise",
              "label": "octocat@github.com",
              "labelAlt": "octocat-override@github.com",
              "image": "builtin:GitHub",
              "imageAlt": "builtin:GitLab",
              "type": "HOTP",
              "algo": "SHA256",
              "secret": [0, -1, 127, -128, 42],
              "digits": 8,
              "counter": 12,
              "period": 30,
              "category": "Work",
              "categories": ["Work", "Infra"]
            }
        """.trimIndent()

        val deserialized = gson.fromJson(legacyJson, Token::class.java)
        assertThat(deserialized.id).isEqualTo("GitHub:octocat@github.com")
        assertThat(deserialized.issuer).isEqualTo("GitHub Enterprise")
        assertThat(deserialized.label).isEqualTo("octocat-override@github.com")
        assertThat(deserialized.imageString).isEqualTo("builtin:GitLab")
        assertThat(deserialized.type).isEqualTo(Token.TokenType.HOTP)
        assertThat(deserialized.algorithm).isEqualTo("SHA256")
        assertThat(deserialized.secret).isEqualTo(Base32String.encode(byteArrayOf(0, -1, 127, -128, 42)))
        assertThat(deserialized.digits).isEqualTo(8)
        assertThat(deserialized.counter).isEqualTo(12L)
        assertThat(deserialized.period).isEqualTo(30)
        assertThat(deserialized.category).isEqualTo("Work")
        assertThat(deserialized.categories).containsExactly("Work", "Infra").inOrder()

        val reserialized = gson.toJson(deserialized)
        val jsonObj = JsonParser.parseString(reserialized).asJsonObject

        // Must preserve exact legacy field names with zero extra synthetic/backing fields
        assertThat(jsonObj.keySet()).containsExactly(
            "issuerInt",
            "issuerExt",
            "issuerAlt",
            "label",
            "labelAlt",
            "image",
            "imageAlt",
            "type",
            "algo",
            "secret",
            "digits",
            "counter",
            "period",
            "category",
            "categories",
        )

        val roundTripped = gson.fromJson(reserialized, Token::class.java)
        assertThat(roundTripped).isEqualTo(deserialized)
        assertThat(roundTripped.hashCode()).isEqualTo(deserialized.hashCode())

        val generated = roundTripped.generateCodes()
        assertThat(generated).isNotNull()
        assertThat(generated!!.currentCode).hasLength(8)
        assertThat(roundTripped.counter).isEqualTo(13L)
    }
}
