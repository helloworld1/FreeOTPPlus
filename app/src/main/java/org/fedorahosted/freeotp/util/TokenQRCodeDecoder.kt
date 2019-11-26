package org.fedorahosted.freeotp.util

import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenQRCodeDecoder @Inject constructor(private val qrCodeReader: QRCodeReader) {

    private val tag: String = TokenQRCodeDecoder::class.java.simpleName

    private lateinit var imageData: ByteArray

    fun parseQRCode(image: ImageProxy): String? {
        if (!::imageData.isInitialized || imageData.size < image.width * image.height) {
            imageData = ByteArray(image.width * image.height)
        }

        synchronized(imageData) {
            // Only Y component of YUV is needed

            val y = image.planes[0]
            val ySize = y.buffer.remaining()

            y.buffer.get(imageData, 0, ySize)

            val ls = PlanarYUVLuminanceSource(
                    imageData, image.width, image.height,
                    0, 0, image.width, image.height, false)

            return try {
                qrCodeReader.decode(BinaryBitmap(HybridBinarizer(ls))).text
            } catch (_: NotFoundException) {
                null
            } catch (e: ChecksumException) {
                e.printStackTrace()
                Log.e(tag, "error parsing qr code", e)
                null
            } catch (e: FormatException) {
                Log.e(tag, "error parsing qr code", e)
                null
            } finally {
                qrCodeReader.reset()
            }
        }
    }
}
