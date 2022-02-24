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
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
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
import org.fedorahosted.freeotp.databinding.EditBinding
import org.fedorahosted.freeotp.databinding.ShowQrcodeBinding
import org.fedorahosted.freeotp.util.ImageUtil
import org.fedorahosted.freeotp.util.setTokenImage
import javax.inject.Inject

@AndroidEntryPoint
class ShowQRCodeActivity : AppCompatActivity() {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var imageUtil: ImageUtil

    private lateinit var binding: ShowQrcodeBinding
    private lateinit var mIssuer: TextView
    private lateinit var mLabel: TextView

    private var tokenId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ShowQrcodeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, 0)

            // Get token values.
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: return@launch

            // Get references to widgets.
            mIssuer = findViewById<View>(R.id.issuer) as TextView
            mLabel = findViewById<View>(R.id.label) as TextView
            setSupportActionBar(findViewById(R.id.toolbar))

            mLabel.setText(token.label)
            mIssuer.setText(token.issuer)
            binding.imageView.setTokenImage(token)
            Log.d("TEST", OtpTokenFactory.toUri(token).toString());

            val qrcodeWriter = QRCodeWriter();
            var qrcodeSize = 400
            var encoded = qrcodeWriter.encode(OtpTokenFactory.toUri(token).toString(), BarcodeFormat.QR_CODE, qrcodeSize, qrcodeSize);
            var pixels = IntArray(qrcodeSize*qrcodeSize)
            for (x in 0..(qrcodeSize-1)) {
                for (y in 0..(qrcodeSize-1)) {
                    if (encoded.get(x, y)) {
                        pixels[x*qrcodeSize+y] = Color.BLACK;
                    } else {
                        pixels[x*qrcodeSize+y] = Color.WHITE;
                    }
                }
            }
            var qrcode = Bitmap.createBitmap(pixels, qrcodeSize, qrcodeSize, Bitmap.Config.RGB_565);
            binding.qrcode.setImageBitmap(qrcode);
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
    }
}
