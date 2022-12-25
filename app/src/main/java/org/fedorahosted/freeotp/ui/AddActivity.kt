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

package org.fedorahosted.freeotp.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.util.ImageUtil
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AddActivity : AppCompatActivity(), View.OnClickListener, CompoundButton.OnCheckedChangeListener {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var imageUtil: ImageUtil

    private val SHA1_OFFSET = 1
    private lateinit var mImage: ImageButton
    private lateinit var mIssuer: EditText
    private lateinit var mLabel: EditText
    private lateinit var mSecret: EditText
    private lateinit var mInterval: EditText
    private lateinit var mCounter: EditText
    private lateinit var mAlgorithm: Spinner
    private lateinit var mHOTP: RadioButton

    private var mImageURL: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.add)

        setSupportActionBar(findViewById(R.id.toolbar))
        mImage = findViewById(R.id.image)
        mIssuer = findViewById(R.id.issuer)
        mLabel = findViewById(R.id.label)
        mSecret = findViewById(R.id.secret)
        mInterval = findViewById(R.id.interval)
        mCounter = findViewById(R.id.counter)
        mAlgorithm = findViewById(R.id.algorithm)
        mHOTP = findViewById(R.id.hotp)

        // Select the default algorithm
        mAlgorithm.setSelection(SHA1_OFFSET)

        // Setup the Counter toggle
        mHOTP.setOnCheckedChangeListener(this)

        // Setup the buttons
        findViewById<View>(R.id.cancel).setOnClickListener(this)
        findViewById<View>(R.id.add).setOnClickListener(this)
        findViewById<View>(R.id.add).isEnabled = false
        mImage.setOnClickListener(this)

        // Set constraints on when the Add button is enabled
        val tw = AddTextWatcher(this)
        mIssuer.addTextChangedListener(tw)
        mLabel.addTextChangedListener(tw)
        mSecret.addTextChangedListener(AddSecretTextWatcher(this))
        mInterval.addTextChangedListener(tw)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.image -> startActivityForResult(Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 0)

            R.id.cancel -> finish()

            R.id.add -> {
                // Get the fields
                val issuer = Uri.encode(mIssuer.text.toString())
                val label = Uri.encode(mLabel.text.toString())
                val secret = Uri.encode(mSecret.text.toString())
                val algorithm = mAlgorithm.selectedItem.toString().lowercase(Locale.US)
                val interval = Integer.parseInt(mInterval.text.toString())
                val digits = when {
                    findViewById<RadioButton>(R.id.digits5).isChecked -> 5
                    findViewById<RadioButton>(R.id.digits7).isChecked -> 7
                    findViewById<RadioButton>(R.id.digits8).isChecked -> 8
                    else -> 6
                }

                // Create the URI
                var uri = String.format(Locale.US,
                        "otpauth://%sotp/%s:%s?secret=%s&algorithm=%s&digits=%d&period=%d",
                        if (mHOTP.isChecked) "h" else "t", issuer, label,
                        secret, algorithm, digits, interval)

                // Add optional parameters.
                if (mHOTP.isChecked) {
                    val counter = Integer.parseInt(mCounter.text.toString())
                    uri += String.format("&counter=%d", counter)
                }
                if (mImageURL != null) {
                    try {
                        val enc = URLEncoder.encode(mImageURL.toString(), "utf-8")
                        uri += String.format("&image=%s", enc)
                    } catch (e: UnsupportedEncodingException) {
                        e.printStackTrace()
                    }

                }

                // Add the token
                lifecycleScope.launch {
                    otpTokenDatabase.otpTokenDao().insert(OtpTokenFactory.createFromUri(Uri.parse(uri)))
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        findViewById<View>(R.id.counter_row).visibility = if (isChecked) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                mImageURL = data?.data?.let {
                    imageUtil.saveImageUriToFile(it)
                }

                Glide.with(this@AddActivity)
                        .load(mImageURL)
                        .placeholder(R.drawable.logo)
                        .into(mImage)

            }
        }
    }
}
