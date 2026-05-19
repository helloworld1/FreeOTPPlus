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
import android.content.ActivityNotFoundException
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.databinding.MainBinding
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.util.Settings
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.max

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var tokenMigrationUtil: MigrationUtil
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenListAdapter: TokenListAdapter

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainBinding
    private var searchQuery = ""
    private var menu: Menu? = null
    private var lastSessionEndTimestamp = 0L;
    private var categories: List<String> = emptyList()

    // Tracks whether binding is fully initialized so onNewIntent
    // can safely reference binding.rootView without NPE
    private var isBindingReady = false

    private val tokenListObserver: AdapterDataObserver = object: AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            binding.tokenList.scrollToPosition(positionStart)
        }
    }

    private val dateFormatter : DateFormat = SimpleDateFormat("yyyyMMdd_HHmm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // NOTE: onNewIntent(intent) is intentionally NOT called here before binding
        // is initialized. It is instead called after binding is ready, at the
        // bottom of onCreate, so that Snackbar references to binding.rootView
        // are safe.

        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        isBindingReady = true

        ViewCompat.setOnApplyWindowInsetsListener(binding.tokenList) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                resources.getDimensionPixelSize(R.dimen.token_list_bottom_padding) + insets.bottom
            )
            windowInsets
        }

        viewModel.migrateOldData()

        binding.tokenList.adapter = tokenListAdapter

        // Used GridlayoutManager to support tablet mode for multiple columns
        // Make sure one column has at least 320 DP
        val columns = max(1, resources.configuration.screenWidthDp / 320)
        binding.tokenList.layoutManager = GridLayoutManager(this, columns)

        ItemTouchHelper(TokenTouchCallback(this, tokenListAdapter, otpTokenDatabase))
            .attachToRecyclerView(binding.tokenList)
        tokenListAdapter.registerAdapterDataObserver(tokenListObserver)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.getTokenList().collect { tokens ->
                        tokenListAdapter.submitList(tokens)

                        if (tokens.isEmpty()) {
                            binding.emptyView.visibility = View.VISIBLE
                            binding.tokenList.visibility = View.GONE
                        } else {
                            binding.emptyView.visibility = View.GONE
                            binding.tokenList.visibility = View.VISIBLE
                        }
                    }
                }

                launch {
                    viewModel.getAuthState().collect { authState ->
                        if (authState == MainViewModel.AuthState.UNAUTHENTICATED) {
                            verifyAuthentication()
                        }
                    }
                }

                launch {
                    viewModel.getAllCategories().collect {
                        categories = it
                        invalidateOptionsMenu()
                    }
                }
            }
        }

        setSupportActionBar(binding.toolbar)

        binding.searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener, androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setTokenSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                searchQuery = query ?: ""
                viewModel.setTokenSearchQuery(query ?: "")
                return true
            }
        })

        binding.addTokenFab.setOnClickListener {
            startActivity(Intent(this, ScanTokenActivity::class.java))
        }

        // Don't permit screenshots since these might contain OTP codes unless explicitly
        // launched with screenshot mode
        if (intent.extras?.getBoolean(SCREENSHOT_MODE_EXTRA) != true) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        // Now that binding is fully ready, handle the launch intent safely
        handleOtpAuthIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        tokenListAdapter.unregisterAdapterDataObserver(tokenListObserver)
        lastSessionEndTimestamp = 0L
        isBindingReady = false
    }

    override fun onStart() {
        super.onStart()
        viewModel.onSessionStart()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onSessionStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        this.menu = menu
        refreshOptionMenu()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val filterItem = menu.findItem(R.id.action_filter)
        val filterMenu = filterItem?.subMenu ?: return super.onPrepareOptionsMenu(menu)
        filterMenu.clear()

        filterMenu.add(Menu.NONE, R.id.action_filter_all, Menu.NONE, R.string.all_categories)
        filterMenu.add(Menu.NONE, R.id.action_filter_uncategorized, Menu.NONE, R.string.uncategorized)

        for (category in categories) {
            filterMenu.add(Menu.NONE, Menu.NONE, Menu.NONE, category).setOnMenuItemClickListener {
                viewModel.setCategoryFilter(category)
                true
            }
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan -> {
                startActivity(Intent(this, ScanTokenActivity::class.java))
                return true
            }

            R.id.action_add -> {
                startActivity(Intent(this, AddActivity::class.java))
                return true
            }

            R.id.action_filter_all -> {
                viewModel.setCategoryFilter(null)
                return true
            }

            R.id.action_filter_uncategorized -> {
                viewModel.setCategoryFilter(MainViewModel.CATEGORY_UNCATEGORIZED)
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
                createFile("application/json", "freeotp-backup", "json", WRITE_JSON_REQUEST_CODE)
                return true
            }

            R.id.action_export_key_uri -> {
                createFile("text/plain", "freeotp-backup", "txt", WRITE_KEY_URI_REQUEST_CODE)
                return true
            }

            R.id.use_dark_theme -> {
                settings.darkMode = !settings.darkMode
                if (settings.darkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
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
                // Make sure we also verify authentication before turning on or off the settings
                if (!settings.requireAuthentication) {
                    verifyAuthentication(isEnabling = true)
                } else {
                    verifyAuthentication(isDisabling = true)
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

    /**
     * SECURITY FIX: onNewIntent no longer directly inserts tokens.
     * All otpauth:// intent handling is routed through handleOtpAuthIntent()
     * which requires explicit user confirmation before saving any token.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOtpAuthIntent(intent)
    }

    /**
     * Handles an incoming otpauth:// intent safely.
     *
     * Security properties:
     * - Rejects intents originating from the same package (internal share flows).
     * - Only processes URIs with scheme == "otpauth". All other schemes are ignored.
     * - Requires explicit user confirmation via a dialog before inserting any token,
     *   preventing silent injection by malicious apps or phishing webpages.
     * - Displays the parsed issuer and account to the user so they can evaluate
     *   whether the token is from a trusted source before accepting.
     * - Guarded by isBindingReady so this is safe to call from both onCreate
     *   (after binding is inflated) and onNewIntent.
     */
    private fun handleOtpAuthIntent(intent: Intent) {
        // Reject intents explicitly shared from within the same package
        if (packageName == intent.extras?.getString(SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA)) {
            Log.i(TAG, "Intent shared from the same package name. Ignoring.")
            return
        }

        val uri = intent.data ?: return

        // Only handle otpauth:// URIs; ignore all other schemes
        if (uri.scheme != "otpauth") return

        // Guard: binding must be ready before we reference binding.rootView
        if (!isBindingReady) {
            Log.w(TAG, "handleOtpAuthIntent called before binding was ready. Ignoring intent.")
            return
        }

        // Parse display fields from URI for the confirmation dialog.
        // URI format: otpauth://totp/<issuer>:<account>?secret=...&issuer=...
        val rawPath = uri.path?.trimStart('/') ?: ""
        val issuer = uri.getQueryParameter("issuer")
            ?: rawPath.substringBefore(":").ifEmpty { getString(R.string.unknown_issuer) }
        val account = rawPath.substringAfter(":", "").ifEmpty { getString(R.string.unknown_account) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_token_confirmation_title)
            .setMessage(
                getString(R.string.add_token_confirmation_message, issuer, account)
            )
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.ok_text) { _, _ ->
                lifecycleScope.launch {
                    try {
                        otpTokenDatabase.otpTokenDao().insert(OtpTokenFactory.createFromUri(uri))
                        Snackbar.make(
                            binding.rootView,
                            R.string.token_added_successfully,
                            Snackbar.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add token from external intent", e)
                        Snackbar.make(
                            binding.rootView,
                            R.string.invalid_token_uri_received,
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel_text, null)
            .show()
    }

    public override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)

        if (resultCode != Activity.RESULT_OK) {
            return
        }

        when (requestCode) {
            WRITE_JSON_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportJsonFile(uri)
                    Snackbar.make(binding.rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
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
                        lifecycleScope.launch {
                            try {
                                importFromUtil.importJsonFile(uri)
                                Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                    .show()
                            } catch (e: Exception) {
                                Log.e(TAG, "Import JSON failed", e)
                                Snackbar.make(binding.root, R.string.import_json_failed_text, Snackbar.LENGTH_SHORT)
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel_text, null)
                    .show()
            }

            WRITE_KEY_URI_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportKeyUriFile(uri)
                    Snackbar.make(binding.rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                        .show()
                }
            }

            READ_KEY_URI_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    try {
                        importFromUtil.importKeyUriFile(uri)
                        Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        Snackbar.make(binding.rootView, R.string.import_key_uri_failed_text, Snackbar.LENGTH_SHORT)
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

        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(
                applicationContext,
                getString(R.string.launch_file_browser_failure),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun createFile(
        mimeType: String,
        fileName: String,
        fileExtension: String,
        requestCode: Int,
        appendTimestamp: Boolean = true
    ) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Create a file with the requested MIME type.
        intent.type = mimeType
        intent.putExtra(
            Intent.EXTRA_TITLE,
            "$fileName${if (appendTimestamp) "_${dateFormatter.format(Date())}" else ""}.$fileExtension"
        )

        try {
            startActivityForResult(intent, requestCode)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(
                applicationContext,
                getString(R.string.launch_file_browser_failure),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refreshOptionMenu() {
        this.menu?.findItem(R.id.use_dark_theme)?.isChecked = settings.darkMode
        this.menu?.findItem(R.id.copy_to_clipboard)?.isChecked = settings.copyToClipboard
        this.menu?.findItem(R.id.require_authentication)?.isChecked = settings.requireAuthentication
    }

    private fun verifyAuthentication(isEnabling: Boolean = false, isDisabling: Boolean = false) {
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
                        ).show()
                    }

                    if (!isEnabling && !isDisabling && errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        finish()
                    }
                }

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)

                    if (isEnabling) {
                        settings.requireAuthentication = true
                        refreshOptionMenu()
                    } else if (isDisabling) {
                        settings.requireAuthentication = false
                        refreshOptionMenu()
                    }
                }

                override fun onAuthenticationFailed() {
                    // Invalid authentication, e.g. wrong fingerprint. Android auth UI shows an
                    // error, so no need for FreeOTP to show one
                    super.onAuthenticationFailed()

                    Toast.makeText(
                        applicationContext,
                        R.string.unable_to_authenticate,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.authentication_dialog_title))
            .setSubtitle(getString(R.string.authentication_dialog_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val READ_JSON_REQUEST_CODE = 42
        const val WRITE_JSON_REQUEST_CODE = 43
        const val READ_KEY_URI_REQUEST_CODE = 44
        const val WRITE_KEY_URI_REQUEST_CODE = 45
        const val SCREENSHOT_MODE_EXTRA = "screenshot_mode"
        const val SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA = "shareFromPackageName"
    }
}