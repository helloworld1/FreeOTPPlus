package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.squareup.picasso.Picasso
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.Token
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.matchToken

fun ImageView.setTokenImage(token: Token) {
    if (token.image != null) {
        Picasso.get()
                .load(token.image)
                .placeholder(R.drawable.logo)
                .into(this)
    } else {
        matchIssuerWithTokenThumbnail(token)?.let {
            setImageResource(it)
        } ?: run {
            setImageResource(R.drawable.logo)
        }
    }
}

private fun matchIssuerWithTokenThumbnail(token: Token): Int? {
    return TokenImage.values().firstOrNull {
        it.matchToken(token.issuer, token.label)
    }?.resource
}