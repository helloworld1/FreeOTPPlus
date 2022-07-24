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
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.common.util.Settings
import javax.inject.Inject

@AndroidEntryPoint
class SetupAuthenticationActivity : AppCompatActivity(), View.OnClickListener, TextWatcher {

    @Inject
    lateinit var settings: Settings

    private lateinit var mPassword: EditText
    private lateinit var mPasswordConfirm: EditText
    private lateinit var mSave: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.setup_authentication)

        setSupportActionBar(findViewById(R.id.toolbar))
        mPassword = findViewById(R.id.password)
        mPasswordConfirm = findViewById(R.id.password_confirm)

        // Setup the buttons
        findViewById<View>(R.id.cancel).setOnClickListener(this)
        mSave = findViewById(R.id.save)
        mSave.setOnClickListener(this)
        mSave.isEnabled = false

        // Set constraints on when the Save button is enabled
        mPassword.addTextChangedListener(this)
        mPasswordConfirm.addTextChangedListener(this)

        mPassword.requestFocus()
    }

    override fun onClick(view: View) {
        when (view.id) {

            R.id.cancel -> finish()

            R.id.save -> {
                // Store password
                lifecycleScope.launch {
                    settings.password = mPassword.text.toString()
                    settings.requireAuthentication = true
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        mSave.isEnabled = false

        if (mPassword.text.length < 3)
            return
        if (mPasswordConfirm.text.length < 3) {
            mPasswordConfirm.error = null
            return
        } else if (mPasswordConfirm.text.toString() != mPassword.text.toString()) {
            mPasswordConfirm.error = "Passwords do not match!"
            return
        }

        mSave.isEnabled = true
    }

    override fun afterTextChanged(s: Editable) {

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK) {
            lifecycleScope.launch {
                settings.requireAuthentication = true
            }
        }
    }
}
