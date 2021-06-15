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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.databinding.EditBinding
import org.fedorahosted.freeotp.util.ImageUtil
import javax.inject.Inject

@AndroidEntryPoint
class EditActivity : AppCompatActivity(), TextWatcher, View.OnClickListener {

    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var imageUtil: ImageUtil

    private lateinit var binding: EditBinding
    private lateinit var mIssuer: EditText
    private lateinit var mLabel: EditText
    private lateinit var mImage: ImageButton
    private lateinit var mRestore: Button
    private lateinit var mSave: Button

    private var mIssuerCurrent: String? = null
    private var mIssuerDefault: String? = null
    private var mLabelCurrent: String? = null
    private var mLabelDefault: String? = null

    private var mImageCurrent: Uri? = null
    private var mImageDefault: Uri? = null
    private var mImageDisplay: Uri? = null

    private var tokenId: Long = 0L

    private fun showImage(imageUri: Uri?) {
        mImageDisplay = imageUri
        onTextChanged(null, 0, 0, 0)

        Glide.with(this)
                .load(imageUri)
                .placeholder(R.drawable.logo)
                .into(mImage)

        // Remove user image option is only enabled if there is image to display
        binding.removeUserTokenImage.isEnabled = mImageDisplay != null
    }

    private fun imageIs(uri: Uri?): Boolean {
        return if (uri == null) mImageDisplay == null else uri == mImageDisplay
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = EditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lifecycleScope.launch {
            tokenId = intent.getLongExtra(EXTRA_TOKEN_ID, 0)

            // Get token values.
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: return@launch
            mIssuerCurrent = token.issuer
            mLabelCurrent = token.label
            mImageCurrent = if (token.imagePath != null) Uri.parse(token.imagePath) else null
            mIssuerDefault = token.issuer
            mLabelDefault = token.label
            mImageDefault = mImageCurrent

            // Get references to widgets.
            mIssuer = findViewById<View>(R.id.issuer) as EditText
            mLabel = findViewById<View>(R.id.label) as EditText
            mImage = findViewById<View>(R.id.image) as ImageButton
            mRestore = findViewById<View>(R.id.restore) as Button
            mSave = findViewById<View>(R.id.save) as Button
            setSupportActionBar(findViewById(R.id.toolbar))

            // Setup text changed listeners.
            mIssuer.addTextChangedListener(this@EditActivity)
            mLabel.addTextChangedListener(this@EditActivity)

            // Setup click callbacks.
            findViewById<View>(R.id.cancel).setOnClickListener(this@EditActivity)
            findViewById<View>(R.id.save).setOnClickListener(this@EditActivity)
            findViewById<View>(R.id.restore).setOnClickListener(this@EditActivity)
            binding.removeUserTokenImage.setOnClickListener(this@EditActivity)
            mImage.setOnClickListener(this@EditActivity)

            // Setup initial state. When the image is null, the image removal button will be
            // disabled and a placeholder image is displayed
            showImage(mImageCurrent)

            mLabel.setText(mLabelCurrent)
            mIssuer.setText(mIssuerCurrent)
            mIssuer.setSelection(mIssuer.text.length)

            // Token details
            val algorithmTextView = findViewById<TextView>(R.id.algorithm)
            algorithmTextView.text = token.algorithm

            val digitsTextView = findViewById<TextView>(R.id.digits)
            digitsTextView.text = token.digits.toString()

            val secretTextView = findViewById<TextView>(R.id.secret)
            secretTextView.text = token.secret

            val intervalTextView = findViewById<TextView>(R.id.interval)
            intervalTextView.text = token.period.toString()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == IMAGE_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                data?.data?.let {
                    lifecycleScope.launch {
                        val path = imageUtil.saveImageUriToFile(it)
                        showImage(path)
                    }
                } ?: let {
                    Toast.makeText(this, R.string.fail_to_add_image, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

    }

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        val label = mLabel.text.toString()
        val issuer = mIssuer.text.toString()
        mSave.isEnabled = label != mLabelCurrent || issuer != mIssuerCurrent || !imageIs(mImageCurrent)
        mRestore.isEnabled = label != mLabelDefault || issuer != mIssuerDefault || !imageIs(mImageDefault)
    }

    override fun afterTextChanged(s: Editable) {

    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.image -> {
                val intent = Intent(Intent.ACTION_PICK,
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                startActivityForResult(intent, IMAGE_REQUEST_CODE)
            }

            R.id.restore -> {
                mLabel.setText(mLabelDefault)
                mIssuer.setText(mIssuerDefault)
                mIssuer.setSelection(mIssuer.text.length)
                mImageDefault?.let {
                    showImage(it)
                }
            }

            R.id.save -> {
                lifecycleScope.launch {
                    val token = otpTokenDatabase.otpTokenDao().get(tokenId).first() ?: return@launch
                    val newToken = token.copy(
                        issuer = mIssuer.text.toString(),
                        label = mLabel.text.toString(),
                        imagePath = mImageDisplay?.toString()
                    )

                    otpTokenDatabase.otpTokenDao().update(newToken)
                    setResult(RESULT_OK)
                    finish()
                }
            }

            R.id.remove_user_token_image -> {
                showImage(null)
            }

            R.id.cancel -> finish()
        }
    }

    companion object {
        const val EXTRA_TOKEN_ID = "token_id"
        const val IMAGE_REQUEST_CODE = 60
    }
}
