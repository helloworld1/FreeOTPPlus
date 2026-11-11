package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.amulyakhare.textdrawable.util.ColorGenerator
import com.bumptech.glide.Glide
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken

fun ImageView.setTokenImage(token: OtpToken) {
    if (token.imagePath != null) {
        Glide.with(this)
            .load(token.imagePath)
            .placeholder(R.drawable.logo)
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