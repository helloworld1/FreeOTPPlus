package org.fedorahosted.freeotp.ui

import android.content.Intent
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.Settings

class TokenViewHolder(
    private val activity: AppCompatActivity,
    val tokenLayout: TokenLayout,
    private val settings: Settings,
    private val tokenCryptoManager: TokenCryptoManager,
    private val onEdit: (OtpToken) -> Unit,
    private val onDelete: (OtpToken) -> Unit,
    private val onShareQrCode: (OtpToken) -> Unit
) : RecyclerView.ViewHolder(tokenLayout) {

    private var currentToken: OtpToken? = null

    private val menuItemClickListener = PopupMenu.OnMenuItemClickListener { item ->
        val token = currentToken ?: return@OnMenuItemClickListener true
        when (item.itemId) {
            R.id.action_edit -> {
                onEdit(token)
            }

            R.id.action_delete -> {
                onDelete(token)
            }

            R.id.action_share -> {
                AuthenticationUtil.authenticateForSensitiveAction(
                    activity,
                    settings,
                    tokenCryptoManager
                ) {
                    if (!tokenCryptoManager.isUnlocked()) {
                        return@authenticateForSensitiveAction
                    }
                    val plainToken = tokenCryptoManager.decryptToken(token)
                    val i = Intent(Intent.ACTION_VIEW, OtpTokenFactory.toUri(plainToken))
                    i.putExtra(MainActivity.SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA, activity.packageName)
                    activity.startActivity(Intent.createChooser(i, null))
                }
            }

            R.id.action_share_qr_code -> {
                AuthenticationUtil.authenticateForSensitiveAction(
                    activity,
                    settings,
                    tokenCryptoManager
                ) {
                    onShareQrCode(token)
                }
            }
        }
        true
    }

    fun bind(token: OtpToken) {
        currentToken = token
        tokenLayout.bind(token, R.menu.token, menuItemClickListener)
    }

    fun bindCounterUpdate(token: OtpToken) {
        currentToken = token
        tokenLayout.updateCounter(token)
    }
}
