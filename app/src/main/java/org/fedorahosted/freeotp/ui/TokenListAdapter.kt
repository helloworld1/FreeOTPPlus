package org.fedorahosted.freeotp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.ArrayMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenCode
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.token.TokenPersistence

class TokenListAdapter(val context: Context,
                       val tokenPersistence: TokenPersistence): RecyclerView.Adapter<TokenViewHolder>() {
    val clipboardManager: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val tokenCodes: MutableMap<String, TokenCode> = ArrayMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val tokenLayout = LayoutInflater.from(context).inflate(R.layout.token, parent, false) as TokenLayout
        return TokenViewHolder(tokenLayout)
    }

    override fun getItemCount(): Int = tokenPersistence.length()

    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val token = tokenPersistence[position]
        if (token != null) {
            holder.tokenLayout.bind(token, R.menu.token, getOnMenuItemClick(position))
        }

        holder.tokenLayout.setOnClickListener(View.OnClickListener { v ->
            val codes = token?.generateCodes() ?: return@OnClickListener
            tokenPersistence.save(token)

            // Copy code to clipboard.
            clipboardManager.primaryClip = ClipData.newPlainText(null, codes.currentCode)

            Snackbar.make(v, R.string.code_copied, Snackbar.LENGTH_SHORT).show()

            tokenCodes[token.id] = codes
            (v as TokenLayout).start(token.type, codes, true)
        })
    }


    fun getOnMenuItemClick(position: Int) = PopupMenu.OnMenuItemClickListener {item ->
        when (item.itemId) {
            R.id.action_edit -> {
                val i = Intent(context, EditActivity::class.java)
                i.putExtra(EditActivity.EXTRA_POSITION, position)
                context.startActivity(i)
            }

            R.id.action_delete -> {
                val i = Intent(context, DeleteActivity::class.java)
                i.putExtra(DeleteActivity.EXTRA_POSITION, position)
                context.startActivity(i)
            }
        }
        true
    }

}