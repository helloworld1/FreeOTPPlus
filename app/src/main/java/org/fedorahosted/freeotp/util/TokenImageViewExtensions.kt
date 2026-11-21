package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.amulyakhare.textdrawable.util.ColorGenerator
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken

fun ImageView.setTokenImage(token: OtpToken) {
    val safeImageFile = TokenImageBackupUtil.resolveSafeLocalImageFile(
        token.imagePath,
        context.filesDir
    )
    if (safeImageFile != null) {
        Glide.with(this)
            .load(safeImageFile)
            .placeholder(R.drawable.logo)
            .error(R.drawable.logo)
            .signature(ObjectKey("${safeImageFile.length()}_${safeImageFile.lastModified()}"))
            .into(this)
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

private fun matchIssuerWithTokenThumbnail(token: OtpToken): Int? {
    return TokenImage.values().firstOrNull {
        it.matchToken(token.issuer, token.label)
    }?.resource
}
