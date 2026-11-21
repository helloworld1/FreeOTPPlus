package org.fedorahosted.freeotp.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.databinding.ShareQrcodeBinding
import java.security.GeneralSecurityException
import javax.inject.Inject

@AndroidEntryPoint
class ShareQrCodeDialogFragment : DialogFragment() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = ShareQrcodeBinding.inflate(layoutInflater)
        val tokenId = arguments?.getLong(ARG_TOKEN_ID, 0L) ?: 0L

        val alertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_qr_code)
            .setView(binding.root)
            .setPositiveButton(R.string.ok_text, null)
            .create()

        lifecycleScope.launch {
            if (tokenId == 0L) {
                dismissAllowingStateLoss()
                return@launch
            }
            if (!tokenCryptoManager.isUnlocked()) {
                (activity as? MainActivity)?.isSessionAuthenticated()
                dismissAllowingStateLoss()
                return@launch
            }

            val storedToken = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: run {
                dismissAllowingStateLoss()
                return@launch
            }
            if (!isAdded) {
                dismissAllowingStateLoss()
                return@launch
            }
            if (!tokenCryptoManager.isUnlocked()) {
                (activity as? MainActivity)?.isSessionAuthenticated()
                dismissAllowingStateLoss()
                return@launch
            }

            val token = try {
                tokenCryptoManager.decryptToken(storedToken)
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Failed decrypting token for QR code", e)
                showInvalidTokenError()
                dismissAllowingStateLoss()
                return@launch
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Token vault locked before QR code could be generated", e)
                if (!tokenCryptoManager.isUnlocked()) {
                    (activity as? MainActivity)?.isSessionAuthenticated()
                } else {
                    showInvalidTokenError()
                }
                dismissAllowingStateLoss()
                return@launch
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid token for QR code", e)
                showInvalidTokenError()
                dismissAllowingStateLoss()
                return@launch
            }

            alertDialog.setTitle(if (token.issuer.isNullOrBlank()) token.label else token.issuer)
            if (!token.issuer.isNullOrBlank() && token.label.isNotBlank()) {
                binding.qrSubtitle.text = token.label
                binding.qrSubtitle.visibility = View.VISIBLE
            }

            val qrCodeSize = resources.getDimensionPixelSize(R.dimen.qr_code_size)

            val uriAndBitmap = try {
                val uriString = OtpTokenFactory.toUri(token).toString()
                val bitmap = withContext(Dispatchers.Default) {
                    val encoded = QRCodeWriter().encode(
                        uriString,
                        BarcodeFormat.QR_CODE,
                        qrCodeSize,
                        qrCodeSize
                    )
                    val pixels = IntArray(qrCodeSize * qrCodeSize)
                    for (y in 0 until qrCodeSize) {
                        val rowOffset = y * qrCodeSize
                        for (x in 0 until qrCodeSize) {
                            pixels[rowOffset + x] = if (encoded.get(x, y)) Color.BLACK else Color.WHITE
                        }
                    }
                    Bitmap.createBitmap(pixels, qrCodeSize, qrCodeSize, Bitmap.Config.RGB_565)
                }
                uriString to bitmap
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed generating QR code", e)
                null
            }

            if (!isAdded) return@launch

            if (uriAndBitmap != null) {
                val (uriString, qrBitmap) = uriAndBitmap
                binding.qrcode.setImageBitmap(qrBitmap)
                binding.copyUriButton.isEnabled = true
                binding.copyUriButton.setOnClickListener {
                    if (!isAdded) return@setOnClickListener
                    val mainActivity = activity as? MainActivity
                    if (!tokenCryptoManager.isUnlocked() || mainActivity?.isSessionAuthenticated() == false) {
                        mainActivity?.isSessionAuthenticated()
                        dismissAllowingStateLoss()
                        return@setOnClickListener
                    }
                    copyTokenUriToClipboard(uriString)
                    if (mainActivity != null) {
                        mainActivity.showSnackbar(R.string.token_uri_copied)
                    } else {
                        context?.let { Toast.makeText(it, R.string.token_uri_copied, Toast.LENGTH_SHORT).show() }
                    }
                    dismissAllowingStateLoss()
                }
            } else {
                showInvalidTokenError()
                dismissAllowingStateLoss()
            }
        }

        return alertDialog
    }

    private fun copyTokenUriToClipboard(uriString: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.share_qr_code), uriString)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE_KEY, true)
            }
        }
        clipboard.setPrimaryClip(clip)
    }

    private fun showInvalidTokenError() {
        if (!isAdded) return
        val mainActivity = activity as? MainActivity
        if (mainActivity != null) {
            mainActivity.showSnackbar(R.string.invalid_token)
        } else {
            context?.let { Toast.makeText(it, R.string.invalid_token, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        private val TAG = ShareQrCodeDialogFragment::class.java.simpleName
        private const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
        const val ARG_TOKEN_ID = "token_id"

        fun args(tokenId: Long): Bundle = Bundle().apply {
            putLong(ARG_TOKEN_ID, tokenId)
        }
    }
}
