package org.fedorahosted.freeotp.util

import android.media.Image
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.lang.IllegalArgumentException
import javax.inject.Inject
import javax.inject.Singleton

const val QR_DECODER_MAX_IMAGE_WIDTH = 1920
const val QR_DECODER_MAX_IMAGE_HEIGHT = 1080

@Singleton
class TokenQRCodeDecoder @Inject constructor(private val qrCodeReader: QRCodeReader) {

    private val tag: String = TokenQRCodeDecoder::class.java.simpleName

    private val imageData: ByteArray = ByteArray(QR_DECODER_MAX_IMAGE_WIDTH * QR_DECODER_MAX_IMAGE_HEIGHT)

    fun parseQRCode(image: Image): String? {
        synchronized(imageData) {
            // Only Y component of YUV is needed

            val y = image.planes[0]
            val ySize = y.buffer.remaining()

            if (ySize > imageData.size) {
                throw IllegalArgumentException("Image size is larger than $QR_DECODER_MAX_IMAGE_WIDTH*$QR_DECODER_MAX_IMAGE_HEIGHT]")
            }

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
