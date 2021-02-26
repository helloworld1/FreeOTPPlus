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

/*
 * Portions Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.SearchView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.squareup.picasso.Picasso
import dagger.android.AndroidInjection
import kotlinx.android.synthetic.main.main.*
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.ImportExportUtil
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.uiLifecycleScope
import javax.inject.Inject

class MainActivity : AppCompatActivity() {
    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var tokenPersistence: TokenPersistence

    private lateinit var tokenListAdapter: TokenListAdapter
    private var searchQuery = ""
    private var menu: Menu? = null

    private val tokenListObserver: AdapterDataObserver = object: AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            token_list.scrollToPosition(positionStart)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidInjection.inject(this)
        onNewIntent(intent)

        setContentView(R.layout.main)

        tokenListAdapter = TokenListAdapter(this, tokenPersistence, settings)
        token_list.adapter = tokenListAdapter
        token_list.layoutManager = LinearLayoutManager(this)
        ItemTouchHelper(TokenTouchCallback(this, tokenListAdapter, tokenPersistence)).attachToRecyclerView(token_list)
        tokenListAdapter.registerAdapterDataObserver(tokenListObserver)

        setSupportActionBar(toolbar)

        search_view.setOnQueryTextListener(object: SearchView.OnQueryTextListener, androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query ?: ""
                refreshTokenList(searchQuery)
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                searchQuery = query ?: ""
                refreshTokenList(searchQuery)
                return true
            }

        })

        add_token_fab.setOnClickListener {
            startActivityForResult(Intent(this, ScanTokenActivity::class.java), SCAN_TOKEN_REQUEST_CODE)
        }

        // Don't permit screenshots since these might contain OTP codes.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        if (settings.requireAuthentication) {
            verifyAuthentication(onSuccess =  {
                refreshTokenList("")
            })
        } else {
            refreshTokenList("")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tokenListAdapter.unregisterAdapterDataObserver(tokenListObserver)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        this.menu = menu
        refreshOptionMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_scan -> {
                startActivityForResult(Intent(this, ScanTokenActivity::class.java), SCAN_TOKEN_REQUEST_CODE)
                return true
            }

            R.id.action_add -> {
                startActivityForResult(Intent(this, AddActivity::class.java), ADD_TOKEN_REQUEST_CODE)
                return true
            }

            R.id.action_import_json -> {
                performFileSearch(READ_JSON_REQUEST_CODE)
                return true
            }

            R.id.action_import_key_uri -> {
                performFileSearch(READ_KEY_URI_REQUEST_CODE)
                return true
            }

            R.id.action_export_json -> {
                createFile("application/json", "freeotp-backup.json", WRITE_JSON_REQUEST_CODE)
                return true
            }

            R.id.action_export_key_uri -> {
                createFile("text/plain", "freeotp-backup.txt", WRITE_KEY_URI_REQUEST_CODE)
                return true
            }

            R.id.use_dark_theme -> {
                settings.darkMode = !settings.darkMode
                if (settings.darkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                recreate()
                return true
            }

            R.id.copy_to_clipboard -> {
                settings.copyToClipboard = !settings.copyToClipboard
                item.isChecked = settings.copyToClipboard
                refreshOptionMenu()
            }

            R.id.require_authentication -> {
                // Make sure we also verify authentication before turning on the settings
                if (!settings.requireAuthentication) {
                    verifyAuthentication(onSuccess =  {
                        settings.requireAuthentication = true
                        refreshOptionMenu()
                    }, onFailure = {
                        Toast.makeText(applicationContext,
                                R.string.unable_to_authenticate, Toast.LENGTH_SHORT)
                                .show()
                    })
                } else {
                    settings.requireAuthentication = false
                    refreshOptionMenu()
                }
                return true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }

            R.id.quit_and_lock -> {
                finish()
                return true
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data
        if (uri != null) {
            uiLifecycleScope {
                try {
                    tokenPersistence.addFromUriString(uri.toString())
                } catch (e: Exception) {
                    Snackbar.make(root_view, R.string.invalid_token_uri_received, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (resultCode != Activity.RESULT_OK) {
            return
        }

        refreshTokenList(searchQuery)

        when (requestCode) {
            WRITE_JSON_REQUEST_CODE -> {
                uiLifecycleScope {
                    val uri = resultData?.data ?: return@uiLifecycleScope
                    importFromUtil.exportJsonFile(uri)
                    Snackbar.make(root_view, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }

            READ_JSON_REQUEST_CODE -> {
                val uri = resultData?.data ?: return
                MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.import_json_file)
                        .setMessage(R.string.import_json_file_warning)
                        .setIcon(R.drawable.alert)
                        .setPositiveButton(R.string.ok_text) { _: DialogInterface, _: Int ->
                            uiLifecycleScope {
                                try {
                                    importFromUtil.importJsonFile(uri)
                                    refreshTokenList(searchQuery)
                                    Snackbar.make(root_view, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                            .show()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Import JSON failed", e)
                                    Snackbar.make(root_view, R.string.import_json_failed_text, Snackbar.LENGTH_SHORT)
                                            .show()
                                }
                            }

                        }
                        .setNegativeButton(R.string.cancel_text, null)
                        .show()
            }

            WRITE_KEY_URI_REQUEST_CODE -> {
                uiLifecycleScope {
                    val uri = resultData?.data ?: return@uiLifecycleScope
                    importFromUtil.exportKeyUriFile(uri)
                    Snackbar.make(root_view, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }
            }

            READ_KEY_URI_REQUEST_CODE -> {
                uiLifecycleScope {
                    val uri = resultData?.data ?: return@uiLifecycleScope
                    try {
                        importFromUtil.importKeyUriFile(uri)
                        refreshTokenList(searchQuery)
                        Snackbar.make(root_view, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        Snackbar.make(root_view, R.string.import_key_uri_failed_text, Snackbar.LENGTH_SHORT)
                                .show()
                    }
                }
            }
        }

    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"

        startActivityForResult(intent, requestCode)
    }

    private fun createFile(mimeType: String, fileName: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Create a file with the requested MIME type.
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_TITLE, fileName)
        startActivityForResult(intent, requestCode)
    }

    private fun refreshTokenList(queryString: String) {
        uiLifecycleScope {
            val tokens = if (queryString.isEmpty()) {
                tokenPersistence.getTokens()
            } else {
                tokenPersistence.getTokens().filter { token ->
                    token.label.contains(queryString, true) || token.issuer.contains(queryString, true)
                }
            }

            tokenListAdapter.submitList(tokens)

            if (tokens.isEmpty()) {
                empty_view.visibility = View.VISIBLE
                token_list.visibility = View.GONE
            } else {
                empty_view.visibility = View.GONE
                token_list.visibility = View.VISIBLE
                tokens.forEach {
                    if (it.image != null) {
                        Picasso.get().load(it.image).fetch()
                    }
                }
            }
        }
    }

    private fun refreshOptionMenu() {
        this.menu?.findItem(R.id.use_dark_theme)?.isChecked = settings.darkMode
        this.menu?.findItem(R.id.copy_to_clipboard)?.isChecked = settings.copyToClipboard
        this.menu?.findItem(R.id.require_authentication)?.isChecked = settings.requireAuthentication
    }

    private fun verifyAuthentication(onSuccess: () -> Unit = {}, onFailure: (() -> Unit)? = null) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int,
                                                       errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (onFailure == null) {
                            Toast.makeText(applicationContext,
                                    "${getString(R.string.authentication_error)} $errString", Toast.LENGTH_SHORT)
                                    .show()

                            if (errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                                finish()
                            }
                        } else {
                            onFailure()
                        }
                    }

                    override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Toast.makeText(applicationContext,
                                getString(R.string.authentication_succeeded), Toast.LENGTH_SHORT)
                                .show()
                        onSuccess()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()

                        if (onFailure == null) {
                            Toast.makeText(applicationContext, getString(R.string.authentication_failed),
                                    Toast.LENGTH_SHORT)
                                    .show()
                            finish()
                        } else {
                            onFailure()
                        }
                    }
                })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.authentication_dialog_title))
                .setSubtitle(getString(R.string.authentication_dialog_subtitle))
                .setDeviceCredentialAllowed(true)
                .build()

        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
