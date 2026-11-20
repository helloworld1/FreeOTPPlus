package org.fedorahosted.freeotp.util

import android.net.Uri
import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.amulyakhare.textdrawable.util.ColorGenerator
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken
import java.io.File

fun ImageView.setTokenImage(token: OtpToken) {
    if (token.imagePath != null) {
        var request = Glide.with(this)
            .load(token.imagePath)
            .placeholder(R.drawable.logo)
            .error(R.drawable.logo)
        localFileSignature(token.imagePath)?.let { signature ->
            request = request.signature(signature)
        }
        request.into(this)
        return
    }

    matchIssuerWithTokenThumbnail(token)?.let {
        setImageResource(it)
        return
    }

    val textSource = if (!token.issuer.isNullOrBlank()) token.issuer else token.label
    if (!textSource.isNullOrBlank()) {
        val tokenText = textSource.substring(0, 1)
        val color = ColorGenerator.MATERIAL.getColor(tokenText)
        val tokenTextDrawable = TextDrawable.builder().buildRoundRect(tokenText, color, 10)
        setImageDrawable(tokenTextDrawable)
    } else {
        setImageResource(R.drawable.logo)
    }
}

internal fun localFileSignature(imagePath: String?): ObjectKey? {
    if (imagePath.isNullOrBlank()) return null
    val parsed = runCatching { Uri.parse(imagePath) }.getOrNull() ?: return null
    if (!parsed.scheme.equals("file", ignoreCase = true)) return null
    val filePath = parsed.path ?: return null
    val file = File(filePath)
    if (!file.isFile) return null
    return ObjectKey("${file.length()}_${file.lastModified()}")
}

private fun matchIssuerWithTokenThumbnail(token: OtpToken): Int? {
    return TokenImage.values().firstOrNull {
        it.matchToken(token.issuer, token.label)
    }?.resource
}
