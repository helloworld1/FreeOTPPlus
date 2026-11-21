package org.fedorahosted.freeotp.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.token.TokenLayout
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.Settings
import java.security.GeneralSecurityException

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

    private fun showMessage(messageRes: Int) {
        (activity as? MainActivity)?.showSnackbar(messageRes)
            ?: Snackbar.make(tokenLayout, messageRes, Snackbar.LENGTH_SHORT).show()
    }

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
                        (activity as? MainActivity)?.isSessionAuthenticated()
                        return@authenticateForSensitiveAction
                    }
                    try {
                        val plainToken = tokenCryptoManager.decryptToken(token)
                        val i = Intent(Intent.ACTION_VIEW, OtpTokenFactory.toUri(plainToken))
                        i.putExtra(MainActivity.SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA, activity.packageName)
                        activity.startActivity(Intent.createChooser(i, null))
                    } catch (e: GeneralSecurityException) {
                        Log.e(TAG, "Failed decrypting token for sharing", e)
                        showMessage(R.string.invalid_token)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Token vault locked before sharing token", e)
                        if (!tokenCryptoManager.isUnlocked()) {
                            (activity as? MainActivity)?.isSessionAuthenticated()
                        } else {
                            showMessage(R.string.invalid_token)
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid token URI for sharing", e)
                        showMessage(R.string.invalid_token)
                    } catch (e: ActivityNotFoundException) {
                        Log.e(TAG, "No activity available to share token", e)
                        showMessage(R.string.launch_file_browser_failure)
                    }
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

    companion object {
        private val TAG = TokenViewHolder::class.java.simpleName
    }
}
