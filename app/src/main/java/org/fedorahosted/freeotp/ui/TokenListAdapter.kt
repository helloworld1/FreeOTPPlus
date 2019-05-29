package org.fedorahosted.freeotp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.ArrayMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.Token
import org.fedorahosted.freeotp.token.TokenCode
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.UiLifecycleScope

class TokenListAdapter(val context: Context,
                       val tokenPersistence: TokenPersistence,
                       val uiLifecycleScope: UiLifecycleScope): ListAdapter<Token, TokenViewHolder>(TokenItemCallback()) {
    private val clipboardManager: ClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val tokenCodes: MutableMap<String, TokenCode> = ArrayMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val tokenLayout = LayoutInflater.from(context).inflate(R.layout.token, parent, false) as TokenLayout
        return TokenViewHolder(tokenLayout)
    }



    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val token = currentList[position]

        holder.bind(token)
        holder.tokenLayout.setOnClickListener { v ->
            uiLifecycleScope.launch {
                val codes = token.generateCodes() ?: return@launch
                tokenPersistence.save(token)

                // Copy code to clipboard.
                clipboardManager.primaryClip = ClipData.newPlainText(null, codes.currentCode)

                Snackbar.make(v, R.string.code_copied, Snackbar.LENGTH_SHORT).show()

                tokenCodes[token.id] = codes
                (v as TokenLayout).start(token.type, codes, true)
            }
        }
    }
}