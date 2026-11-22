package org.fedorahosted.freeotp.ui

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.biometric.BiometricPrompt
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.TokenSortOrder
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.databinding.MainBinding
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.fedorahosted.freeotp.util.Settings
import java.security.GeneralSecurityException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    internal enum class DocumentAction {
        IMPORT_JSON,
        IMPORT_KEY_URI,
        IMPORT_IMAGES,
        EXPORT_JSON,
        EXPORT_KEY_URI,
        EXPORT_IMAGES
    }

    private data class PendingDocumentResult(
        val action: DocumentAction,
        val uri: Uri
    )

    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var appSessionManager: AppSessionManager
    @Inject lateinit var clipboardUtil: ClipboardUtil

    private val viewModel: MainViewModel by viewModels()
    private lateinit var binding: MainBinding

    private enum class AuthDialogKind {
        NONE,
        MANUAL_KEY,
        APP_LAUNCH_RETRY,
        APP_LAUNCH_NO_SCREEN_LOCK
    }

    private var activeTransientDialog: AlertDialog? = null
    private var activeAuthDialog: AlertDialog? = null
    private var activeAuthDialogKind: AuthDialogKind = AuthDialogKind.NONE
    private var isAuthPromptInProgress = false
    private var pendingAppLaunchRetryDialog = false
    private var pendingAppLaunchRetryMessage: String? = null
    private var pendingDeepLinkUri: Uri? = null
    private var pendingDocumentResult: PendingDocumentResult? = null
    private var pendingExportEncrypted = false

    private val dateFormatter: DateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    private val authModePrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TokenCryptoManager.AUTH_MODE_KEY) {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    appSessionManager.refreshSessionState()
                    invalidateMenu()
                }
            }
        }
    }

    private val openJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onDocumentPicked(DocumentAction.IMPORT_JSON, uri)
    }

    private val openKeyUriLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onDocumentPicked(DocumentAction.IMPORT_KEY_URI, uri)
    }

    private val createJsonLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        onDocumentPicked(DocumentAction.EXPORT_JSON, uri)
    }

    private val createKeyUriLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        onDocumentPicked(DocumentAction.EXPORT_KEY_URI, uri)
    }

    private val openImagesZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onDocumentPicked(DocumentAction.IMPORT_IMAGES, uri)
    }

    private val createImagesZipLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        onDocumentPicked(DocumentAction.EXPORT_IMAGES, uri)
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
        val fragmentDialogWindows = navHost?.childFragmentManager?.fragments
            ?.filterIsInstance<DialogFragment>()
            ?.mapNotNull { it.dialog?.window }
            .orEmpty()
        val activityDialogWindows = listOfNotNull(
            activeTransientDialog?.takeIf { it.isShowing }?.window,
            activeAuthDialog?.takeIf { it.isShowing }?.window
        )
        return fragmentDialogWindows + activityDialogWindows
    }

    internal fun isSessionAuthenticated(): Boolean {
        return viewModel.isAuthenticated() && tokenCryptoManager.isUnlocked()
    }

    internal fun showTrackedDialog(dialog: AlertDialog) {
        if (activeTransientDialog !== dialog) {
            activeTransientDialog?.dismiss()
        }
        activeTransientDialog = dialog
        AuthenticationUtil.applySecureFlag(this, dialog)
        dialog.show()
        AuthenticationUtil.applySecureFlag(this, dialog)
    }

    private fun dismissTransientDialog() {
        activeTransientDialog?.dismiss()
        activeTransientDialog = null
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
                        dismissTransientDialog()
                        dismissOpenChildScreensOnLock()
                        if (!isFinishing && !isDestroyed) {
                            verifyAuthentication()
                        }
                    } else {
                        pendingAppLaunchRetryDialog = false
                        pendingAppLaunchRetryMessage = null
                        dismissAuthDialog()
                        binding.navHostFragment.visibility = View.VISIBLE
                        invalidateMenu()
                        processPendingDeepLinkIfAny()
                        processPendingDocumentResultIfAny()
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
        isAuthPromptInProgress = false
        pendingAppLaunchRetryDialog = false
        pendingAppLaunchRetryMessage = null
        dismissTransientDialog()
        dismissAuthDialog()
    }

    override fun onStart() {
        super.onStart()
        settings.sharedPreferences.registerOnSharedPreferenceChangeListener(authModePrefListener)
        viewModel.onSessionStart()
        invalidateMenu()
    }

    override fun onStop() {
        settings.sharedPreferences.unregisterOnSharedPreferenceChangeListener(authModePrefListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && !isDestroyed && !isSessionAuthenticated()) {
            verifyAuthentication()
        }
    }

    fun quitAndLock() {
        binding.navHostFragment.visibility = View.INVISIBLE
        dismissTransientDialog()
        dismissAuthDialog()
        finish()
        viewModel.lockSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (packageName == intent.extras?.getString(SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA)) {
            Log.i(TAG, "Intent shared from the same package name. Ignoring the intent and do not add the token")
            return
        }

        if (intent.getBooleanExtra(EXTRA_DEEP_LINK_HANDLED, false)) {
            return
        }
        val uri = intent.data ?: return
        intent.putExtra(EXTRA_DEEP_LINK_HANDLED, true)

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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_token)
            .setMessage(getString(R.string.add_token_confirmation_message, tokenDetails))
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.add) { _, _ ->
                if (!isSessionAuthenticated()) {
                    pendingDeepLinkUri = uri
                    verifyAuthentication()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        otpTokenDatabase.otpTokenDao().insert(
                            tokenCryptoManager.encryptTokenForStorage(token)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Vault locked while adding deep-link token", e)
                        if (!isSessionAuthenticated()) {
                            pendingDeepLinkUri = uri
                            verifyAuthentication()
                        } else {
                            showSnackbar(R.string.invalid_token)
                        }
                    } catch (e: GeneralSecurityException) {
                        Log.e(TAG, "Failed encrypting deep-link token", e)
                        showSnackbar(R.string.invalid_token)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed adding deep-link token", e)
                        showSnackbar(R.string.invalid_token)
                    }
                }
            }
            .setNegativeButton(R.string.cancel_text, null)
            .create()
        showTrackedDialog(dialog)
    }

    private inline fun launchSystemPicker(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Cannot find activity", e)
            Toast.makeText(applicationContext, getString(R.string.launch_file_browser_failure), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun startImportJson() {
        if (!isSessionAuthenticated()) return
        launchSystemPicker { openJsonLauncher.launch(arrayOf("*/*")) }
    }

    internal fun startImportKeyUri() {
        if (!isSessionAuthenticated()) return
        launchSystemPicker { openKeyUriLauncher.launch(arrayOf("*/*")) }
    }

    internal fun startImportTokenImages() {
        if (!isSessionAuthenticated()) return
        launchSystemPicker {
            openImagesZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
        }
    }

    internal fun startExportTokenImages() {
        if (!isSessionAuthenticated()) return
        AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
            val timestamp = dateFormatter.format(Date())
            launchSystemPicker {
                createImagesZipLauncher.launch("freeotp_images_$timestamp.zip")
            }
        }
    }

    internal fun showSortConfirmationDialog(order: TokenSortOrder) {
        if (!isSessionAuthenticated()) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sort_tokens_warning_title)
            .setMessage(R.string.sort_tokens_warning_message)
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.ok_text) { _, _ ->
                viewModel.sortTokens(order)
            }
            .setNegativeButton(R.string.cancel_text, null)
            .create()
        showTrackedDialog(dialog)
    }

    internal fun handleExportAction(isJson: Boolean) {
        if (!isSessionAuthenticated()) return
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
        val dialog = MaterialAlertDialogBuilder(this)
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
            .create()
        showTrackedDialog(dialog)
    }

    private fun launchExportCreateFile(isJson: Boolean) {
        val baseFileName = exportBaseFileName(encrypted = pendingExportEncrypted)
        val timestamp = dateFormatter.format(Date())
        launchSystemPicker {
            if (isJson) {
                createJsonLauncher.launch("${baseFileName}_$timestamp.json")
            } else {
                createKeyUriLauncher.launch("${baseFileName}_$timestamp.txt")
            }
        }
    }

    internal fun onDocumentPicked(action: DocumentAction, uri: Uri?) {
        if (uri == null) {
            appSessionManager.refreshSessionState()
            return
        }
        if (!appSessionManager.refreshSessionState()) {
            pendingDocumentResult = PendingDocumentResult(action, uri)
            return
        }
        handleAuthenticatedDocumentResult(action, uri)
    }

    private fun processPendingDocumentResultIfAny() {
        if (!viewModel.isAuthenticated() || !tokenCryptoManager.isUnlocked()) {
            return
        }
        val pending = pendingDocumentResult ?: return
        pendingDocumentResult = null
        handleAuthenticatedDocumentResult(pending.action, pending.uri)
    }

    private fun handleAuthenticatedDocumentResult(action: DocumentAction, uri: Uri) {
        when (action) {
            DocumentAction.EXPORT_JSON -> {
                val encryptExport = settings.authMode == AuthMode.TOKEN_ENCRYPTED && pendingExportEncrypted
                lifecycleScope.launch {
                    try {
                        importFromUtil.exportJsonFile(uri, encrypt = encryptExport)
                        showSnackbar(R.string.export_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Export JSON failed", e)
                        showSnackbar(R.string.export_failed_text)
                    }
                }
            }

            DocumentAction.IMPORT_JSON -> {
                val dialog = MaterialAlertDialogBuilder(this)
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
                                    Glide.get(this@MainActivity).clearMemory()
                                    viewModel.notifyTokenImagesChanged()
                                    showSnackbar(R.string.import_succeeded_text)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Import JSON failed", e)
                                showSnackbar(R.string.import_json_failed_text)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel_text, null)
                    .create()
                showTrackedDialog(dialog)
            }

            DocumentAction.EXPORT_KEY_URI -> {
                val encryptExport = settings.authMode == AuthMode.TOKEN_ENCRYPTED && pendingExportEncrypted
                lifecycleScope.launch {
                    try {
                        importFromUtil.exportKeyUriFile(uri, encrypt = encryptExport)
                        showSnackbar(R.string.export_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Export Key URI failed", e)
                        showSnackbar(R.string.export_failed_text)
                    }
                }
            }

            DocumentAction.IMPORT_KEY_URI -> {
                lifecycleScope.launch {
                    try {
                        if (importFromUtil.isKeyUriFileEncrypted(uri)) {
                            showImportEncryptedBackupDialog(uri, isJson = false)
                        } else {
                            importFromUtil.importKeyUriFile(uri)
                            Glide.get(this@MainActivity).clearMemory()
                            viewModel.notifyTokenImagesChanged()
                            showSnackbar(R.string.import_succeeded_text)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        showSnackbar(R.string.import_key_uri_failed_text)
                    }
                }
            }

            DocumentAction.EXPORT_IMAGES -> {
                lifecycleScope.launch {
                    try {
                        importFromUtil.exportTokenImagesZip(uri)
                        showSnackbar(R.string.export_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Export token images failed", e)
                        showSnackbar(R.string.export_failed_text)
                    }
                }
            }

            DocumentAction.IMPORT_IMAGES -> {
                lifecycleScope.launch {
                    try {
                        importFromUtil.importTokenImagesZip(uri)
                        Glide.get(this@MainActivity).clearMemory()
                        viewModel.notifyTokenImagesChanged()
                        showSnackbar(R.string.import_succeeded_text)
                    } catch (e: Exception) {
                        Log.e(TAG, "Import token images failed", e)
                        showSnackbar(R.string.import_token_images_failed_text)
                    }
                }
            }
        }
    }

    private fun showImportEncryptedBackupDialog(uri: Uri, isJson: Boolean) {
        val dialog = AuthenticationUtil.createManualKeyPromptDialog(
            activity = this,
            titleRes = R.string.import_encrypted_backup_title,
            messageRes = R.string.import_encrypted_backup_message,
            positiveButtonRes = R.string.import_text
        ) { passphrase, _, dismissSafely ->
            try {
                if (isJson) {
                    importFromUtil.importJsonFile(uri, passphrase)
                } else {
                    importFromUtil.importKeyUriFile(uri, passphrase)
                }
                Glide.get(this@MainActivity).clearMemory()
                viewModel.notifyTokenImagesChanged()
                dismissSafely()
                showSnackbar(R.string.import_succeeded_text)
                null
            } catch (_: InvalidEncryptionKeyException) {
                getString(R.string.incorrect_encryption_key)
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted import failed", e)
                dismissSafely()
                showSnackbar(if (isJson) R.string.import_json_failed_text else R.string.import_key_uri_failed_text)
                null
            }
        }
        showTrackedDialog(dialog)
    }

    internal fun selectAuthenticationMode(selectedMode: AuthMode) {
        invalidateMenu()
        if (!isSessionAuthenticated()) return
        val currentMode = settings.authMode
        if (selectedMode != currentMode) {
            handleAuthModeSelection(currentMode, selectedMode)
        }
    }

    private fun handleAuthModeSelection(currentMode: AuthMode, targetMode: AuthMode) {
        if (isAuthPromptInProgress) return
        val hasAppLaunchCredential = AuthenticationUtil.appLaunchAuthCallbackOverride != null ||
            AuthenticationUtil.hasScreenLock(this)
        when {
            targetMode == AuthMode.TOKEN_ENCRYPTED -> {
                if (currentMode == AuthMode.APP_LAUNCH && hasAppLaunchCredential) {
                    isAuthPromptInProgress = true
                    AuthenticationUtil.authenticateAppLaunch(
                        activity = this,
                        onSuccess = {
                            isAuthPromptInProgress = false
                            viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                            showSetEncryptionKeyDialog()
                        },
                        onNoDeviceCredential = {
                            isAuthPromptInProgress = false
                            showSetEncryptionKeyDialog()
                        },
                        onCancelOrError = { code, msg ->
                            isAuthPromptInProgress = false
                            showNonFatalAuthErrorIfNeeded(code, msg)
                        }
                    )
                } else {
                    showSetEncryptionKeyDialog()
                }
            }
            currentMode == AuthMode.TOKEN_ENCRYPTED -> {
                if (targetMode == AuthMode.APP_LAUNCH && !hasAppLaunchCredential) {
                    showScreenLockRequiredDialog()
                } else {
                    showDisableEncryptionWarningDialog(targetMode)
                }
            }
            targetMode == AuthMode.APP_LAUNCH -> {
                if (!hasAppLaunchCredential) {
                    showScreenLockRequiredDialog()
                    return
                }
                isAuthPromptInProgress = true
                AuthenticationUtil.authenticateAppLaunch(
                    activity = this,
                    onSuccess = {
                        isAuthPromptInProgress = false
                        appSessionManager.prepareAuthenticatedSession()
                        settings.authMode = AuthMode.APP_LAUNCH
                        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                        invalidateMenu()
                    },
                    onNoDeviceCredential = {
                        isAuthPromptInProgress = false
                        showScreenLockRequiredDialog()
                    },
                    onCancelOrError = { code, msg ->
                        isAuthPromptInProgress = false
                        showNonFatalAuthErrorIfNeeded(code, msg)
                    }
                )
            }
            else -> {
                if (!hasAppLaunchCredential) {
                    disableAppLaunchWhenScreenLockMissing()
                    return
                }
                isAuthPromptInProgress = true
                AuthenticationUtil.authenticateAppLaunch(
                    activity = this,
                    onSuccess = {
                        isAuthPromptInProgress = false
                        settings.authMode = AuthMode.NONE
                        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                        invalidateMenu()
                    },
                    onNoDeviceCredential = {
                        isAuthPromptInProgress = false
                        disableAppLaunchWhenScreenLockMissing()
                    },
                    onCancelOrError = { code, msg ->
                        isAuthPromptInProgress = false
                        showNonFatalAuthErrorIfNeeded(code, msg)
                    }
                )
            }
        }
    }

    private fun showNonFatalAuthErrorIfNeeded(errorCode: Int, errString: CharSequence?) {
        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
            errorCode != BiometricPrompt.ERROR_CANCELED &&
            !errString.isNullOrBlank()
        ) {
            Toast.makeText(
                applicationContext,
                "${getString(R.string.authentication_error)} $errString",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openSecuritySettings() {
        val candidateIntents = listOf(
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (candidate in candidateIntents) {
            try {
                startActivity(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try next fallback Settings action
            }
        }
        Toast.makeText(applicationContext, R.string.unable_to_authenticate, Toast.LENGTH_SHORT).show()
    }

    private fun showScreenLockRequiredDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.screen_lock_required_title)
            .setMessage(R.string.screen_lock_required_message)
            .setPositiveButton(R.string.open_settings_button) { _, _ ->
                openSecuritySettings()
            }
            .setNegativeButton(R.string.cancel_text, null)
            .create()
        showTrackedDialog(dialog)
    }

    private fun disableAppLaunchWhenScreenLockMissing() {
        settings.authMode = AuthMode.NONE
        onAuthCompleted()
        invalidateMenu()
        showSnackbar(R.string.app_launch_auth_disabled)
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
            AuthenticationUtil.applySecureFlag(this, dialog)
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val passphraseChars = AuthenticationUtil.extractCharArray(keyEditText)
                val confirmChars = AuthenticationUtil.extractCharArray(confirmKeyEditText)
                var handedOffToCoroutine = false
                try {
                    if (passphraseChars.size < CryptoEngine.MIN_KEY_LENGTH) {
                        keyInputLayout.error = getString(R.string.encryption_key_too_short, CryptoEngine.MIN_KEY_LENGTH)
                        return@setOnClickListener
                    }
                    if (!passphraseChars.contentEquals(confirmChars)) {
                        confirmKeyInputLayout.error = getString(R.string.encryption_keys_do_not_match)
                        return@setOnClickListener
                    }

                    positiveButton.isEnabled = false
                    AuthenticationUtil.clearAndCancelAutofill(this, keyEditText, confirmKeyEditText)
                    handedOffToCoroutine = true
                    lifecycleScope.launch {
                        val setupMaterial = try {
                            tokenCryptoManager.deriveSetupMaterial(passphraseChars)
                        } finally {
                            passphraseChars.fill('\u0000')
                        }
                        completeTokenEncryptionSetup(setupMaterial, dialog)
                    }
                } finally {
                    confirmChars.fill('\u0000')
                    if (!handedOffToCoroutine) {
                        passphraseChars.fill('\u0000')
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(this, keyEditText, confirmKeyEditText)
        }
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
        showTrackedDialog(dialog)
    }

    private suspend fun enableManualOnlyEncryption(
        setupMaterial: TokenCryptoManager.SetupMaterial,
        setupDialog: AlertDialog? = null
    ) {
        try {
            appSessionManager.prepareAuthenticatedSession()
            tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
            setupDialog?.let { AuthenticationUtil.dismissKeyDialogSafely(it) }
            onAuthCompleted()
            invalidateMenu()
            showSnackbar(R.string.token_encryption_enabled_manual_only, Snackbar.LENGTH_LONG)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed enabling token encryption", e)
            setupDialog?.let { AuthenticationUtil.dismissKeyDialogSafely(it) }
            showSnackbar(R.string.authentication_failed)
        } finally {
            setupMaterial.clear()
        }
    }

    private suspend fun completeTokenEncryptionSetup(
        setupMaterial: TokenCryptoManager.SetupMaterial,
        setupDialog: AlertDialog
    ) {
        if (!AuthenticationUtil.canAuthenticateStrongBiometric(this)) {
            enableManualOnlyEncryption(setupMaterial, setupDialog)
            return
        }

        val encryptCipher = try {
            tokenCryptoManager.createBiometricEncryptCipher()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize biometric encryption cipher, falling back to manual key", e)
            enableManualOnlyEncryption(setupMaterial, setupDialog)
            return
        }

        AuthenticationUtil.dismissKeyDialogSafely(setupDialog)
        isAuthPromptInProgress = true
        AuthenticationUtil.authenticateBiometricCrypto(
            activity = this,
            title = getString(R.string.encrypt_key_with_biometric_title),
            subtitle = getString(R.string.encrypt_key_with_biometric_subtitle),
            negativeButtonText = getString(R.string.use_manual_key_only),
            cipher = encryptCipher,
            onSuccess = { authenticatedCipher ->
                isAuthPromptInProgress = false
                lifecycleScope.launch {
                    var delegatedToManualFallback = false
                    try {
                        val wrappedBlob = tokenCryptoManager.wrapKeyWithBiometricCipher(
                            authenticatedCipher,
                            setupMaterial.keyBytes
                        )
                        appSessionManager.prepareAuthenticatedSession()
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, wrappedBlob)
                        onAuthCompleted()
                        invalidateMenu()
                        showSnackbar(R.string.token_encryption_enabled)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Biometric key wrapping failed, falling back to manual key", e)
                        tokenCryptoManager.cancelPendingBiometricKeySetup()
                        delegatedToManualFallback = true
                        enableManualOnlyEncryption(setupMaterial)
                    } finally {
                        if (!delegatedToManualFallback) {
                            setupMaterial.clear()
                        }
                    }
                }
            },
            onFallbackToManual = { _ ->
                isAuthPromptInProgress = false
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                lifecycleScope.launch {
                    enableManualOnlyEncryption(setupMaterial)
                }
            },
            onCancel = {
                isAuthPromptInProgress = false
                tokenCryptoManager.cancelPendingBiometricKeySetup()
                setupMaterial.clear()
            }
        )
    }

    private fun showDisableEncryptionWarningDialog(targetMode: AuthMode) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disable_token_encryption_title)
            .setMessage(R.string.disable_token_encryption_warning)
            .setIcon(R.drawable.alert)
            .setPositiveButton(R.string.ok_text) { _, _ ->
                authenticateAndDisableEncryption(targetMode)
            }
            .setNegativeButton(R.string.cancel_text, null)
            .create()
        showTrackedDialog(dialog)
    }

    private fun authenticateAndDisableEncryption(targetMode: AuthMode) {
        if (isAuthPromptInProgress) return
        val canBio = AuthenticationUtil.canAuthenticateStrongBiometric(this)
        val prep = if (canBio) tokenCryptoManager.prepareBiometricDecryptCipher() else null
        val decryptCipher = prep?.cipher

        if (canBio && prep?.status == TokenCryptoManager.BiometricKeyStatus.READY && decryptCipher != null) {
            isAuthPromptInProgress = true
            AuthenticationUtil.authenticateBiometricCrypto(
                activity = this,
                title = getString(R.string.authentication_dialog_title),
                subtitle = getString(R.string.unlock_tokens_biometric_subtitle),
                negativeButtonText = getString(R.string.enter_key_manually),
                cipher = decryptCipher,
                onSuccess = { authenticatedCipher ->
                    isAuthPromptInProgress = false
                    if (tokenCryptoManager.unlockWithBiometricCipher(authenticatedCipher)) {
                        performDisableEncryption(targetMode)
                    } else {
                        showVerifyKeyToDisableDialog(targetMode)
                    }
                },
                onFallbackToManual = { _ ->
                    isAuthPromptInProgress = false
                    showVerifyKeyToDisableDialog(targetMode)
                },
                onCancel = {
                    isAuthPromptInProgress = false
                }
            )
        } else {
            showVerifyKeyToDisableDialog(targetMode)
        }
    }

    private fun showVerifyKeyToDisableDialog(targetMode: AuthMode) {
        val dialog = AuthenticationUtil.createManualKeyPromptDialog(
            activity = this,
            titleRes = R.string.verify_encryption_key_title,
            messageRes = R.string.verify_encryption_key_message,
            positiveButtonRes = R.string.ok_text
        ) { chars, _, dismissSafely ->
            if (tokenCryptoManager.unlockWithPassphrase(chars)) {
                try {
                    appSessionManager.prepareAuthenticatedSession()
                    tokenCryptoManager.disableTokenEncryption(targetMode)
                    dismissSafely()
                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                    invalidateMenu()
                    showSnackbar(R.string.token_encryption_disabled)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed disabling token encryption", e)
                    getString(R.string.authentication_failed)
                }
            } else {
                getString(R.string.incorrect_encryption_key)
            }
        }
        showTrackedDialog(dialog)
    }

    private fun performDisableEncryption(targetMode: AuthMode) {
        lifecycleScope.launch {
            try {
                appSessionManager.prepareAuthenticatedSession()
                tokenCryptoManager.disableTokenEncryption(targetMode)
                viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
                invalidateMenu()
                showSnackbar(R.string.token_encryption_disabled)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed disabling token encryption", e)
                showSnackbar(R.string.authentication_failed)
            }
        }
    }

    private fun dismissAuthDialog() {
        activeAuthDialog?.dismiss()
        activeAuthDialog = null
        activeAuthDialogKind = AuthDialogKind.NONE
    }

    private fun verifyAuthentication() {
        if (isFinishing || isDestroyed) {
            return
        }
        val currentMode = tokenCryptoManager.getAuthMode()
        if (currentMode == AuthMode.APP_LAUNCH) {
            val hasAppLaunchCredential = AuthenticationUtil.appLaunchAuthCallbackOverride != null ||
                AuthenticationUtil.hasScreenLock(this)
            if (!hasAppLaunchCredential) {
                pendingAppLaunchRetryDialog = false
                pendingAppLaunchRetryMessage = null
                if (activeAuthDialogKind == AuthDialogKind.APP_LAUNCH_RETRY) {
                    dismissAuthDialog()
                }
            } else {
                if (activeAuthDialogKind == AuthDialogKind.APP_LAUNCH_NO_SCREEN_LOCK) {
                    dismissAuthDialog()
                }
                if (pendingAppLaunchRetryDialog &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    val msg = pendingAppLaunchRetryMessage
                    pendingAppLaunchRetryDialog = false
                    pendingAppLaunchRetryMessage = null
                    showAppLaunchRetryDialog(msg)
                    return
                }
            }
        } else {
            pendingAppLaunchRetryDialog = false
            pendingAppLaunchRetryMessage = null
        }

        if (isAuthPromptInProgress || activeAuthDialog?.isShowing == true) {
            return
        }

        when (currentMode) {
            AuthMode.NONE -> {
                onAuthCompleted()
            }
            AuthMode.APP_LAUNCH -> {
                isAuthPromptInProgress = true
                AuthenticationUtil.authenticateAppLaunch(
                    activity = this,
                    onSuccess = {
                        isAuthPromptInProgress = false
                        onAuthCompleted()
                    },
                    onNoDeviceCredential = {
                        isAuthPromptInProgress = false
                        showAppLaunchScreenLockDisabledDialog()
                    },
                    onCancelOrError = { errorCode, errString ->
                        isAuthPromptInProgress = false
                        if (isSessionAuthenticated()) {
                            return@authenticateAppLaunch
                        }
                        val message = errString?.takeIf {
                            errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                                errorCode != BiometricPrompt.ERROR_CANCELED &&
                                it.isNotBlank()
                        }?.toString()
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            pendingAppLaunchRetryDialog = true
                            pendingAppLaunchRetryMessage = message
                            return@authenticateAppLaunch
                        }
                        showAppLaunchRetryDialog(message)
                    }
                )
            }
            AuthMode.TOKEN_ENCRYPTED -> {
                verifyTokenEncryptedLaunch()
            }
        }
    }

    private fun showAppLaunchScreenLockDisabledDialog() {
        dismissAuthDialog()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.screen_lock_disabled_title)
            .setMessage(R.string.screen_lock_disabled_message)
            .setCancelable(false)
            .setPositiveButton(R.string.set_up_screen_lock_button) { _, _ ->
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                openSecuritySettings()
            }
            .setNeutralButton(R.string.turn_off_button) { _, _ ->
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                disableAppLaunchWhenScreenLockMissing()
            }
            .setNegativeButton(R.string.cancel_text) { _, _ ->
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                finish()
            }
            .create()
        activeAuthDialog = dialog
        activeAuthDialogKind = AuthDialogKind.APP_LAUNCH_NO_SCREEN_LOCK
        AuthenticationUtil.applySecureFlag(this, dialog)
        dialog.show()
        AuthenticationUtil.applySecureFlag(this, dialog)
    }

    private fun showAppLaunchRetryDialog(errorMessage: String?) {
        dismissAuthDialog()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.authentication_dialog_title)
            .setMessage(errorMessage ?: getString(R.string.authentication_dialog_subtitle))
            .setCancelable(false)
            .setPositiveButton(R.string.unlock_text) { _, _ ->
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                verifyAuthentication()
            }
            .setNegativeButton(R.string.cancel_text) { _, _ ->
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                finish()
            }
            .create()
        activeAuthDialog = dialog
        activeAuthDialogKind = AuthDialogKind.APP_LAUNCH_RETRY
        AuthenticationUtil.applySecureFlag(this, dialog)
        dialog.show()
        AuthenticationUtil.applySecureFlag(this, dialog)
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
        dismissAuthDialog()

        val canOfferBiometric = (!tokenCryptoManager.isBiometricKeyConfigured() || biometricInvalidated) &&
            AuthenticationUtil.canAuthenticateStrongBiometric(this)
        val biometricLabelRes = if (canOfferBiometric) {
            if (biometricInvalidated) R.string.reenable_biometric_unlock else R.string.enable_biometric_unlock
        } else {
            null
        }
        val messageRes = if (biometricInvalidated) {
            R.string.biometric_invalidated_enter_key_message
        } else {
            R.string.enter_encryption_key_to_unlock_message
        }

        val dialog = AuthenticationUtil.createManualKeyPromptDialog(
            activity = this,
            titleRes = R.string.unlock_tokens_title,
            messageRes = messageRes,
            positiveButtonRes = R.string.unlock_text,
            cancelable = !isLaunchGate,
            biometricCheckboxLabelRes = biometricLabelRes,
            onCancel = {
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                if (isLaunchGate) {
                    finish()
                }
            }
        ) { chars, biometricChecked, dismissSafely ->
            if (!tokenCryptoManager.unlockWithPassphrase(chars)) {
                getString(R.string.incorrect_encryption_key)
            } else {
                dismissSafely()
                activeAuthDialog = null
                activeAuthDialogKind = AuthDialogKind.NONE
                if (canOfferBiometric && biometricChecked) {
                    promptReenableBiometricAfterManualUnlock()
                } else {
                    onAuthCompleted()
                }
                null
            }
        }

        activeAuthDialog = dialog
        activeAuthDialogKind = AuthDialogKind.MANUAL_KEY
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
        pendingAppLaunchRetryDialog = false
        pendingAppLaunchRetryMessage = null
        viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
        processPendingDeepLinkIfAny()
        processPendingDocumentResultIfAny()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val SCREENSHOT_MODE_EXTRA = "screenshot_mode"
        const val SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA = "shareFromPackageName"
        private const val STATE_PENDING_EXPORT_ENCRYPTED = "pending_export_encrypted"
        private const val STATE_PENDING_DEEP_LINK_URI = "pending_deep_link_uri"
        private const val EXTRA_DEEP_LINK_HANDLED = "org.fedorahosted.freeotp.EXTRA_DEEP_LINK_HANDLED"

        internal fun exportBaseFileName(encrypted: Boolean): String {
            return if (encrypted) "freeotp-backup-encrypted" else "freeotp-backup"
        }
    }
}
