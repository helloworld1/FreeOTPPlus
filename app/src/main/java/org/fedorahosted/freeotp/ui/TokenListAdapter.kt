package org.fedorahosted.freeotp.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.ListAdapter
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.fedorahosted.freeotp.util.Settings

class TokenListAdapter(
    private val activity: AppCompatActivity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val tokenCodeUtil: TokenCodeUtil,
    private val settings: Settings,
    private val tokenCryptoManager: TokenCryptoManager,
    private val clipboardUtil: ClipboardUtil,
    private val viewModel: MainViewModel,
    private val onEdit: (OtpToken) -> Unit,
    private val onDelete: (OtpToken) -> Unit,
    private val onShareQrCode: (OtpToken) -> Unit
) : ListAdapter<OtpToken, TokenViewHolder>(TokenItemCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TokenViewHolder {
        val tokenLayout = LayoutInflater.from(activity).inflate(R.layout.token, parent, false) as TokenLayout
        return TokenViewHolder(
            activity = activity,
            tokenLayout = tokenLayout,
            settings = settings,
            tokenCryptoManager = tokenCryptoManager,
            onEdit = onEdit,
            onDelete = onDelete,
            onShareQrCode = onShareQrCode
        )
    }

    override fun onBindViewHolder(
        holder: TokenViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isNotEmpty() && payloads.all { it == TokenItemCallback.PAYLOAD_COUNTER_CHANGED }) {
            holder.bindCounterUpdate(currentList[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: TokenViewHolder, position: Int) {
        val currentToken = currentList[position]
        holder.bind(currentToken)

        viewModel.getActiveTokenCode(currentToken.id)?.let { activeCodes ->
            holder.tokenLayout.start(currentToken.tokenType, activeCodes, animate = false)
        }

        holder.tokenLayout.setOnClickListener { v ->
            lifecycleScope.launch {
                // Fetch the token again to refresh the HOTP token counter
                otpTokenDatabase.otpTokenDao().get(currentToken.id).first()?.let { token ->
                    if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                        viewModel.onSessionStart()
                        return@let
                    }

                    val codes = try {
                        tokenCodeUtil.generateTokenCode(token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                            viewModel.onSessionStart()
                        } else {
                            Snackbar.make(v, R.string.invalid_token, Snackbar.LENGTH_SHORT).show()
                        }
                        return@let
                    }

                    val code = codes.currentCode
                    if (code.isNullOrEmpty()) {
                        if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                            viewModel.onSessionStart()
                        } else {
                            Snackbar.make(v, R.string.invalid_token, Snackbar.LENGTH_SHORT).show()
                        }
                        return@let
                    }

                    val tokenLayout = v as TokenLayout
                    if (token.tokenType == OtpTokenType.HOTP) {
                        otpTokenDatabase.otpTokenDao().incrementCounter(token.id)
                        val incrementedCounter = if (token.counter == Long.MAX_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            token.counter + 1L
                        }
                        holder.bindCounterUpdate(token.copy(counter = incrementedCounter))
                    }

                    if (settings.copyToClipboard) {
                        clipboardUtil.copySensitiveText(null, code)
                        if (ClipboardUtil.shouldShowAppCopyFeedback()) {
                            Snackbar.make(v, R.string.code_copied, Snackbar.LENGTH_SHORT).show()
                        }
                    }

                    viewModel.putActiveTokenCode(token.id, codes)
                    tokenLayout.start(token.tokenType, codes, true)
                }
            }
        }
    }
}
