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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView.OnEditorActionListener
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.common.encryption.EncryptDecrypt
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
    private lateinit var mBiometric: Button


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.authenticate)

        setSupportActionBar(findViewById(R.id.toolbar))
        mPassword = findViewById(R.id.password)

        // Setup the buttons
        mUnlock = findViewById(R.id.unlock)
        mBiometric = findViewById(R.id.biometric)
        mUnlock.setOnClickListener(this)
        mBiometric.setOnClickListener(this)
        mBiometric.isEnabled = false
        mBiometric.isVisible = false
        mUnlock.isEnabled = false

        // Set constraints on when the Save button is enabled
        mPassword.addTextChangedListener(this)
        mPassword.setOnEditorActionListener { _, actionId, _ ->
            var handled = false
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onClick(mUnlock)
                handled = true
            }
            handled
        }

        when (BiometricManager.from(this).canAuthenticate()) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                mBiometric.isVisible = true
                mBiometric.isEnabled = true
                showBiometricPrompt()
            }
            else -> mPassword.requestFocus()
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.unlock -> {
                if (encryptDecrypt.validatePassword(
                        mPassword.text.toString(),
                        settings.password!!
                    )
                ) {
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
            R.id.biometric -> showBiometricPrompt()
        }
    }

    private fun showBiometricPrompt(){
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    // Don't show error message toast if user pressed back button
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        Toast.makeText(
                            applicationContext,
                            "${getString(R.string.authentication_error)} $errString",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }

                    if (errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        mPassword.requestFocus()
                    }
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    lifecycleScope.launch {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    // Invalid authentication, e.g. wrong fingerprint. Android auth UI shows an
                    // error, so no need for FreeOTP to show one
                    super.onAuthenticationFailed()

                    Toast.makeText(
                        applicationContext,
                        R.string.unable_to_authenticate, Toast.LENGTH_SHORT
                    )
                        .show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.authentication_dialog_title))
            .setSubtitle(getString(R.string.authentication_dialog_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
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
