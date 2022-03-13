/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2014  Nathaniel McCallum, Red Hat
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

package org.fedorahosted.freeotp.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.databinding.ShareQrcodeBinding
import javax.inject.Inject

@AndroidEntryPoint
class ShareQrCodeActivity : AppCompatActivity() {

    @Inject
    lateinit var otpTokenDatabase: OtpTokenDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ShareQrcodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            val tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, 0)

            // Get token values.
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: return@launch
            title = token.issuer

            // Get references to widgets.
            val qrcodeWriter = QRCodeWriter();

            val qrCodeSize = resources.getDimensionPixelSize(R.dimen.qr_code_size)
            val encoded = qrcodeWriter.encode(
                OtpTokenFactory.toUri(token).toString(),
                BarcodeFormat.QR_CODE,
                qrCodeSize,
                qrCodeSize
            );
            val pixels = IntArray(qrCodeSize * qrCodeSize)
            for (x in 0 until qrCodeSize) {
                for (y in 0 until qrCodeSize) {
                    if (encoded.get(x, y)) {
                        pixels[x * qrCodeSize + y] = Color.BLACK;
                    } else {
                        pixels[x * qrCodeSize + y] = Color.WHITE;
                    }
                }
            }
            val qrcode =
                Bitmap.createBitmap(pixels, qrCodeSize, qrCodeSize, Bitmap.Config.RGB_565);
            binding.qrcode.setImageBitmap(qrcode);
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
