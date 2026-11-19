package org.fedorahosted.freeotp.ui

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.databinding.ShareQrcodeBinding
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
            if (tokenId == 0L || !tokenCryptoManager.isUnlocked()) {
                dismissAllowingStateLoss()
                return@launch
            }

            val storedToken = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: run {
                dismissAllowingStateLoss()
                return@launch
            }
            if (!tokenCryptoManager.isUnlocked()) {
                dismissAllowingStateLoss()
                return@launch
            }

            val token = tokenCryptoManager.decryptToken(storedToken)
            alertDialog.setTitle(if (token.issuer.isNullOrBlank()) token.label else token.issuer)
            if (!token.issuer.isNullOrBlank() && token.label.isNotBlank()) {
                binding.qrSubtitle.text = token.label
                binding.qrSubtitle.visibility = View.VISIBLE
            }

            val qrCodeSize = resources.getDimensionPixelSize(R.dimen.qr_code_size)
            val uriString = OtpTokenFactory.toUri(token).toString()

            val qrBitmap = try {
                withContext(Dispatchers.Default) {
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed generating QR code", e)
                null
            }

            if (qrBitmap != null) {
                binding.qrcode.setImageBitmap(qrBitmap)
            } else {
                Toast.makeText(requireContext(), R.string.invalid_token, Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
        }

        return alertDialog
    }

    companion object {
        private val TAG = ShareQrCodeDialogFragment::class.java.simpleName
        const val ARG_TOKEN_ID = "token_id"
    }
}
