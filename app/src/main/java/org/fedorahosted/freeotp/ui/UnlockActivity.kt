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
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.common.encryption.EncryptDecrypt
import org.fedorahosted.freeotp.common.encryption.EncryptionType
import org.fedorahosted.freeotp.common.util.Settings
import javax.inject.Inject

@AndroidEntryPoint
class UnlockActivity : AppCompatActivity(), View.OnClickListener, TextWatcher {

    @Inject
    lateinit var settings: Settings

    @Inject
    lateinit var encryptDecrypt: EncryptDecrypt

    private lateinit var mPassword: EditText
    private lateinit var mUnlock: Button
    private lateinit var mFingerprint: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.authenticate)

        setSupportActionBar(findViewById(R.id.toolbar))
        mPassword = findViewById(R.id.password)

        // Setup the buttons
        mUnlock = findViewById(R.id.unlock)
        mUnlock.setOnClickListener(this)
        mUnlock.isEnabled = false

        // Set constraints on when the Save button is enabled
        mPassword.addTextChangedListener(this)
        mPassword.requestFocus()
    }

    override fun onClick(view: View) {
        when (view.id) {

            R.id.unlock -> {
                if (encryptDecrypt.validatePassword(
                        mPassword.text.toString(),
                        settings.password!!
                    )
                ) {
                    // Add the token
                    lifecycleScope.launch {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                } else {
                    mPassword.error = "Invalid Password, try again!"
                    Toast.makeText(this, "Invalid password entered, try again!", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        mUnlock.isEnabled = false

        if (mPassword.text.length < 3)
            return

        mUnlock.isEnabled = true
    }

    override fun afterTextChanged(s: Editable) {

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                // TODO set authstate of mainactivity
            }
        }
    }
}
