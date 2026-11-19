package org.fedorahosted.freeotp.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
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
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.Settings
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private data class PendingActivityResult(
        val requestCode: Int,
        val resultCode: Int,
        val resultData: Intent?
    )

    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var tokenMigrationUtil: MigrationUtil
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var appSessionManager: AppSessionManager

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainBinding

    private var confirmAddTokenDialog: AlertDialog? = null
    private var activeAuthDialog: AlertDialog? = null
    private var isAuthPromptInProgress = false
    private var pendingDeepLinkUri: Uri? = null
    private var pendingActivityResult: PendingActivityResult? = null
    private var pendingExportEncrypted = false

    private val dateFormatter: DateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleDocumentPickerUri(READ_JSON_REQUEST_CODE, uri)
    }

    private val openKeyUriLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        handleDocumentPickerUri(READ_KEY_URI_REQUEST_CODE, uri)
    }

    private val createJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        handleDocumentPickerUri(WRITE_JSON_REQUEST_CODE, uri)
    }

    private val createKeyUriLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        handleDocumentPickerUri(WRITE_KEY_URI_REQUEST_CODE, uri)
    }

    private fun navController(): NavController {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        return navHost.navController
    }

    fun navigateTo(resId: Int, args: Bundle? = null) {
        navController().navigate(resId, args)
    }

    fun navigateBack(): Boolean {
        return navController().popBackStack()
    }

    fun currentDestinationId(): Int? {
        return navController().currentDestination?.id
    }

    fun getOpenDialogWindows(): List<Window> {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return emptyList()
        return navHost.childFragmentManager.fragments
            .filterIsInstance<DialogFragment>()
            .mapNotNull { it.dialog?.window }
    }

    fun showSnackbar(messageRes: Int, duration: Int = Snackbar.LENGTH_SHORT) {
        Snackbar.make(binding.rootView, messageRes, duration).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState != null) {
            pendingExportEncrypted = savedInstanceState.getBoolean(STATE_PENDING_EXPORT_ENCRYPTED, false)
            @Suppress("DEPRECATION")
            pendingDeepLinkUri = savedInstanceState.getParcelable(STATE_PENDING_DEEP_LINK_URI)
        }

        binding = MainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setImportantForAutofill(
            window.decorView,
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        )

        viewModel.migrateOldData()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getAuthState().collect { authState ->
                    if (authState == MainViewModel.AuthState.UNAUTHENTICATED) {
                        binding.navHostFragment.visibility = View.INVISIBLE
                        confirmAddTokenDialog?.dismiss()
                        confirmAddTokenDialog = null
                        dismissOpenChildScreensOnLock()
                        verifyAuthentication()
                    } else {
                        binding.navHostFragment.visibility = View.VISIBLE
                        invalidateMenu()
                        processPendingDeepLinkIfAny()
                        processPendingActivityResultIfAny()
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            onNewIntent(intent)
        }
    }

    private fun dismissOpenChildScreensOnLock() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
            ?: return
        for (fragment in navHost.childFragmentManager.fragments.toList()) {
            if (fragment is DialogFragment) {
                fragment.dismissAllowingStateLoss()
            }
        }
        val controller = navHost.navController
        if (controller.currentDestination?.id != R.id.tokenListFragment) {
            controller.popBackStack(R.id.tokenListFragment, false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_EXPORT_ENCRYPTED, pendingExportEncrypted)
        outState.putParcelable(STATE_PENDING_DEEP_LINK_URI, pendingDeepLinkUri)
    }

    override fun onDestroy() {
        super.onDestroy()
        confirmAddTokenDialog?.dismiss()
        confirmAddTokenDialog = null
        activeAuthDialog?.dismiss()
        activeAuthDialog = null
    }

    override fun onStart() {
        super.onStart()
        viewModel.onSessionStart()
        invalidateMenu()
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
        } catch (_: Exception) {
            showSnackbar(R.string.invalid_token_uri_received)
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

    internal fun startImportJson() {
        try {
            openJsonLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext, getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun startImportKeyUri() {
        try {
            openKeyUriLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext, getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun handleExportAction(isJson: Boolean) {
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
        val timestamp = dateFormatter.format(Date())
        try {
            if (isJson) {
                createJsonLauncher.launch("${baseFileName}_$timestamp.json")
            } else {
                createKeyUriLauncher.launch("${baseFileName}_$timestamp.txt")
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext, getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleDocumentPickerUri(requestCode: Int, uri: Uri?) {
        if (uri == null) {
            appSessionManager.refreshSessionState()
            return
        }
        val data = Intent().setData(uri)
        if (!appSessionManager.refreshSessionState()) {
            pendingActivityResult = PendingActivityResult(requestCode, Activity.RESULT_OK, data)
            return
        }
        handleAuthenticatedActivityResult(requestCode, data)
    }

    @Deprecated("Deprecated in Java")
    public override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        resultData: Intent?
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, resultData)

        if (requestCode != WRITE_JSON_REQUEST_CODE &&
            requestCode != READ_JSON_REQUEST_CODE &&
            requestCode != WRITE_KEY_URI_REQUEST_CODE &&
            requestCode != READ_KEY_URI_REQUEST_CODE
        ) {
            appSessionManager.refreshSessionState()
            return
        }

        if (resultCode != Activity.RESULT_OK) {
            appSessionManager.refreshSessionState()
            return
        }

        if (!appSessionManager.refreshSessionState()) {
            pendingActivityResult = PendingActivityResult(requestCode, resultCode, resultData)
            return
        }

        handleAuthenticatedActivityResult(requestCode, resultData)
    }

    private fun processPendingActivityResultIfAny() {
        if (!viewModel.isAuthenticated() || !tokenCryptoManager.isUnlocked()) {
            return
        }
        val pending = pendingActivityResult ?: return
        pendingActivityResult = null
        if (pending.resultCode == Activity.RESULT_OK) {
            handleAuthenticatedActivityResult(pending.requestCode, pending.resultData)
        }
    }

    private fun handleAuthenticatedActivityResult(
        requestCode: Int,
        resultData: Intent?
    ) {
        when (requestCode) {
            WRITE_JSON_REQUEST_CODE -> {
                val encryptExport = settings.authMode == AuthMode.TOKEN_ENCRYPTED && pendingExportEncrypted
                lifecycleScope.launch {
                    val uri = resultData?.data ?: return@launch
                    try {
                        importFromUtil.exportJsonFile(uri, encrypt = encryptExport)
                        showSnackbar(R.string.export_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Export JSON failed", e)
                    }
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
                                    showSnackbar(R.string.import_succeeded_text)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Import JSON failed", e)
                                showSnackbar(R.string.import_json_failed_text)
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
                    try {
                        importFromUtil.exportKeyUriFile(uri, encrypt = encryptExport)
                        showSnackbar(R.string.export_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Export Key URI failed", e)
                    }
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
                            showSnackbar(R.string.import_succeeded_text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        showSnackbar(R.string.import_key_uri_failed_text)
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
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
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
                        AuthenticationUtil.dismissKeyDialogSafely(dialog, keyEditText)
                        showSnackbar(R.string.import_succeeded_text)
                    } catch (_: InvalidEncryptionKeyException) {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    } catch (e: Exception) {
                        Log.e(TAG, "Encrypted import failed", e)
                        AuthenticationUtil.dismissKeyDialogSafely(dialog, keyEditText)
                        val errRes = if (isJson) R.string.import_json_failed_text else R.string.import_key_uri_failed_text
                        showSnackbar(errRes)
                    } finally {
                        passphrase.fill('\u0000')
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(this, keyEditText)
        }
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
        dialog.show()
    }

    internal fun showAuthenticationModeDialog() {
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
                AuthenticationUtil.authenticateAppLaunch(this, finishOnCancel = false) {
                    settings.authMode = targetMode
                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                    invalidateMenu()
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
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
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
                AuthenticationUtil.clearAndCancelAutofill(this, keyEditText, confirmKeyEditText)
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
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(this, keyEditText, confirmKeyEditText)
        }
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
        dialog.show()
    }

    private suspend fun completeTokenEncryptionSetup(
        setupMaterial: TokenCryptoManager.SetupMaterial,
        setupDialog: AlertDialog
    ) {
        if (!AuthenticationUtil.canAuthenticateStrongBiometric(this)) {
            try {
                tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                AuthenticationUtil.dismissKeyDialogSafely(setupDialog)
                onAuthCompleted()
                invalidateMenu()
                showSnackbar(R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
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
                AuthenticationUtil.dismissKeyDialogSafely(setupDialog)
                onAuthCompleted()
                invalidateMenu()
                showSnackbar(R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
            } finally {
                setupMaterial.clear()
            }
            return
        }

        AuthenticationUtil.dismissKeyDialogSafely(setupDialog)
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
                        invalidateMenu()
                        showSnackbar(R.string.token_encryption_enabled)
                    } catch (e: Exception) {
                        Log.e(TAG, "Biometric key wrapping failed, falling back to manual key", e)
                        tokenCryptoManager.cancelPendingBiometricKeySetup()
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
                        onAuthCompleted()
                        invalidateMenu()
                        showSnackbar(R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
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
                        invalidateMenu()
                        showSnackbar(R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
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
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
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
                        AuthenticationUtil.dismissKeyDialogSafely(dialog, keyEditText)
                        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                        invalidateMenu()
                        showSnackbar(R.string.token_encryption_disabled)
                    } else {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = getString(R.string.incorrect_encryption_key)
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(this, keyEditText)
        }
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
        dialog.show()
    }

    private fun performDisableEncryption(targetMode: AuthMode) {
        lifecycleScope.launch {
            tokenCryptoManager.disableTokenEncryption(targetMode)
            viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
            invalidateMenu()
            showSnackbar(R.string.token_encryption_disabled)
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
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
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
                    AuthenticationUtil.dismissKeyDialogSafely(dialog, keyEditText)
                    activeAuthDialog = null

                    if (shouldEnableBiometric) {
                        promptReenableBiometricAfterManualUnlock()
                    } else {
                        onAuthCompleted()
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(this, keyEditText)
        }

        activeAuthDialog = dialog
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
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
                    showSnackbar(R.string.biometric_unlock_enabled)
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
        processPendingActivityResultIfAny()
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
        private const val STATE_PENDING_DEEP_LINK_URI = "pending_deep_link_uri"

        internal fun exportBaseFileName(encrypted: Boolean): String {
            return if (encrypted) "freeotp-backup-encrypted" else "freeotp-backup"
        }
    }
}
