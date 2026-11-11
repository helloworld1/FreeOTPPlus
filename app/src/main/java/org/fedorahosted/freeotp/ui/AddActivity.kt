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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
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
    private lateinit var mCategory: AutoCompleteTextView
    private lateinit var mSecret: EditText
    private lateinit var mInterval: EditText
    private lateinit var mCounter: EditText
    private lateinit var mAlgorithm: Spinner
    private lateinit var mHOTP: RadioButton

    private var mImageURL: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.add)

        val buttonLayout = findViewById<View>(R.id.button_layout)
        ViewCompat.setOnApplyWindowInsetsListener(buttonLayout) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                insets.bottom
            )
            windowInsets
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        mImage = findViewById(R.id.image)
        mIssuer = findViewById(R.id.issuer)
        mLabel = findViewById(R.id.label)
        mCategory = findViewById(R.id.category)
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
        mCategory.addTextChangedListener(tw)
        mSecret.addTextChangedListener(AddSecretTextWatcher(this))
        mInterval.addTextChangedListener(tw)

        lifecycleScope.launch {
            val categories = otpTokenDatabase.otpTokenDao().getAllCategories().first().toMutableList()
            categories.add(0, getString(R.string.uncategorized))
            val adapter = ArrayAdapter(this@AddActivity, android.R.layout.simple_dropdown_item_1line, categories)
            mCategory.setAdapter(adapter)
            mCategory.threshold = 0
            mCategory.setOnClickListener {
                mCategory.showDropDown()
            }
            mCategory.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    mCategory.showDropDown()
                }
            }
            mCategory.setOnItemClickListener { _, _, position, _ ->
                if (position == 0) {
                    mCategory.setText("")
                }
            }
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.image -> startActivityForResult(Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), 0)

            R.id.cancel -> finish()

            R.id.add -> {
                // Get the fields
                val rawIssuer = mIssuer.text.toString()
                val rawLabel = mLabel.text.toString()
                val issuer = Uri.encode(rawIssuer)
                val label = Uri.encode(rawLabel)
                val secret = Uri.encode(mSecret.text.toString())
                val algorithm = mAlgorithm.selectedItem.toString().lowercase(Locale.US)
                val interval = Integer.parseInt(mInterval.text.toString())
                val digits = when {
                    findViewById<RadioButton>(R.id.digits5).isChecked -> 5
                    findViewById<RadioButton>(R.id.digits7).isChecked -> 7
                    findViewById<RadioButton>(R.id.digits8).isChecked -> 8
                    else -> 6
                }

                val path = when {
                    rawIssuer.isNotBlank() -> "$issuer:$label"
                    rawLabel.contains(':') -> ":$label"
                    else -> label
                }

                // Create the URI
                var uri = String.format(Locale.US,
                        "otpauth://%sotp/%s?secret=%s&algorithm=%s&digits=%d&period=%d",
                        if (mHOTP.isChecked) "h" else "t", path,
                        secret, algorithm, digits, interval)

                // Add optional parameters.
                if (rawIssuer.isNotBlank()) {
                    uri += String.format("&issuer=%s", issuer)
                }
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
                    val token = OtpTokenFactory.createFromUri(Uri.parse(uri)).copy(
                        issuer = rawIssuer.takeIf { it.isNotBlank() },
                        label = rawLabel,
                        category = mCategory.text.toString()
                    )
                    otpTokenDatabase.otpTokenDao().insert(token)
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
