package org.fedorahosted.freeotp.data

import android.net.Uri
import com.google.android.apps.authenticator.Base32String
import java.lang.IllegalArgumentException
import java.util.*
import javax.crypto.Mac

object OtpTokenFactory {
    fun createFromUri(uri: Uri): OtpToken {
        if (uri.scheme != "otpauth")
            throw IllegalArgumentException("URI does not starts with otpauth")

        val type =
            when (uri.authority) {
                "totp" -> OtpTokenType.TOTP
                "hotp" -> OtpTokenType.HOTP
                else -> throw IllegalArgumentException("URI does not contain totp or hotp type")
            }

        var path = uri.path
        if (path == null) {
            throw IllegalArgumentException("Token path is null")
        }

        // Strip the path of its leading '/'
        var j = 0
        while (path!![j] == '/') {
            path = path.substring(1)
            j++
        }

        if (path.isEmpty()) {
            throw IllegalArgumentException("Token path is empty")
        }

        val i = path.indexOf(':')
        val issuerExt = if (i < 0) "" else path.substring(0, i)
        val issuerInt = uri.getQueryParameter("issuer")

        val issuer = if (issuerInt?.isEmpty() == true) issuerExt else issuerInt
        val label = path.substring(if (i >= 0) i + 1 else 0)

        var algo = uri.getQueryParameter("algorithm")
        if (algo == null) algo = "sha1"
        algo = algo.uppercase(Locale.getDefault())

        Mac.getInstance("Hmac$algo")

        var d = uri.getQueryParameter("digits")
        if (d == null) {
            d = if (issuerExt == "Steam") "5" else "6"
        }
        val digits = d.toInt()
        if (issuerExt != "Steam" && digits != 6 && digits != 7 && digits != 8 && digits != 5)
            throw IllegalArgumentException("Digits must be 5 to 8")

        var p = uri.getQueryParameter("period")
        if (p == null) p = "30"
        val period = p.toInt()

        val counter = if (type == OtpTokenType.HOTP) {
            var c = uri.getQueryParameter("counter")
            if (c == null) c = "0"
            c.toLong() - 1
        } else {
            0
        }

        val secret = uri.getQueryParameter("secret") ?: throw IllegalArgumentException("Secret is null")
        val image = uri.getQueryParameter("image")

        return OtpToken (
            id = 0,
            ordinal = -System.currentTimeMillis(), // One way to make the token to the top of the list
            issuer = issuer,
            label = label,
            imagePath = image,
            tokenType = type,
            algorithm = algo,
            secret = secret,
            digits = digits,
            counter = counter,
            period = period,
            encryptionType = EncryptionType.PLAIN_TEXT
        )
    }
}