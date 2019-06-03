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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.fedorahosted.freeotp.token.Token
import org.fedorahosted.freeotp.token.TokenPersistence
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class TokenQRCodeDecoder @Inject constructor(private val qrCodeReader: QRCodeReader) {
    private val tag: String = TokenQRCodeDecoder::class.java.simpleName
    fun parseQRCode(image: Image): String? {
        // Only Y component of YUV is needed
        val y = image.planes[0]
        val ySize = y.buffer.remaining()
        val imageData = ByteArray(ySize)
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
