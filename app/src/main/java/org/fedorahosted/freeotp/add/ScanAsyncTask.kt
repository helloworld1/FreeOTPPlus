/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.add

import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.os.AsyncTask

import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

open class ScanAsyncTask : AsyncTask<Void, Void, String>(), PreviewCallback {
    private class Data {
        var data: ByteArray? = null
        internal var size: Camera.Size? = null
    }

    private val mBlockingQueue: BlockingQueue<Data>
    private val mReader: Reader

    init {
        mBlockingQueue = LinkedBlockingQueue<Data>(5)
        mReader = QRCodeReader()
    }

    override fun doInBackground(vararg args: Void): String? {
        while (true) {
            try {
                val data = mBlockingQueue.take()
                val ls = PlanarYUVLuminanceSource(
                        data.data, data.size!!.width, data.size!!.height,
                        0, 0, data.size!!.width, data.size!!.height, false)
                val r = mReader.decode(BinaryBitmap(HybridBinarizer(ls)))
                return r.text
            } catch (e: InterruptedException) {
                return null
            } catch (e: NotFoundException) {
            } catch (e: ChecksumException) {
            } catch (e: FormatException) {
            } catch (e: ArrayIndexOutOfBoundsException) {
            } finally {
                mReader.reset()
            }
        }
    }

    override fun onPreviewFrame(data: ByteArray, camera: Camera) {
        val d = Data()
        d.data = data
        d.size = camera.parameters.previewSize
        mBlockingQueue.offer(d)
    }
}
