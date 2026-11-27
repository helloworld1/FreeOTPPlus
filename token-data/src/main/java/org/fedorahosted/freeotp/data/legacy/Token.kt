/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.data.legacy

import android.net.Uri
import org.fedorahosted.freeotp.data.util.Base32String
import org.fedorahosted.freeotp.data.util.Base32String.DecodingException
import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.Locale
import java.util.Objects
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Token {
    class TokenUriInvalidException : Exception() {
        companion object {
            private const val serialVersionUID: Long = -1108624734612362345L
        }
    }

    enum class TokenType {
        HOTP,
        TOTP,
    }

    private var issuerInt: String? = null
    private var issuerExt: String? = null
    private var issuerAlt: String? = null

    @SerializedName("label")
    private var rawLabel: String? = null
    private var labelAlt: String? = null

    @SerializedName("image")
    private var rawImage: String? = null
    private var imageAlt: String? = null

    var type: TokenType? = null
        private set

    @SerializedName("algo")
    private var algo: String? = null

    @SerializedName("secret")
    private var rawSecret: ByteArray? = null

    var digits: Int = 0
        private set

    var counter: Long = 0L
        private set

    var period: Int = 0
        private set

    var category: String? = null
    var categories: List<String>? = null

    @Suppress("unused")
    private constructor()

    @Throws(TokenUriInvalidException::class)
    private constructor(uri: Uri?, internal: Boolean) {
        if (uri == null || uri.scheme != "otpauth") {
            throw TokenUriInvalidException()
        }

        type = when (uri.authority) {
            "totp" -> TokenType.TOTP
            "hotp" -> TokenType.HOTP
            else -> throw TokenUriInvalidException()
        }

        var path = uri.path ?: throw TokenUriInvalidException()
        while (path.startsWith("/")) {
            path = path.substring(1)
        }
        if (path.isEmpty()) {
            throw TokenUriInvalidException()
        }

        issuerInt = uri.getQueryParameter("issuer")
        val internalIssuer = issuerInt
        if (!internalIssuer.isNullOrEmpty() && path.startsWith("$internalIssuer:")) {
            issuerExt = internalIssuer
            rawLabel = path.substring(internalIssuer.length + 1)
        } else {
            val i = path.indexOf(':')
            issuerExt = if (i < 0) "" else path.substring(0, i)
            rawLabel = path.substring(if (i >= 0) i + 1 else 0)
        }

        algo = (uri.getQueryParameter("algorithm") ?: "sha1").uppercase(Locale.US)
        try {
            Mac.getInstance("Hmac$algo")
        } catch (_: NoSuchAlgorithmException) {
            throw TokenUriInvalidException()
        }

        val isSteam = issuerExt == "Steam" || issuerInt == "Steam"
        try {
            val d = uri.getQueryParameter("digits") ?: if (isSteam) "5" else "6"
            digits = d.toInt()
            if (!isSteam && digits != 6 && digits != 7 && digits != 8 && digits != 5) {
                throw TokenUriInvalidException()
            }
        } catch (_: NumberFormatException) {
            throw TokenUriInvalidException()
        }

        try {
            val p = uri.getQueryParameter("period") ?: "30"
            period = p.toInt()
        } catch (_: NumberFormatException) {
            throw TokenUriInvalidException()
        }

        if (type == TokenType.HOTP) {
            try {
                val c = uri.getQueryParameter("counter") ?: "0"
                counter = c.toLong()
                if (counter < 0) {
                    throw TokenUriInvalidException()
                }
            } catch (_: NumberFormatException) {
                throw TokenUriInvalidException()
            }
        }

        try {
            val s = uri.getQueryParameter("secret")
            rawSecret = Base32String.decode(s)
        } catch (_: DecodingException) {
            throw TokenUriInvalidException()
        } catch (_: NullPointerException) {
            throw TokenUriInvalidException()
        }

        rawImage = uri.getQueryParameter("image")

        if (internal) {
            setIssuerInternal(uri.getQueryParameter("issueralt"))
            setLabelInternal(uri.getQueryParameter("labelalt"))
        }
    }

    @Throws(TokenUriInvalidException::class)
    constructor(uri: String?, internal: Boolean) : this(if (uri != null) Uri.parse(uri) else null, internal)

    @Throws(TokenUriInvalidException::class)
    constructor(uri: Uri) : this(uri, false)

    @Throws(TokenUriInvalidException::class)
    constructor(uri: String?) : this(uri, false)

    private fun getHOTP(counter: Long): String {
        val bb = ByteBuffer.allocate(8)
        bb.putLong(counter)

        var div = 1
        for (i in digits downTo 1) {
            div *= 10
        }

        try {
            val secretBytes = rawSecret ?: return ""
            val mac = Mac.getInstance("Hmac$algo")
            mac.init(SecretKeySpec(secretBytes, "Hmac$algo"))

            val digest = mac.doFinal(bb.array())
            if (digest.size < 4) {
                return ""
            }

            val off = minOf(digest[digest.size - 1].toInt() and 0xf, digest.size - 4)
            var binary = (digest[off].toInt() and 0x7f) shl 0x18
            binary = binary or ((digest[off + 1].toInt() and 0xff) shl 0x10)
            binary = binary or ((digest[off + 2].toInt() and 0xff) shl 0x08)
            binary = binary or (digest[off + 3].toInt() and 0xff)

            var hotp = ""
            if (issuerExt == "Steam") {
                for (i in 0 until digits) {
                    hotp += STEAMCHARS[binary % STEAMCHARS.size]
                    binary /= STEAMCHARS.size
                }
            } else {
                binary %= div
                hotp = binary.toString()
                while (hotp.length != digits) {
                    hotp = "0$hotp"
                }
            }

            return hotp
        } catch (e: InvalidKeyException) {
            e.printStackTrace()
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }

        return ""
    }

    val id: String?
        @JvmName("getID")
        get() = when {
            !issuerInt.isNullOrEmpty() -> "$issuerInt:$rawLabel"
            !issuerExt.isNullOrEmpty() -> "$issuerExt:$rawLabel"
            else -> rawLabel
        }

    // NOTE: This changes internal data. You MUST save the token immediately.
    var issuer: String
        get() = when {
            !issuerAlt.isNullOrEmpty() -> issuerAlt!!
            !issuerExt.isNullOrEmpty() -> issuerExt!!
            !issuerInt.isNullOrEmpty() -> issuerInt!!
            else -> ""
        }
        set(value) {
            setIssuerInternal(value)
        }

    private fun setIssuerInternal(value: String?) {
        issuerAlt = if (value == null || value == this.issuerExt) null else value
    }

    // NOTE: This changes internal data. You MUST save the token immediately.
    var label: String
        get() = labelAlt ?: (rawLabel ?: "")
        set(value) {
            setLabelInternal(value)
        }

    private fun setLabelInternal(value: String?) {
        labelAlt = if (value == null || value == this.rawLabel) null else value
    }

    // NOTE: This may change internal data. You MUST save the token immediately.
    fun generateCodes(): TokenCode? {
        val cur = System.currentTimeMillis()

        return when (type) {
            TokenType.HOTP -> TokenCode(getHOTP(counter++), cur, cur + (period * 1000))
            TokenType.TOTP -> {
                val totpCounter = cur / 1000 / period
                TokenCode(
                    getHOTP(totpCounter + 0),
                    (totpCounter + 0) * period * 1000,
                    (totpCounter + 1) * period * 1000,
                    TokenCode(
                        getHOTP(totpCounter + 1),
                        (totpCounter + 1) * period * 1000,
                        (totpCounter + 2) * period * 1000,
                    ),
                )
            }
            null -> null
        }
    }

    fun toUri(): Uri {
        val hasIssuerExt = !issuerExt.isNullOrEmpty()
        val issuerLabel = if (hasIssuerExt) "$issuerExt:$rawLabel" else rawLabel

        val secretBytes = rawSecret ?: throw NullPointerException("secret is null")
        val builder = Uri.Builder()
            .scheme("otpauth")
            .path(issuerLabel)
            .appendQueryParameter("secret", Base32String.encode(secretBytes))
            .appendQueryParameter("algorithm", algo)
            .appendQueryParameter("digits", digits.toString())
            .appendQueryParameter("period", period.toString())

        val issuerParam = issuerInt ?: issuerExt
        if (!issuerParam.isNullOrEmpty()) {
            builder.appendQueryParameter("issuer", issuerParam)
        }

        when (type) {
            TokenType.HOTP -> {
                builder.authority("hotp")
                builder.appendQueryParameter("counter", maxOf(0L, counter).toString())
            }
            TokenType.TOTP -> {
                builder.authority("totp")
            }
            null -> Unit
        }

        return builder.build()
    }

    override fun toString(): String = toUri().toString()

    var image: Uri?
        get() = when {
            imageAlt != null -> Uri.parse(imageAlt)
            rawImage != null -> Uri.parse(rawImage)
            else -> null
        }
        set(value) {
            imageAlt = null
            if (value == null) return
            if (this.rawImage == null || Uri.parse(this.rawImage) != value) {
                imageAlt = value.toString()
            }
        }

    val imageString: String?
        get() = imageAlt ?: rawImage

    val secret: String
        get() = Base32String.encode(rawSecret ?: throw NullPointerException("secret is null"))

    val algorithm: String?
        get() = algo

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Token
        return digits == other.digits &&
            counter == other.counter &&
            period == other.period &&
            issuerInt == other.issuerInt &&
            issuerExt == other.issuerExt &&
            issuerAlt == other.issuerAlt &&
            rawLabel == other.rawLabel &&
            labelAlt == other.labelAlt &&
            rawImage == other.rawImage &&
            imageAlt == other.imageAlt &&
            type == other.type &&
            algo == other.algo &&
            rawSecret.contentEquals(other.rawSecret) &&
            category == other.category &&
            categories == other.categories
    }

    override fun hashCode(): Int {
        var result = Objects.hash(
            issuerInt,
            issuerExt,
            issuerAlt,
            rawLabel,
            labelAlt,
            rawImage,
            imageAlt,
            type,
            algo,
            digits,
            counter,
            period,
            category,
            categories,
        )
        result = 31 * result + rawSecret.contentHashCode()
        return result
    }

    companion object {
        private val STEAMCHARS = charArrayOf(
            '2', '3', '4', '5', '6', '7', '8', '9', 'B', 'C',
            'D', 'F', 'G', 'H', 'J', 'K', 'M', 'N', 'P', 'Q',
            'R', 'T', 'V', 'W', 'X', 'Y',
        )
    }
}
