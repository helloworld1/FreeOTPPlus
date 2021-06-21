package org.fedorahosted.freeotp.uitest

import android.net.Uri
import org.fedorahosted.freeotp.data.OtpTokenFactory

object TestData {
    val OTP_HOTP_TOKEN_1 = OtpTokenFactory.createFromUri(
        Uri.parse("otpauth://hotp/github.com:github%20account%201?secret=abcd5432&algorithm=SHA256&digits=6&period=30&lock=false&counter=0"))
        .copy(
            id = 1,
            ordinal = 1
        )

    val OTP_TOTP_TOKEN_2 = OtpTokenFactory.createFromUri(
        Uri.parse("otpauth://totp/microsoft.com:microsoft%20account%201?secret=bcde23456&algorithm=SHA512&digits=6&period=30&lock=false"))
        .copy(
            id = 2,
            ordinal = 2
        )

}