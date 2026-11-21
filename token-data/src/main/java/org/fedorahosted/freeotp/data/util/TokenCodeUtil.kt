package org.fedorahosted.freeotp.data.util

import com.google.android.apps.authenticator.Base32String
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.legacy.TokenCode
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class TokenCodeUtil private constructor(
    private val tokenCryptoManager: TokenCryptoManager?,
    @Suppress("UNUSED_PARAMETER") unused: Unit
) {
    @Inject
    constructor(tokenCryptoManager: TokenCryptoManager) : this(tokenCryptoManager, Unit)

    constructor() : this(null, Unit)

    fun generateTokenCode(otpToken: OtpToken): TokenCode {
        val cur = System.currentTimeMillis()
        val period = if (otpToken.period > 0) otpToken.period else 30
        val plaintextSecret = resolvePlaintextSecret(otpToken)

        when (otpToken.tokenType) {
            OtpTokenType.HOTP ->
                return TokenCode(
                    if (plaintextSecret != null) getHOTP(otpToken, plaintextSecret, otpToken.counter) else "",
                    cur,
                    cur + period * 1000L
                )
            OtpTokenType.TOTP -> {
                val counter: Long = cur / 1000 / period
                return TokenCode(
                    if (plaintextSecret != null) getHOTP(otpToken, plaintextSecret, counter + 0) else "",
                    (counter + 0) * period * 1000L,
                    (counter + 1) * period * 1000L,
                    TokenCode(
                        if (plaintextSecret != null) getHOTP(otpToken, plaintextSecret, counter + 1) else "",
                        (counter + 1) * period * 1000L,
                        (counter + 2) * period * 1000L
                    )
                )
            }
        }
    }

    private fun resolvePlaintextSecret(otpToken: OtpToken): String? {
        if (otpToken.encryptionType != EncryptionType.ENCRYPTED) {
            return otpToken.secret
        }
        val manager = tokenCryptoManager ?: return null
        return try {
            manager.decryptSecret(otpToken)
        } catch (e: GeneralSecurityException) {
            e.printStackTrace()
            null
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            null
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            null
        }
    }

    private fun getHOTP(otpToken: OtpToken, plaintextSecret: String, counter: Long): String {
        if (otpToken.digits <= 0) {
            return ""
        }

        // Encode counter in network byte order
        val bb = ByteBuffer.allocate(8)
        bb.putLong(counter)

        // Create digits divisor
        var div = 1
        for (i in otpToken.digits downTo 1) div *= 10

        // Create the HMAC
        try {
            val secretBytes = Base32String.decode(plaintextSecret)
            if (secretBytes.isEmpty()) {
                return ""
            }
            val mac = Mac.getInstance("Hmac${otpToken.algorithm}")
            mac.init(SecretKeySpec(secretBytes, "Hmac${otpToken.algorithm}"))

            // Do the hashing
            val digest = mac.doFinal(bb.array())
            if (digest.size < 4) {
                return ""
            }

            // Truncate (clamp offset to digest.size - 4 so 16-byte HmacMD5 never overruns when off is 13..15;
            // for SHA1/SHA256/SHA512 where digest.size >= 20, off <= 15 is completely unchanged)
            var binary: Int
            val off = (digest[digest.size - 1].toInt() and 0xf).coerceAtMost(digest.size - 4)
            binary = digest[off].toInt() and 0x7f shl 0x18
            binary = binary or (digest[off + 1].toInt() and 0xff shl 0x10)
            binary = binary or (digest[off + 2].toInt() and 0xff shl 0x08)
            binary = binary or (digest[off + 3].toInt() and 0xff)
            var hotp = ""
            if (otpToken.issuer == "Steam") {
                for (i in 0 until otpToken.digits) {
                    hotp += STEAMCHARS[binary % STEAMCHARS.size]
                    binary /= STEAMCHARS.size
                }
            } else {
                binary %= div

                // Zero pad
                hotp = binary.toString()
                while (hotp.length < otpToken.digits) hotp = "0$hotp"
            }
            return hotp
        } catch (e: Base32String.DecodingException) {
            e.printStackTrace()
        } catch (e: GeneralSecurityException) {
            e.printStackTrace()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: IndexOutOfBoundsException) {
            e.printStackTrace()
        } catch (e: ArithmeticException) {
            e.printStackTrace()
        }
        return ""
    }

    companion object {

        private val STEAMCHARS = charArrayOf(
            '2', '3', '4', '5', '6', '7', '8', '9', 'B', 'C',
            'D', 'F', 'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'T', 'V', 'W', 'X', 'Y'
        )

    }

}
