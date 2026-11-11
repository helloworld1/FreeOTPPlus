package org.fedorahosted.freeotp.data

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.fedorahosted.freeotp.data.legacy.SavedTokens
import org.junit.Test

class MigrationUtilTest {
    private val gson = Gson()

    @Test
    fun convertSavedTokens_importsTokensWithoutIssuer() {
        val json = """
            {
              "tokenOrder": [
                "plain_account",
                ":colon_prefixed_account",
                "empty_ext_account",
                "github.com:with_issuer",
                "InferredIssuer:missing_issuer_ext"
              ],
              "tokens": [
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "label": "plain_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "issuerExt": "",
                  "label": "colon_prefixed_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "issuerExt": "",
                  "label": "empty_ext_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA256",
                  "counter": 0,
                  "digits": 6,
                  "issuerExt": "github.com",
                  "label": "with_issuer",
                  "period": 30,
                  "secret": [8, 66, 29, 111, -99],
                  "type": "HOTP"
                },
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "label": "missing_issuer_ext",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                }
              ]
            }
        """.trimIndent()

        val savedTokens = gson.fromJson(json, SavedTokens::class.java)
        val result = MigrationUtil.convertSavedTokens(savedTokens)

        assertThat(result).hasSize(5)

        assertThat(result[0].issuer).isNull()
        assertThat(result[0].label).isEqualTo("plain_account")
        assertThat(result[0].secret).isEqualTo("AAAA2345")

        assertThat(result[1].issuer).isNull()
        assertThat(result[1].label).isEqualTo("colon_prefixed_account")

        assertThat(result[2].issuer).isNull()
        assertThat(result[2].label).isEqualTo("empty_ext_account")

        assertThat(result[3].issuer).isEqualTo("github.com")
        assertThat(result[3].label).isEqualTo("with_issuer")
        assertThat(result[3].tokenType).isEqualTo(OtpTokenType.HOTP)

        assertThat(result[4].issuer).isEqualTo("InferredIssuer")
        assertThat(result[4].label).isEqualTo("missing_issuer_ext")
    }

    @Test
    fun convertSavedTokens_preservesDuplicateAccountTokensWithoutIssuer() {
        val json = """
            {
              "tokenOrder": [
                "dup_account",
                "dup_account"
              ],
              "tokens": [
                {
                  "algo": "SHA1",
                  "counter": 0,
                  "digits": 6,
                  "label": "dup_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA256",
                  "counter": 0,
                  "digits": 8,
                  "label": "dup_account",
                  "period": 30,
                  "secret": [8, 66, 29, 111, -99],
                  "type": "TOTP"
                }
              ]
            }
        """.trimIndent()

        val savedTokens = gson.fromJson(json, SavedTokens::class.java)
        val result = MigrationUtil.convertSavedTokens(savedTokens)

        assertThat(result).hasSize(2)
        assertThat(result[0].issuer).isNull()
        assertThat(result[0].secret).isEqualTo("AAAA2345")
        assertThat(result[0].digits).isEqualTo(6)

        assertThat(result[1].issuer).isNull()
        assertThat(result[1].secret).isEqualTo("BBBB2345")
        assertThat(result[1].digits).isEqualTo(8)
    }

    @Test
    fun convertSavedTokens_ignoresEntryMissingLabel() {
        val json = """
            {
              "tokenOrder": [
                "",
                "valid_account"
              ],
              "tokens": [
                {
                  "algo": "SHA1",
                  "digits": 6,
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                },
                {
                  "algo": "SHA1",
                  "digits": 6,
                  "label": "valid_account",
                  "period": 30,
                  "secret": [0, 0, 13, 111, -99],
                  "type": "TOTP"
                }
              ]
            }
        """.trimIndent()

        val savedTokens = gson.fromJson(json, SavedTokens::class.java)
        val result = MigrationUtil.convertSavedTokens(savedTokens)

        assertThat(result).hasSize(1)
        assertThat(result[0].label).isEqualTo("valid_account")
        assertThat(result[0].issuer).isNull()
    }
}
