package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.amulyakhare.textdrawable.util.ColorGenerator
import com.bumptech.glide.Glide
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.liberty.android.freeotp.token_images.matchToken
import org.liberty.android.freeotp.token_images.matchTokenImage

fun ImageView.setTokenImage(token: OtpToken) {
    when {
        token.imagePath != null -> {
            Glide.with(this)
                .load(token.imagePath)
                .placeholder(R.drawable.logo)
                .into(this)
        }
        !token.iconKey.isNullOrBlank() -> {
            matchTokenImage(token.iconKey)?.let {
                setImageResource(it.resource)
            } ?: setFallbackTextImage(token)
        }
        !token.issuer.isNullOrBlank() -> {
            matchIssuerWithTokenThumbnail(token)?.let {
                setImageResource(it)
            } ?: setFallbackTextImage(token)
        }
        else -> {
            setFallbackTextImage(token)
        }
    }
}

private fun ImageView.setFallbackTextImage(token: OtpToken) {
    val tokenText = token.displayName().substring(0, 1)
    val color = ColorGenerator.MATERIAL.getColor(tokenText)
    val tokenTextDrawable = TextDrawable.builder().buildRoundRect(tokenText, color, 10)
    setImageDrawable(tokenTextDrawable)
}

private fun matchIssuerWithTokenThumbnail(token: OtpToken): Int? {
    return org.liberty.android.freeotp.token_images.TokenImage.values().firstOrNull {
        it.matchToken(token.issuer, token.label)
    }?.resource
}
