package org.fedorahosted.freeotp.util

import org.fedorahosted.freeotp.data.OtpToken

fun OtpToken.displayName(): String {
    return alias?.takeIf { it.isNotBlank() }
        ?: issuer?.takeIf { it.isNotBlank() }
        ?: label
}

fun String?.blankToNull(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}
