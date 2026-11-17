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
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.databinding.MainBinding
import org.fedorahosted.freeotp.util.AuthenticationUtil
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
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var tokenListAdapter: TokenListAdapter

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainBinding
    private var searchQuery = ""
    private var menu: Menu? = null
    private var categories: List<String> = emptyList()
    private var confirmAddTokenDialog: AlertDialog? = null
    private var activeAuthDialog: AlertDialog? = null
    private var isAuthPromptInProgress = false
    private var pendingDeepLinkUri: Uri? = null
    private var pendingExportEncrypted = false

    private val tokenListObserver: AdapterDataObserver = object : AdapterDataObserver() {
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            super.onItemRangeInserted(positionStart, itemCount)
            binding.tokenList.scrollToPosition(positionStart)
        }
    }

    private val dateFormatter: DateFormat = SimpleDateFormat("yyyyMMdd_HHmm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            pendingExportEncrypted = savedInstanceState.getBoolean(STATE_PENDING_EXPORT_ENCRYPTED, false)
        }

        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.addTokenFab) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val marginParams = view.layoutParams as android.view.ViewGroup.MarginLayoutParams
            marginParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.bottom
            if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                marginParams.leftMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.left
            } else {
                marginParams.rightMargin = resources.getDimensionPixelSize(R.dimen.space_3) + insets.right
            }
            view.layoutParams = marginParams
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
                        } else {
                            processPendingDeepLinkIfAny()
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

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener, androidx.appcompat.widget.SearchView.OnQueryTextListener {
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

        if (savedInstanceState == null) {
            onNewIntent(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_EXPORT_ENCRYPTED, pendingExportEncrypted)
    }

    override fun onDestroy() {
        super.onDestroy()
        confirmAddTokenDialog?.dismiss()
        confirmAddTokenDialog = null
        activeAuthDialog?.dismiss()
        activeAuthDialog = null
        tokenListAdapter.unregisterAdapterDataObserver(tokenListObserver)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onSessionStart()
        refreshOptionMenu()
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
        menu.findItem(R.id.quit_and_lock)?.isVisible = settings.authMode != AuthMode.NONE

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
                handleExportAction(isJson = true)
                return true
            }

            R.id.action_export_key_uri -> {
                handleExportAction(isJson = false)
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

            R.id.authentication_mode -> {
                showAuthenticationModeDialog()
                return true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }

            R.id.quit_and_lock -> {
                viewModel.lockSession()
                finish()
                return true
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (packageName == intent.extras?.getString(SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA)) {
            Log.i(TAG, "Intent shared from the same package name. Ignoring the intent and do not add the token")
            return
        }

        val uri = intent.data ?: return
        intent.data = null

        if (!viewModel.isAuthenticated() || !tokenCryptoManager.isUnlocked()) {
            pendingDeepLinkUri = uri
            return
        }

        showAddTokenDeepLinkConfirmation(uri)
    }

    private fun processPendingDeepLinkIfAny() {
        if (!viewModel.isAuthenticated() || !tokenCryptoManager.isUnlocked()) {
            return
        }
        val uri = pendingDeepLinkUri ?: return
        pendingDeepLinkUri = null
        showAddTokenDeepLinkConfirmation(uri)
    }

    private fun showAddTokenDeepLinkConfirmation(uri: Uri) {
        val token = try {
            OtpTokenFactory.createFromUri(uri)
        } catch (e: Exception) {
            Snackbar.make(binding.rootView, R.string.invalid_token_uri_received, Snackbar.LENGTH_SHORT)
                .show()
            return
        }

        val tokenDetails = if (token.issuer.isNullOrBlank()) {
            "${getString(R.string.account)}: ${token.label}"
        } else {
            "${getString(R.string.issuer)}: ${token.issuer}\n${getString(R.string.account)}: ${token.label}"
        }

        confirmAddTokenDialog?.dismiss()
        confirmAddTokenDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_token)
            .setMessage(getString(R.string.add_token_confirmation_message, tokenDetails))
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.add) { _, _ ->
                lifecycleScope.launch {
                    otpTokenDatabase.otpTokenDao().insert(
                        tokenCryptoManager.encryptTokenForStorage(token)
                    )
                }
            }
            .setNegativeButton(R.string.cancel_text, null)
            .show()
    }

    private fun handleExportAction(isJson: Boolean) {
        AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
            if (settings.authMode == AuthMode.TOKEN_ENCRYPTED) {
                showExportEncryptionChoiceDialog(isJson)
            } else {
                pendingExportEncrypted = false
                launchExportCreateFile(isJson)
            }
        }
    }

    private fun showExportEncryptionChoiceDialog(isJson: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_encryption_choice_title)
            .setMessage(R.string.export_encryption_choice_message)
            .setPositiveButton(R.string.export_encrypted) { _, _ ->
                pendingExportEncrypted = true
                launchExportCreateFile(isJson)
            }
            .setNegativeButton(R.string.export_decrypted) { _, _ ->
                pendingExportEncrypted = false
                launchExportCreateFile(isJson)
            }
            .setNeutralButton(R.string.cancel_text, null)
            .show()
    }

    private fun launchExportCreateFile(isJson: Boolean) {
        val baseFileName = exportBaseFileName(encrypted = pendingExportEncrypted)
        if (isJson) {
            createFile("application/json", baseFileName, "json", WRITE_JSON_REQUEST_CODE)
        } else {
            createFile("text/plain", baseFileName, "txt", WRITE_KEY_URI_REQUEST_CODE)
        }
    }

    public override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, resultData)
        viewModel.keepSessionAliveForResult()

        if (resultCode != Activity.RESULT_OK) {
            return
        }

        when (requestCode) {
            WRITE_JSON_REQUEST_CODE -> {
                val encryptExport = settings.authMode == AuthMode.TOKEN_ENCRYPTED && pendingExportEncrypted
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportJsonFile(uri, encrypt = encryptExport)
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
                                if (importFromUtil.isJsonFileEncrypted(uri)) {
                                    showImportEncryptedBackupDialog(uri, isJson = true)
                                } else {
                                    importFromUtil.importJsonFile(uri)
                                    Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                        .show()
                                }
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
                val encryptExport = settings.authMode == AuthMode.TOKEN_ENCRYPTED && pendingExportEncrypted
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportKeyUriFile(uri, encrypt = encryptExport)
                    Snackbar.make(binding.rootView, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                        .show()
                }
            }

            READ_KEY_URI_REQUEST_CODE -> {
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    try {
                        if (importFromUtil.isKeyUriFileEncrypted(uri)) {
                            showImportEncryptedBackupDialog(uri, isJson = false)
                        } else {
                            importFromUtil.importKeyUriFile(uri)
                            Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                .show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        Snackbar.make(binding.rootView, R.string.import_key_uri_failed_text, Snackbar.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
    }

    private fun showImportEncryptedBackupDialog(uri: Uri, isJson: Boolean) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_key_prompt, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)

        messageView.setText(R.string.import_encrypted_backup_message)
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_encrypted_backup_title)
            .setView(dialogView)
            .setPositiveButton(R.string.import_text, null)
            .setNegativeButton(R.string.cancel_text, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val passphrase = keyEditText.text?.toString()?.toCharArray() ?: CharArray(0)
                if (passphrase.isEmpty()) {
                    keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    return@setOnClickListener
                }
                positiveButton.isEnabled = false
                lifecycleScope.launch {
                    try {
                        if (isJson) {
                            importFromUtil.importJsonFile(uri, passphrase)
                        } else {
                            importFromUtil.importKeyUriFile(uri, passphrase)
                        }
                        dialog.dismiss()
                        Snackbar.make(binding.rootView, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                    } catch (_: InvalidEncryptionKeyException) {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    } catch (e: Exception) {
                        Log.e(TAG, "Encrypted import failed", e)
                        dialog.dismiss()
                        val errRes = if (isJson) R.string.import_json_failed_text else R.string.import_key_uri_failed_text
                        Snackbar.make(binding.rootView, errRes, Snackbar.LENGTH_SHORT).show()
                    } finally {
                        passphrase.fill('\u0000')
                    }
                }
            }
        }
        dialog.show()
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
        intent.addCategory(Intent.CATEGORY_OPENABLE)
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
        this.menu?.findItem(R.id.quit_and_lock)?.isVisible = settings.authMode != AuthMode.NONE
    }

    private fun showAuthenticationModeDialog() {
        val modes = arrayOf(AuthMode.NONE, AuthMode.APP_LAUNCH, AuthMode.TOKEN_ENCRYPTED)
        val labels = arrayOf(
            getString(R.string.auth_mode_none),
            getString(R.string.auth_mode_app_launch),
            getString(R.string.auth_mode_token_encrypted)
        )
        val currentMode = settings.authMode
        val currentIndex = modes.indexOf(currentMode).coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.authentication_mode)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                val selectedMode = modes[which]
                if (selectedMode == currentMode) {
                    return@setSingleChoiceItems
                }
                handleAuthModeSelection(currentMode, selectedMode)
            }
            .setNegativeButton(R.string.cancel_text, null)
            .show()
    }

    private fun handleAuthModeSelection(currentMode: AuthMode, targetMode: AuthMode) {
        when {
            targetMode == AuthMode.TOKEN_ENCRYPTED -> {
                showSetEncryptionKeyDialog()
            }
            currentMode == AuthMode.TOKEN_ENCRYPTED -> {
                showDisableEncryptionWarningDialog(targetMode)
            }
            else -> {
                // Switching between NONE and APP_LAUNCH
                AuthenticationUtil.authenticateAppLaunch(this, finishOnCancel = false) {
                    settings.authMode = targetMode
                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                    refreshOptionMenu()
                }
            }
        }
    }

    private fun showSetEncryptionKeyDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_set_encryption_key, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)
        val confirmKeyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.confirm_key_input_layout)
        val confirmKeyEditText = dialogView.findViewById<TextInputEditText>(R.id.confirm_key_edit_text)

        messageView.text = getString(R.string.set_encryption_key_message, CryptoEngine.MIN_KEY_LENGTH)
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }
        confirmKeyEditText.doAfterTextChanged { confirmKeyInputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.set_encryption_key_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok_text, null)
            .setNegativeButton(R.string.cancel_text, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val key = keyEditText.text?.toString().orEmpty()
                val confirmKey = confirmKeyEditText.text?.toString().orEmpty()

                if (key.length < CryptoEngine.MIN_KEY_LENGTH) {
                    keyInputLayout.error = getString(R.string.encryption_key_too_short, CryptoEngine.MIN_KEY_LENGTH)
                    return@setOnClickListener
                }
                if (key != confirmKey) {
                    confirmKeyInputLayout.error = getString(R.string.encryption_keys_do_not_match)
                    return@setOnClickListener
                }

                positiveButton.isEnabled = false
                val passphraseChars = key.toCharArray()
                lifecycleScope.launch {
                    val setupMaterial = try {
                        tokenCryptoManager.deriveSetupMaterial(passphraseChars)
                    } finally {
                        passphraseChars.fill('\u0000')
                    }
                    completeTokenEncryptionSetup(setupMaterial, dialog)
                }
            }
        }
        dialog.show()
    }

    private suspend fun completeTokenEncryptionSetup(
        setupMaterial: TokenCryptoManager.SetupMaterial,
        setupDialog: AlertDialog
    ) {
        if (!AuthenticationUtil.canAuthenticateStrongBiometric(this)) {
            try {
                tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                setupDialog.dismiss()
                onAuthCompleted()
                refreshOptionMenu()
                Snackbar.make(binding.rootView, R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
                    .show()
            } finally {
                setupMaterial.clear()
            }
            return
        }

        val encryptCipher = try {
            tokenCryptoManager.createBiometricEncryptCipher()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize biometric encryption cipher, falling back to manual key", e)
            try {
                tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                setupDialog.dismiss()
                onAuthCompleted()
                refreshOptionMenu()
                Snackbar.make(binding.rootView, R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
                    .show()
            } finally {
                setupMaterial.clear()
            }
            return
        }

        setupDialog.dismiss()
        AuthenticationUtil.authenticateBiometricCrypto(
            activity = this,
            title = getString(R.string.encrypt_key_with_biometric_title),
            subtitle = getString(R.string.encrypt_key_with_biometric_subtitle),
            negativeButtonText = getString(R.string.use_manual_key_only),
            cipher = encryptCipher,
            onSuccess = { authenticatedCipher ->
                lifecycleScope.launch {
                    try {
                        val wrappedBlob = tokenCryptoManager.wrapKeyWithBiometricCipher(
                            authenticatedCipher,
                            setupMaterial.keyBytes
                        )
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, wrappedBlob)
                        onAuthCompleted()
                        refreshOptionMenu()
                        Snackbar.make(binding.rootView, R.string.token_encryption_enabled, Snackbar.LENGTH_SHORT)
                            .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Biometric key wrapping failed, falling back to manual key", e)
                        tokenCryptoManager.cancelPendingBiometricKeySetup()
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                        onAuthCompleted()
                        refreshOptionMenu()
                        Snackbar.make(binding.rootView, R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
                            .show()
                    } finally {
                        setupMaterial.clear()
                    }
                }
            },
            onFallbackToManual = { _ ->
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                lifecycleScope.launch {
                    try {
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                        onAuthCompleted()
                        refreshOptionMenu()
                        Snackbar.make(binding.rootView, R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
                            .show()
                    } finally {
                        setupMaterial.clear()
                    }
                }
            },
            onCancel = {
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                setupMaterial.clear()
            }
        )
    }

    private fun showDisableEncryptionWarningDialog(targetMode: AuthMode) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disable_token_encryption_title)
            .setMessage(R.string.disable_token_encryption_warning)
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.ok_text) { _, _ ->
                authenticateAndDisableEncryption(targetMode)
            }
            .setNegativeButton(R.string.cancel_text, null)
            .show()
    }

    private fun authenticateAndDisableEncryption(targetMode: AuthMode) {
        val canBio = AuthenticationUtil.canAuthenticateStrongBiometric(this)
        val prep = if (canBio) tokenCryptoManager.prepareBiometricDecryptCipher() else null
        val decryptCipher = prep?.cipher

        if (canBio && prep?.status == TokenCryptoManager.BiometricKeyStatus.READY && decryptCipher != null) {
            AuthenticationUtil.authenticateBiometricCrypto(
                activity = this,
                title = getString(R.string.authentication_dialog_title),
                subtitle = getString(R.string.unlock_tokens_biometric_subtitle),
                negativeButtonText = getString(R.string.enter_key_manually),
                cipher = decryptCipher,
                onSuccess = { authenticatedCipher ->
                    if (tokenCryptoManager.unlockWithBiometricCipher(authenticatedCipher)) {
                        performDisableEncryption(targetMode)
                    } else {
                        showVerifyKeyToDisableDialog(targetMode)
                    }
                },
                onFallbackToManual = { _ ->
                    showVerifyKeyToDisableDialog(targetMode)
                },
                onCancel = {}
            )
        } else {
            showVerifyKeyToDisableDialog(targetMode)
        }
    }

    private fun showVerifyKeyToDisableDialog(targetMode: AuthMode) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_key_prompt, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)

        messageView.setText(R.string.verify_encryption_key_message)
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.verify_encryption_key_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok_text, null)
            .setNegativeButton(R.string.cancel_text, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val chars = keyEditText.text?.toString()?.toCharArray() ?: CharArray(0)
                if (chars.isEmpty()) {
                    keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    return@setOnClickListener
                }
                positiveButton.isEnabled = false
                lifecycleScope.launch {
                    val unlocked = try {
                        tokenCryptoManager.unlockWithPassphrase(chars)
                    } finally {
                        chars.fill('\u0000')
                    }
                    if (unlocked) {
                        tokenCryptoManager.disableTokenEncryption(targetMode)
                        dialog.dismiss()
                        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                        refreshOptionMenu()
                        Snackbar.make(binding.rootView, R.string.token_encryption_disabled, Snackbar.LENGTH_SHORT)
                            .show()
                    } else {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun performDisableEncryption(targetMode: AuthMode) {
        lifecycleScope.launch {
            tokenCryptoManager.disableTokenEncryption(targetMode)
            viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            refreshOptionMenu()
            Snackbar.make(binding.rootView, R.string.token_encryption_disabled, Snackbar.LENGTH_SHORT)
                .show()
        }
    }

    private fun verifyAuthentication() {
        if (isAuthPromptInProgress || activeAuthDialog?.isShowing == true) {
            return
        }

        when (settings.authMode) {
            AuthMode.NONE -> {
                onAuthCompleted()
            }
            AuthMode.APP_LAUNCH -> {
                isAuthPromptInProgress = true
                AuthenticationUtil.authenticateAppLaunch(this, finishOnCancel = true) {
                    isAuthPromptInProgress = false
                    onAuthCompleted()
                }
            }
            AuthMode.TOKEN_ENCRYPTED -> {
                verifyTokenEncryptedLaunch()
            }
        }
    }

    private fun verifyTokenEncryptedLaunch() {
        val canBio = AuthenticationUtil.canAuthenticateStrongBiometric(this)
        val prep = tokenCryptoManager.prepareBiometricDecryptCipher()
        val decryptCipher = prep.cipher

        if (canBio && prep.status == TokenCryptoManager.BiometricKeyStatus.READY && decryptCipher != null) {
            isAuthPromptInProgress = true
            AuthenticationUtil.authenticateBiometricCrypto(
                activity = this,
                title = getString(R.string.unlock_tokens_title),
                subtitle = getString(R.string.unlock_tokens_biometric_subtitle),
                negativeButtonText = getString(R.string.enter_key_manually),
                cipher = decryptCipher,
                onSuccess = { authenticatedCipher ->
                    isAuthPromptInProgress = false
                    if (tokenCryptoManager.unlockWithBiometricCipher(authenticatedCipher)) {
                        onAuthCompleted()
                    } else {
                        showManualKeyUnlockDialog(isLaunchGate = true, biometricInvalidated = true)
                    }
                },
                onFallbackToManual = { _ ->
                    isAuthPromptInProgress = false
                    showManualKeyUnlockDialog(isLaunchGate = true, biometricInvalidated = false)
                },
                onCancel = {
                    isAuthPromptInProgress = false
                    finish()
                }
            )
        } else {
            val wasInvalidated = prep.status == TokenCryptoManager.BiometricKeyStatus.INVALIDATED ||
                tokenCryptoManager.wasBiometricInvalidated()
            showManualKeyUnlockDialog(isLaunchGate = true, biometricInvalidated = wasInvalidated)
        }
    }

    private fun showManualKeyUnlockDialog(
        isLaunchGate: Boolean,
        biometricInvalidated: Boolean
    ) {
        activeAuthDialog?.dismiss()

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_key_prompt, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)
        val enableBiometricCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.enable_biometric_checkbox)

        messageView.setText(
            if (biometricInvalidated) {
                R.string.biometric_invalidated_enter_key_message
            } else {
                R.string.enter_encryption_key_to_unlock_message
            }
        )
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }

        val canOfferBiometric = (!tokenCryptoManager.isBiometricKeyConfigured() || biometricInvalidated) &&
            AuthenticationUtil.canAuthenticateStrongBiometric(this)
        if (canOfferBiometric) {
            enableBiometricCheckbox.visibility = View.VISIBLE
            enableBiometricCheckbox.isChecked = true
            enableBiometricCheckbox.setText(
                if (biometricInvalidated) {
                    R.string.reenable_biometric_unlock
                } else {
                    R.string.enable_biometric_unlock
                }
            )
        } else {
            enableBiometricCheckbox.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unlock_tokens_title)
            .setView(dialogView)
            .setCancelable(!isLaunchGate)
            .setPositiveButton(R.string.unlock_text, null)
            .setNegativeButton(R.string.cancel_text) { _, _ ->
                activeAuthDialog = null
                if (isLaunchGate) {
                    finish()
                }
            }
            .setOnCancelListener {
                activeAuthDialog = null
                if (isLaunchGate) {
                    finish()
                }
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val chars = keyEditText.text?.toString()?.toCharArray() ?: CharArray(0)
                if (chars.isEmpty()) {
                    keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    return@setOnClickListener
                }
                positiveButton.isEnabled = false
                lifecycleScope.launch {
                    val unlocked = try {
                        tokenCryptoManager.unlockWithPassphrase(chars)
                    } finally {
                        chars.fill('\u0000')
                    }
                    if (!unlocked) {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                        return@launch
                    }

                    val shouldEnableBiometric = canOfferBiometric && enableBiometricCheckbox.isChecked
                    dialog.dismiss()
                    activeAuthDialog = null

                    if (shouldEnableBiometric) {
                        promptReenableBiometricAfterManualUnlock()
                    } else {
                        onAuthCompleted()
                    }
                }
            }
        }

        activeAuthDialog = dialog
        dialog.show()
    }

    private fun promptReenableBiometricAfterManualUnlock() {
        val encryptCipher = try {
            tokenCryptoManager.createBiometricEncryptCipher()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to create biometric cipher during re-enable", e)
            tokenCryptoManager.cancelPendingBiometricKeySetup()
            onAuthCompleted()
            return
        }

        isAuthPromptInProgress = true
        AuthenticationUtil.authenticateBiometricCrypto(
            activity = this,
            title = getString(R.string.encrypt_key_with_biometric_title),
            subtitle = getString(R.string.encrypt_key_with_biometric_subtitle),
            negativeButtonText = getString(R.string.cancel_text),
            cipher = encryptCipher,
            onSuccess = { authenticatedCipher ->
                isAuthPromptInProgress = false
                try {
                    tokenCryptoManager.saveBiometricEncryptedCurrentKey(authenticatedCipher)
                    Snackbar.make(binding.rootView, R.string.biometric_unlock_enabled, Snackbar.LENGTH_SHORT)
                        .show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed saving re-enabled biometric key", e)
                    tokenCryptoManager.cancelPendingBiometricKeySetup()
                }
                onAuthCompleted()
            },
            onFallbackToManual = { _ ->
                isAuthPromptInProgress = false
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                onAuthCompleted()
            },
            onCancel = {
                isAuthPromptInProgress = false
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                onAuthCompleted()
            }
        )
    }

    private fun onAuthCompleted() {
        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
        processPendingDeepLinkIfAny()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val READ_JSON_REQUEST_CODE = 42
        const val WRITE_JSON_REQUEST_CODE = 43
        const val READ_KEY_URI_REQUEST_CODE = 44
        const val WRITE_KEY_URI_REQUEST_CODE = 45
        const val SCREENSHOT_MODE_EXTRA = "screenshot_mode"
        const val SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA = "shareFromPackageName"
        private const val STATE_PENDING_EXPORT_ENCRYPTED = "pending_export_encrypted"

        internal fun exportBaseFileName(encrypted: Boolean): String {
            return if (encrypted) "freeotp-backup-encrypted" else "freeotp-backup"
        }
    }
}
