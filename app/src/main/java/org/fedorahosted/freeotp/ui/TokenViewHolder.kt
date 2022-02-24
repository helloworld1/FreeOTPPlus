package org.fedorahosted.freeotp.ui

import android.app.Activity
import android.content.Intent
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.token.TokenLayout

class TokenViewHolder(private val activity: Activity,
                      val tokenLayout: TokenLayout) : RecyclerView.ViewHolder(tokenLayout) {
    fun bind(token: OtpToken) {
        tokenLayout.bind(token, R.menu.token, getOnMenuItemClick(token))
    }

    fun getOnMenuItemClick(token: OtpToken) = PopupMenu.OnMenuItemClickListener { item ->
        when (item.itemId) {
            R.id.action_edit -> {
                val i = Intent(tokenLayout.context, EditActivity::class.java)
                i.putExtra(EditActivity.EXTRA_TOKEN_ID, token.id)
                activity.startActivity(i)
            }

            R.id.action_delete -> {
                val i = Intent(tokenLayout.context, DeleteActivity::class.java)
                i.putExtra(DeleteActivity.EXTRA_TOKEN_ID, token.id)
                activity.startActivity(i)
            }

            R.id.action_show_qrcode -> {
                val i = Intent(tokenLayout.context, ShowQRCodeActivity::class.java)
                i.putExtra(EditActivity.EXTRA_TOKEN_ID, token.id)
                activity.startActivity(i)
            }
        }
        true
    }

}