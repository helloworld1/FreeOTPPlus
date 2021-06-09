package org.fedorahosted.freeotp.ui

import androidx.recyclerview.widget.DiffUtil
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.legacy.Token

class TokenItemCallback: DiffUtil.ItemCallback<OtpToken>() {
    override fun areItemsTheSame(oldItem: OtpToken, newItem: OtpToken): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: OtpToken, newItem: OtpToken): Boolean {
        return oldItem == newItem
    }
}