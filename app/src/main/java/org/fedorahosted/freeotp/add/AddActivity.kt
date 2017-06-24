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

import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.util.Locale

import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.TokenPersistence

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextWatcher
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner

import com.squareup.picasso.Picasso

class AddActivity : Activity(), View.OnClickListener, CompoundButton.OnCheckedChangeListener {
    private val SHA1_OFFSET = 1
    private var mImage: ImageButton? = null
    private var mIssuer: EditText? = null
    private var mLabel: EditText? = null
    private var mSecret: EditText? = null
    private var mInterval: EditText? = null
    private var mCounter: EditText? = null
    private var mAlgorithm: Spinner? = null
    private var mHOTP: RadioButton? = null

    private var mImageURL: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.add)

        mImage = findViewById(R.id.image) as ImageButton
        mIssuer = findViewById(R.id.issuer) as EditText
        mLabel = findViewById(R.id.label) as EditText
        mSecret = findViewById(R.id.secret) as EditText
        mInterval = findViewById(R.id.interval) as EditText
        mCounter = findViewById(R.id.counter) as EditText
        mAlgorithm = findViewById(R.id.algorithm) as Spinner
        mHOTP = findViewById(R.id.hotp) as RadioButton

        // Select the default algorithm
        mAlgorithm!!.setSelection(SHA1_OFFSET)

        // Setup the Counter toggle
        mHOTP!!.setOnCheckedChangeListener(this)

        // Setup the buttons
        findViewById(R.id.cancel).setOnClickListener(this)
        findViewById(R.id.add).setOnClickListener(this)
        findViewById(R.id.add).isEnabled = false
        mImage!!.setOnClickListener(this)

        // Set constraints on when the Add button is enabled
        val tw = AddTextWatcher(this)
        mIssuer!!.addTextChangedListener(tw)
        mLabel!!.addTextChangedListener(tw)
        mSecret!!.addTextChangedListener(AddSecretTextWatcher(this))
        mInterval!!.addTextChangedListener(tw)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.image -> startActivityForResult(Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 0)

            R.id.cancel -> finish()

            R.id.add -> {
                // Get the fields
                val issuer = Uri.encode(mIssuer!!.text.toString())
                val label = Uri.encode(mLabel!!.text.toString())
                val secret = Uri.encode(mSecret!!.text.toString())
                val algorithm = mAlgorithm!!.selectedItem.toString().toLowerCase(Locale.US)
                val interval = Integer.parseInt(mInterval!!.text.toString())
                val digits = if ((findViewById(R.id.digits6) as RadioButton).isChecked) 6 else 8

                // Create the URI
                var uri = String.format(Locale.US,
                        "otpauth://%sotp/%s:%s?secret=%s&algorithm=%s&digits=%d&period=%d",
                        if (mHOTP!!.isChecked) "h" else "t", issuer, label,
                        secret, algorithm, digits, interval)

                // Add optional parameters.
                if (mHOTP!!.isChecked) {
                    val counter = Integer.parseInt(mCounter!!.text.toString())
                    uri = uri + String.format("&counter=%d", counter)
                }
                if (mImageURL != null) {
                    try {
                        val enc = URLEncoder.encode(mImageURL!!.toString(), "utf-8")
                        uri = uri + String.format("&image=%s", enc)
                    } catch (e: UnsupportedEncodingException) {
                        e.printStackTrace()
                    }

                }

                // Add the token
                if (TokenPersistence.addWithToast(this, uri) != null)
                    finish()
            }
        }
    }

    override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
        findViewById(R.id.counter_row).visibility = if (isChecked) View.VISIBLE else View.GONE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            mImageURL = data.data
            Picasso.with(this)
                    .load(mImageURL)
                    .placeholder(R.drawable.logo)
                    .into(mImage)
        }
    }
}
