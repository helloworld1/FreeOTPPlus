package org.fedorahosted.freeotp.data.util

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object CryptoEngine {
    const val MIN_KEY_LENGTH = 6
    const val DEFAULT_KDF_ITERATIONS = 150_000
    const val SALT_LENGTH_BYTES = 16
    const val KEY_LENGTH_BITS = 256
    const val KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8
    const val GCM_IV_LENGTH_BYTES = 12
    const val GCM_TAG_LENGTH_BITS = 128
    const val CIPHERTEXT_PREFIX = "v1:"

    private const val VERIFIER_PLAINTEXT = "FREEOTP_KEY_VERIFIER_V1"
    private val AAD_TOKEN_SECRET = "freeotp:token_secret:v1".toByteArray(Charsets.UTF_8)
    private val AAD_KEY_VERIFIER = "freeotp:key_verifier:v1".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): ByteArray {
        require(salt.isNotEmpty()) { "Salt must not be empty" }
        require(iterations > 0) { "Iterations must be positive" }

        return try {
            val spec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        } catch (_: NoSuchAlgorithmException) {
            // Fallback for Android API 23-25 where PBKDF2WithHmacSHA256 is not in SecretKeyFactory
            deriveKeyHmacSha256(passphrase, salt, iterations, KEY_LENGTH_BYTES)
        }
    }

    internal fun encodeUtf8(chars: CharArray): ByteArray {
        if (chars.isEmpty()) {
            return ByteArray(0)
        }
        val byteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(byteBuffer.remaining()).also { out ->
                byteBuffer.get(out)
            }
        } finally {
            if (byteBuffer.hasArray()) {
                byteBuffer.array().fill(0)
            }
        }
    }

    internal fun deriveKeyHmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int = KEY_LENGTH_BYTES
    ): ByteArray {
        val passwordBytes = encodeUtf8(passphrase)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes.takeIf { it.isNotEmpty() } ?: ByteArray(1), "HmacSHA256"))
            if (passwordBytes.isEmpty()) {
                // Standard HMAC-SHA256 with empty key uses zero-padded block, which SecretKeySpec rejects if 0-length
                mac.init(SecretKeySpec(ByteArray(64), "HmacSHA256"))
            }

            val hLen = mac.macLength
            val blocks = (keyLengthBytes + hLen - 1) / hLen
            val derived = ByteArray(keyLengthBytes)
            val blockIndexBytes = ByteBuffer.allocate(4)
            var offset = 0

            for (block in 1..blocks) {
                blockIndexBytes.clear()
                blockIndexBytes.putInt(block)
                mac.update(salt)
                val u = mac.doFinal(blockIndexBytes.array())
                val t = u.copyOf()

                var prev = u
                for (iter in 2..iterations) {
                    prev = mac.doFinal(prev)
                    for (j in t.indices) {
                        t[j] = (t[j].toInt() xor prev[j].toInt()).toByte()
                    }
                }

                val toCopy = minOf(hLen, keyLengthBytes - offset)
                System.arraycopy(t, 0, derived, offset, toCopy)
                offset += toCopy
            }

            return derived
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun createKeyVerifier(keyBytes: ByteArray): String {
        return encryptBytes(
            plaintext = VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8),
            keyBytes = keyBytes,
            aad = AAD_KEY_VERIFIER
        )
    }

    fun verifyKey(keyBytes: ByteArray, verifier: String): Boolean {
        return try {
            val decrypted = decryptBytes(verifier, keyBytes, AAD_KEY_VERIFIER)
            String(decrypted, Charsets.UTF_8) == VERIFIER_PLAINTEXT
        } catch (_: Exception) {
            false
        }
    }

    fun encryptSecret(plaintextSecret: String, keyBytes: ByteArray): String {
        val bytes = plaintextSecret.toByteArray(Charsets.UTF_8)
        try {
            return encryptBytes(bytes, keyBytes, AAD_TOKEN_SECRET)
        } finally {
            bytes.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decryptSecret(encryptedSecret: String, keyBytes: ByteArray): String {
        val bytes = decryptBytes(encryptedSecret, keyBytes, AAD_TOKEN_SECRET)
        try {
            return String(bytes, Charsets.UTF_8)
        } finally {
            bytes.fill(0)
        }
    }

    fun isEncryptedPayload(value: String?): Boolean {
        return value?.startsWith(CIPHERTEXT_PREFIX) == true
    }

    private fun encryptBytes(plaintext: ByteArray, keyBytes: ByteArray, aad: ByteArray): String {
        require(keyBytes.size == KEY_LENGTH_BYTES) { "Invalid key length: ${keyBytes.size}" }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return CIPHERTEXT_PREFIX + encodeBase64(combined)
    }

    @Throws(GeneralSecurityException::class)
    private fun decryptBytes(payload: String, keyBytes: ByteArray, aad: ByteArray): ByteArray {
        if (!payload.startsWith(CIPHERTEXT_PREFIX)) {
            throw GeneralSecurityException("Unsupported encrypted payload prefix")
        }
        if (keyBytes.size != KEY_LENGTH_BYTES) {
            throw GeneralSecurityException("Invalid key length")
        }

        val combined = try {
            decodeBase64(payload.removePrefix(CIPHERTEXT_PREFIX))
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid Base64 in encrypted payload", e)
        }

        val minLength = GCM_IV_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)
        if (combined.size < minLength) {
            throw GeneralSecurityException("Encrypted payload too short")
        }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyBytes, "AES"),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeBase64(encoded: String): ByteArray = Base64.decode(encoded)
}
