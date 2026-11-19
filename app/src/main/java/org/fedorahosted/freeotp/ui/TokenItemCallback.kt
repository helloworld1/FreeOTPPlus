package org.fedorahosted.freeotp.ui

import androidx.recyclerview.widget.DiffUtil
import org.fedorahosted.freeotp.data.OtpToken

class TokenItemCallback : DiffUtil.ItemCallback<OtpToken>() {
    override fun areItemsTheSame(oldItem: OtpToken, newItem: OtpToken): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: OtpToken, newItem: OtpToken): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: OtpToken, newItem: OtpToken): Any? {
        if (oldItem.copy(counter = newItem.counter) == newItem) {
            return PAYLOAD_COUNTER_CHANGED
        }
        return null
    }

    companion object {
        const val PAYLOAD_COUNTER_CHANGED = "payload_counter_changed"
    }
}
