package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.amulyakhare.textdrawable.TextDrawable
import com.amulyakhare.textdrawable.util.ColorGenerator
import com.squareup.picasso.Picasso
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.Token
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken

fun ImageView.setTokenImage(token: Token) {
    when {
        token.image != null -> {
            Picasso.get()
                    .load(token.image)
                    .placeholder(R.drawable.logo)
                    .into(this)
        }
        token.issuer.isNotBlank() -> {
            matchIssuerWithTokenThumbnail(token)?.let {
                setImageResource(it)
            } ?: run {
                val tokenText = token.issuer.substring(0, 1)
                val color = ColorGenerator.MATERIAL.getColor(tokenText)
                val tokenTextDrawable = TextDrawable.builder().buildRoundRect(tokenText, color, 10)
                setImageDrawable(tokenTextDrawable)
            }
        }
        else -> {
            setImageResource(R.drawable.logo)
        }
    }
}

private fun matchIssuerWithTokenThumbnail(token: Token): Int? {
    return TokenImage.values().firstOrNull {
        it.matchToken(token.issuer, token.label)
    }?.resource
}