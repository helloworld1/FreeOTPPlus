package org.fedorahosted.freeotp.util

import android.widget.ImageView
import com.squareup.picasso.Picasso
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.Token
import org.fedorahosted.freeotp.token.TokenThumbnail
import org.fedorahosted.freeotp.token.matchIssuer

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
    return TokenThumbnail.values().firstOrNull {tokenThumbnail ->
        token.issuer ?.let {issuer ->
            tokenThumbnail.matchIssuer(issuer)
        } ?: false
    }?.resource
}