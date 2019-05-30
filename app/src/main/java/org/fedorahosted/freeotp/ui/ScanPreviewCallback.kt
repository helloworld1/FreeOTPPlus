package org.fedorahosted.freeotp.ui

import android.hardware.Camera
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.token.Token
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.uiLifecycleScope
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ScanPreviewCallback(private val lifecycleOwner: LifecycleOwner,
                          private val tokenPersistence: TokenPersistence,
                          private val callback: (Token?) -> Unit)
    : Camera.PreviewCallback {
    private val qrCodeReader: Reader = QRCodeReader()
    private val lock = ReentrantLock()
    private var tokenFound = false

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (tokenFound) {
            return
        }

        val dataArray = data ?: run {
            Log.w(TAG, "onPreviewFrame with null data")
            return
        }
        val size = camera?.parameters?.previewSize ?: run {
            Log.w(TAG, "onPreviewFrame with null camera or size")
            return
        }

        lifecycleOwner.uiLifecycleScope {
            val tokenString = parseQrCode(dataArray, size) ?: return@uiLifecycleScope
            tokenFound = true
            val token = try {
                tokenPersistence.addFromUriString(tokenString)
            } catch (e: java.lang.Exception) {
                callback(null)
                return@uiLifecycleScope
            }

            callback(token)
        }
    }

    private suspend fun parseQrCode(data: ByteArray, size: Camera.Size): String? = withContext(Dispatchers.Default) {
        lock.withLock {
            try {
                val ls = PlanarYUVLuminanceSource(
                        data, size.width, size.height,
                        0, 0, size.width, size.height, false)
                val r = qrCodeReader.decode(BinaryBitmap(HybridBinarizer(ls)))
                return@withContext r.text
            } catch (e: InterruptedException) {
            } catch (e: NotFoundException) {
            } catch (e: ChecksumException) {
            } catch (e: FormatException) {
            } catch (e: ArrayIndexOutOfBoundsException) {
            } finally {
                qrCodeReader.reset()
            }
            return@withContext null
        }
    }

    companion object {
        val TAG = ScanPreviewCallback::class.java.simpleName
    }
}