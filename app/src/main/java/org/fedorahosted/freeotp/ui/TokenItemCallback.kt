package org.fedorahosted.freeotp.ui

import androidx.recyclerview.widget.DiffUtil
import org.fedorahosted.freeotp.data.legacy.Token

class TokenItemCallback: DiffUtil.ItemCallback<Token>() {
    override fun areItemsTheSame(oldItem: Token, newItem: Token): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Token, newItem: Token): Boolean {
        return oldItem == newItem
    }
}