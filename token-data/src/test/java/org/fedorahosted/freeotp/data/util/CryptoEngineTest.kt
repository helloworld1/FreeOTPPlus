package org.fedorahosted.freeotp.data.util

import com.google.common.truth.Truth.assertThat
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class CryptoEngineTest {

    @Test
    fun deriveKey_matchesHmacSha256FallbackAcrossAsciiAndUnicode() {
        val salt = ByteArray(16) { (it * 7 + 3).toByte() }
        val passphrases = listOf(
            "correct-horse-battery-staple",
            "123456",
            "pässwörd-🔐-密钥-テスト"
        )

        for (passphrase in passphrases) {
            val k1 = CryptoEngine.deriveKey(passphrase.toCharArray(), salt, iterations = 2_000)
            val k2 = CryptoEngine.deriveKeyHmacSha256(passphrase.toCharArray(), salt, iterations = 2_000)
            assertThat(k1).hasLength(32)
            assertThat(k1).isEqualTo(k2)
        }
    }

    @Test
    fun deriveKey_matchesRfc7914Pbkdf2Sha256TestVector() {
        // RFC 7914 Section 11 test vector: P = "passwd", S = "salt", c = 1, dkLen = 32
        val key = CryptoEngine.deriveKeyHmacSha256(
            passphrase = "passwd".toCharArray(),
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 1,
            keyLengthBytes = 32
        )
        val hex = key.joinToString("") { "%02x".format(it) }
        assertThat(hex).isEqualTo("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc")
    }

    @Test
    fun encryptAndDecryptSecret_roundTripsWithRandomIv() {
        val salt = CryptoEngine.generateSalt()
        val key = CryptoEngine.deriveKey("mySecretKey123".toCharArray(), salt, iterations = 1_000)
        val originalSecret = "JBSWY3DPEHPK3PXP"

        val encrypted1 = CryptoEngine.encryptSecret(originalSecret, key)
        val encrypted2 = CryptoEngine.encryptSecret(originalSecret, key)

        assertThat(encrypted1).startsWith("v1:")
        assertThat(encrypted2).startsWith("v1:")
        // Random 12-byte IV ensures identical plaintexts encrypt to distinct ciphertexts
        assertThat(encrypted1).isNotEqualTo(encrypted2)

        assertThat(CryptoEngine.decryptSecret(encrypted1, key)).isEqualTo(originalSecret)
        assertThat(CryptoEngine.decryptSecret(encrypted2, key)).isEqualTo(originalSecret)
    }

    @Test
    fun decryptSecret_rejectsWrongKeyAndTamperedCiphertext() {
        val salt = CryptoEngine.generateSalt()
        val key1 = CryptoEngine.deriveKey("correctKey123".toCharArray(), salt, iterations = 1_000)
        val key2 = CryptoEngine.deriveKey("wrongKey456".toCharArray(), salt, iterations = 1_000)

        val encrypted = CryptoEngine.encryptSecret("JBSWY3DPEHPK3PXP", key1)

        assertThrows(GeneralSecurityException::class.java) {
            CryptoEngine.decryptSecret(encrypted, key2)
        }

        val raw = CryptoEngine.decodeBase64(encrypted.removePrefix("v1:"))
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0x01).toByte()
        val tampered = "v1:" + CryptoEngine.encodeBase64(raw)

        assertThrows(GeneralSecurityException::class.java) {
            CryptoEngine.decryptSecret(tampered, key1)
        }
    }

    @Test
    fun keyVerifier_enforcesDomainSeparationFromTokenSecrets() {
        val salt = CryptoEngine.generateSalt()
        val key = CryptoEngine.deriveKey("domainSeparationKey".toCharArray(), salt, iterations = 1_000)
        val wrongKey = CryptoEngine.deriveKey("otherKey999".toCharArray(), salt, iterations = 1_000)

        val verifier = CryptoEngine.createKeyVerifier(key)
        assertThat(CryptoEngine.verifyKey(key, verifier)).isTrue()
        assertThat(CryptoEngine.verifyKey(wrongKey, verifier)).isFalse()

        // Verifier cannot be decrypted as a token secret due to distinct GCM AAD
        assertThrows(GeneralSecurityException::class.java) {
            CryptoEngine.decryptSecret(verifier, key)
        }

        // Token secret cannot be used as a key verifier
        val encryptedSecret = CryptoEngine.encryptSecret("FREEOTP_KEY_VERIFIER_V1", key)
        assertThat(CryptoEngine.verifyKey(key, encryptedSecret)).isFalse()
    }

    @Test
    fun tokenCodeUtil_plainTextTokenWorksWithoutCryptoManager() {
        val token = OtpToken(
            id = 1L,
            ordinal = 1L,
            issuer = "github.com",
            label = "alice",
            imagePath = null,
            tokenType = OtpTokenType.HOTP,
            algorithm = "SHA256",
            secret = "abcd5432",
            digits = 6,
            counter = 0L,
            period = 30,
            encryptionType = EncryptionType.PLAIN_TEXT
        )

        val code = TokenCodeUtil().generateTokenCode(token)
        assertThat(code.currentCode).isEqualTo("047701")
    }

    @Test
    fun tokenCodeUtil_handlesWhitespaceInBase32AndNeverCrashesOnInvalidSecret() {
        // Secret containing newlines, tabs, hyphens, and non-breaking spaces decodes identically
        val formattedSecretToken = OtpToken(
            id = 1L,
            ordinal = 1L,
            issuer = "github.com",
            label = "alice",
            imagePath = null,
            tokenType = OtpTokenType.HOTP,
            algorithm = "SHA256",
            secret = "abcd\n5432\t\u00A0====",
            digits = 6,
            counter = 0L,
            period = 30,
            encryptionType = EncryptionType.PLAIN_TEXT
        )
        assertThat(TokenCodeUtil().generateTokenCode(formattedSecretToken).currentCode)
            .isEqualTo("047701")

        // Corrupted non-Base32 secret already in DB returns empty code instead of crashing
        val corruptedToken = formattedSecretToken.copy(secret = "0189_INVALID_BASE32!", period = 0)
        assertThat(TokenCodeUtil().generateTokenCode(corruptedToken).currentCode).isEmpty()

        // Padding-only empty secret returns empty code instead of throwing IllegalArgumentException in SecretKeySpec
        val emptyDecodedToken = formattedSecretToken.copy(secret = "========")
        assertThat(TokenCodeUtil().generateTokenCode(emptyDecodedToken).currentCode).isEmpty()
    }

    @Test
    fun tokenCodeUtil_preservesRfc4226HotpVectorsAndHandlesMd5OffsetsWithoutCrash() {
        val util = TokenCodeUtil()

        // 1. Official RFC 4226 Appendix D HOTP test vectors (Secret = ASCII "12345678901234567890")
        val rfcSecretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        val expectedRfcCodes = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489"
        )
        for ((counter, expected) in expectedRfcCodes.withIndex()) {
            val hotpToken = OtpToken(
                id = 1L,
                ordinal = 1L,
                issuer = "RFC4226",
                label = "test",
                imagePath = null,
                tokenType = OtpTokenType.HOTP,
                algorithm = "SHA1",
                secret = rfcSecretBase32,
                digits = 6,
                counter = counter.toLong(),
                period = 30,
                encryptionType = EncryptionType.PLAIN_TEXT
            )
            assertThat(util.generateTokenCode(hotpToken).currentCode).isEqualTo(expected)
        }

        // 2. HmacMD5 across 256 counters (covering all 0..15 low-nibble offsets including 13, 14, 15)
        for (counter in 0L until 256L) {
            val md5HotpToken = OtpToken(
                id = 2L,
                ordinal = 2L,
                issuer = "LegacyMD5",
                label = "user",
                imagePath = null,
                tokenType = OtpTokenType.HOTP,
                algorithm = "MD5",
                secret = rfcSecretBase32,
                digits = 6,
                counter = counter,
                period = 30,
                encryptionType = EncryptionType.PLAIN_TEXT
            )
            val code = util.generateTokenCode(md5HotpToken).currentCode
            assertThat(code).hasLength(6)
            assertThat(code!!.all { it.isDigit() }).isTrue()
        }
    }
}
