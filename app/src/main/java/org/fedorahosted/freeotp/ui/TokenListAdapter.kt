package org.fedorahosted.freeotp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.ArrayMap
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ListAdapter
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@ActivityScoped
class TokenListAdapter @Inject constructor(@ActivityContext val context: Context,
                                           val otpTokenDatabase: OtpTokenDatabase,
                                           val tokenCodeUtil: TokenCodeUtil,
                                           val settings: Settings) : ListAdapter<OtpToken, TokenViewHolder>(TokenItemCallback()) {
    val activity = context as AppCompatActivity
    private val clipboardManager: ClipboardManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val tokenCodes: MutableMap<Long, TokenCode> = ArrayMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val tokenLayout = LayoutInflater.from(activity).inflate(R.layout.token, parent, false) as TokenLayout
        return TokenViewHolder(activity, tokenLayout)
    }

    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val token = currentList[position]

        holder.bind(token)
        holder.tokenLayout.setOnClickListener { v ->
            activity.lifecycleScope.launch {
                val codes = tokenCodeUtil.generateTokenCode(token)

                if (token.tokenType == OtpTokenType.HOTP) {
                    otpTokenDatabase.otpTokenDao().incrementCounter(token.id)
                }

                if (settings.copyToClipboard) {
                    // Copy code to clipboard.
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(null, codes.currentCode))
                    Snackbar.make(v, R.string.code_copied, Snackbar.LENGTH_SHORT).show()
                }

                tokenCodes[token.id] = codes

                (v as TokenLayout).start(token.tokenType, codes, true)
            }
        }
    }
}