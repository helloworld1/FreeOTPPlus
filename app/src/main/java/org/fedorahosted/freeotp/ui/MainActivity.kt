package org.fedorahosted.freeotp.ui

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.EncryptionType
import org.fedorahosted.freeotp.data.InvalidEncryptionKeyException
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.TokenImportMode
import org.fedorahosted.freeotp.data.TokenSortOrder
import org.fedorahosted.freeotp.data.legacy.ImportExportUtil
import org.fedorahosted.freeotp.data.util.TokenCodeUtil
import org.fedorahosted.freeotp.ui.components.DialogWindowRegistry
import org.fedorahosted.freeotp.ui.dialogs.AppLaunchRetryDialog
import org.fedorahosted.freeotp.ui.dialogs.BackupImportDialog
import org.fedorahosted.freeotp.ui.dialogs.DeepLinkAddTokenDialog
import org.fedorahosted.freeotp.ui.dialogs.DisableEncryptionWarningDialog
import org.fedorahosted.freeotp.ui.dialogs.ExportEncryptionChoiceDialog
import org.fedorahosted.freeotp.ui.dialogs.ManualKeyPromptDialog
import org.fedorahosted.freeotp.ui.dialogs.ScreenLockDisabledDialog
import org.fedorahosted.freeotp.ui.dialogs.ScreenLockRequiredDialog
import org.fedorahosted.freeotp.ui.dialogs.SetEncryptionKeyDialog
import org.fedorahosted.freeotp.ui.dialogs.SortConfirmationDialog
import org.fedorahosted.freeotp.ui.navigation.FreeOtpDestination
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.AuthenticationUtil
import org.fedorahosted.freeotp.util.ClipboardUtil
import org.fedorahosted.freeotp.util.CopyToClipboardMode
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.ThemeMode
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import java.security.GeneralSecurityException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    internal enum class DocumentAction {
        IMPORT_JSON,
        IMPORT_KEY_URI,
        IMPORT_FREEOTP_BACKUP,
        IMPORT_AEGIS_JSON,
        IMPORT_IMAGES,
        EXPORT_JSON,
        EXPORT_KEY_URI,
        EXPORT_IMAGES,
    }

    private data class PendingDocumentResult(
        val action: DocumentAction,
        val uri: Uri,
    )

    private data class SnackbarRequest(
        val message: String,
        val duration: SnackbarDuration,
    )

    private sealed interface TransientDialogState {
        data class SortConfirm(val order: TokenSortOrder) : TransientDialogState
        data class ExportEncryptionChoice(val isJson: Boolean) : TransientDialogState
        data class DeepLinkAddToken(
            val uri: Uri,
            val token: OtpToken,
        ) : TransientDialogState
        data object ScreenLockRequired : TransientDialogState
        data object SetEncryptionKey : TransientDialogState
        data class DisableEncryptionWarning(val targetMode: AuthMode) : TransientDialogState
        data class VerifyKeyPrompt(
            @param:StringRes val titleRes: Int,
            @param:StringRes val messageRes: Int,
            @param:StringRes val positiveButtonRes: Int,
            val onSubmit: suspend (passphrase: CharArray) -> String?,
        ) : TransientDialogState
        data class BackupImport(
            val requiresPassword: Boolean,
            @param:StringRes val titleRes: Int,
            @param:StringRes val encryptedMessageRes: Int,
            @param:StringRes val unencryptedMessageRes: Int,
            @param:StringRes val passwordHintRes: Int,
            @param:StringRes val incorrectPasswordRes: Int,
            @param:StringRes val failedSnackbarRes: Int,
            val logLabel: String,
            val onImport: suspend (CharArray?, TokenImportMode) -> Unit,
        ) : TransientDialogState
    }

    private sealed interface AuthGateDialogState {
        data object None : AuthGateDialogState
        data class AppLaunchRetry(val errorMessage: String?) : AuthGateDialogState
        data object AppLaunchNoScreenLock : AuthGateDialogState
        data class ManualKeyUnlock(
            val isLaunchGate: Boolean,
            val biometricInvalidated: Boolean,
            val canOfferBiometric: Boolean,
            val initialBiometricChecked: Boolean,
            @param:StringRes val biometricCheckboxLabelRes: Int?,
            @param:StringRes val messageRes: Int,
        ) : AuthGateDialogState
    }

    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var otpTokenDatabase: OtpTokenDatabase
    @Inject lateinit var tokenCryptoManager: TokenCryptoManager
    @Inject lateinit var tokenCodeUtil: TokenCodeUtil
    @Inject lateinit var tokenQRCodeDecoder: TokenQRCodeDecoder
    @Inject lateinit var executorService: ExecutorService
    @Inject lateinit var appSessionManager: AppSessionManager
    @Inject lateinit var clipboardUtil: ClipboardUtil

    private val viewModel: MainViewModel by viewModels()

    internal val dialogWindowRegistry = DialogWindowRegistry()
    private val snackbarHostState = SnackbarHostState()
    private val snackbarChannel = Channel<SnackbarRequest>(Channel.BUFFERED)
    private var navControllerRef: NavHostController? = null

    private var shareQrTokenId by mutableStateOf<Long?>(null)
    private var deleteTokenId by mutableStateOf<Long?>(null)
    private var activeTransientDialog by mutableStateOf<TransientDialogState?>(null)
    private var activeAuthGateDialog by mutableStateOf<AuthGateDialogState>(AuthGateDialogState.None)

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
                    settings.refreshFlows()
                    appSessionManager.refreshSessionState()
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

    private val openFreeOtpBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onDocumentPicked(DocumentAction.IMPORT_FREEOTP_BACKUP, uri)
    }

    private val openAegisJsonLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        onDocumentPicked(DocumentAction.IMPORT_AEGIS_JSON, uri)
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

    fun navigateToRoute(route: String) {
        snackbarHostState.currentSnackbarData?.dismiss()
        navControllerRef?.navigate(route) {
            launchSingleTop = true
        }
    }

    fun navigateToEditToken(tokenId: Long) {
        navigateToRoute(FreeOtpDestination.editToken(tokenId))
    }

    fun openShareQrDialog(tokenId: Long) {
        shareQrTokenId = tokenId
    }

    fun openDeleteTokenDialog(tokenId: Long) {
        deleteTokenId = tokenId
    }

    fun navigateBack(): Boolean {
        return navControllerRef?.popBackStack() ?: false
    }

    fun currentRoute(): String? {
        return navControllerRef?.currentDestination?.route
    }

    fun getOpenDialogWindows(): List<Window> {
        return dialogWindowRegistry.snapshot()
    }

    internal fun isSessionAuthenticated(): Boolean {
        return viewModel.isAuthenticated() && tokenCryptoManager.isUnlocked()
    }

    private fun dismissTransientDialog() {
        activeTransientDialog = null
        dismissSnackbar()
    }

    fun dismissSnackbar() {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    fun showSnackbar(
        @StringRes messageRes: Int,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ) {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarChannel.trySend(
            SnackbarRequest(
                message = getString(messageRes),
                duration = duration,
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings.refreshFlows()

        if (savedInstanceState != null) {
            pendingExportEncrypted = savedInstanceState.getBoolean(STATE_PENDING_EXPORT_ENCRYPTED, false)
            @Suppress("DEPRECATION")
            pendingDeepLinkUri = savedInstanceState.getParcelable(STATE_PENDING_DEEP_LINK_URI)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ViewCompat.setImportantForAutofill(
                window.decorView,
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            )
        }

        viewModel.migrateOldData()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getAuthState().collect { authState ->
                    if (authState == MainViewModel.AuthState.UNAUTHENTICATED) {
                        dismissTransientDialog()
                        shareQrTokenId = null
                        deleteTokenId = null
                        navControllerRef?.let { controller ->
                            if (controller.currentDestination?.route != FreeOtpDestination.TOKEN_LIST) {
                                controller.popBackStack(FreeOtpDestination.TOKEN_LIST, inclusive = false)
                            }
                        }
                        if (!isFinishing && !isDestroyed) {
                            verifyAuthentication()
                        }
                    } else {
                        pendingAppLaunchRetryDialog = false
                        pendingAppLaunchRetryMessage = null
                        dismissAuthDialog()
                        processPendingDeepLinkIfAny()
                        processPendingDocumentResultIfAny()
                    }
                }
            }
        }

        setContent {
            val navController = rememberNavController()
            navControllerRef = navController

            LaunchedEffect(snackbarHostState) {
                snackbarChannel.receiveAsFlow().collect { req ->
                    snackbarHostState.showSnackbar(
                        message = req.message,
                        duration = req.duration,
                    )
                }
            }

            FreeOtpApp(
                viewModel = viewModel,
                navController = navController,
                snackbarHostState = snackbarHostState,
                dialogWindowRegistry = dialogWindowRegistry,
                otpTokenDatabase = otpTokenDatabase,
                tokenCryptoManager = tokenCryptoManager,
                tokenQRCodeDecoder = tokenQRCodeDecoder,
                executorService = executorService,
                shareQrTokenId = shareQrTokenId,
                deleteTokenId = deleteTokenId,
                onDismissShareQrDialog = { shareQrTokenId = null },
                onDismissDeleteTokenDialog = { deleteTokenId = null },
                onOpenShareQrDialog = { token ->
                    AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
                        shareQrTokenId = token.id
                    }
                },
                onOpenDeleteTokenDialog = { token ->
                    deleteTokenId = token.id
                },
                onTokenCardClick = ::handleTokenCardClick,
                onAdvanceHotp = ::handleAdvanceHotpCounter,
                onShareToOtherApps = ::handleShareTokenToOtherApps,
                onRequestRevealSecret = ::handleRevealTokenSecret,
                onCopySensitiveSecret = { plainSecret ->
                    clipboardUtil.copySensitiveText(getString(R.string.secret), plainSecret)
                    if (ClipboardUtil.shouldShowAppCopyFeedback()) {
                        showSnackbar(R.string.code_copied)
                    }
                },
                onCopyShareQrUri = { uriString ->
                    clipboardUtil.copySensitiveText(getString(R.string.share_qr_code), uriString)
                    if (ClipboardUtil.shouldShowAppCopyFeedback()) {
                        showSnackbar(R.string.token_uri_copied)
                    }
                },
                onShowSnackbar = ::showSnackbar,
                onVaultLocked = {
                    isSessionAuthenticated()
                },
                isSessionAuthenticated = ::isSessionAuthenticated,
                onQrCodeScanned = ::onQrCodeScanned,
                onPasteKeyUri = ::pasteKeyUriFromClipboard,
                onImportJson = ::startImportJson,
                onImportKeyUri = ::startImportKeyUri,
                onImportTokenImages = ::startImportTokenImages,
                onImportFreeOtpBackup = ::startImportFreeOtpBackup,
                onImportAegisJson = ::startImportAegisJson,
                onExportJson = { handleExportAction(isJson = true) },
                onExportKeyUri = { handleExportAction(isJson = false) },
                onExportTokenImages = ::startExportTokenImages,
                onRequestSort = ::showSortConfirmationDialog,
                onSelectThemeMode = ::applyThemeMode,
                onSelectTokenTimerStyle = { style ->
                    settings.tokenTimerStyle = style
                },
                onSelectTokenCodeGrouping = { grouping ->
                    settings.tokenCodeGrouping = grouping
                },
                onSelectAuthMode = ::selectAuthenticationMode,
                onSelectCopyToClipboardMode = { mode ->
                    settings.copyToClipboardMode = mode
                },
                onQuitAndLock = ::quitAndLock,
                dialogHostContent = {
                    RenderActivityDialogs()
                },
            )
        }

        if (savedInstanceState == null) {
            onNewIntent(intent)
        }
    }

    @Composable
    private fun RenderActivityDialogs() {
        when (val authDialog = activeAuthGateDialog) {
            AuthGateDialogState.None -> Unit
            is AuthGateDialogState.AppLaunchRetry -> {
                AppLaunchRetryDialog(
                    errorMessage = authDialog.errorMessage,
                    onRetryUnlock = {
                        activeAuthGateDialog = AuthGateDialogState.None
                        verifyAuthentication()
                    },
                    onCancelAndExit = {
                        activeAuthGateDialog = AuthGateDialogState.None
                        finish()
                    },
                )
            }
            AuthGateDialogState.AppLaunchNoScreenLock -> {
                ScreenLockDisabledDialog(
                    onSetUpScreenLock = {
                        activeAuthGateDialog = AuthGateDialogState.None
                        openSecuritySettings()
                    },
                    onTurnOffAppLaunchAuth = {
                        activeAuthGateDialog = AuthGateDialogState.None
                        disableAppLaunchWhenScreenLockMissing()
                    },
                    onCancelAndExit = {
                        activeAuthGateDialog = AuthGateDialogState.None
                        finish()
                    },
                )
            }
            is AuthGateDialogState.ManualKeyUnlock -> {
                androidx.compose.runtime.key(authDialog) {
                    ManualKeyPromptDialog(
                        titleRes = R.string.unlock_tokens_title,
                        messageRes = authDialog.messageRes,
                        positiveButtonRes = R.string.unlock_text,
                        cancelable = !authDialog.isLaunchGate,
                        biometricCheckboxLabelRes = authDialog.biometricCheckboxLabelRes,
                        initialBiometricChecked = authDialog.initialBiometricChecked,
                        onCancel = {
                            activeAuthGateDialog = AuthGateDialogState.None
                            if (authDialog.isLaunchGate) {
                                finish()
                            }
                        },
                        onSubmit = { chars, biometricChecked ->
                            if (!tokenCryptoManager.unlockWithPassphrase(chars)) {
                                getString(R.string.incorrect_encryption_key)
                            } else {
                                if (authDialog.canOfferBiometric) {
                                    settings.enableBiometricUnlock = biometricChecked
                                }
                                activeAuthGateDialog = AuthGateDialogState.None
                                if (authDialog.canOfferBiometric && biometricChecked) {
                                    promptReenableBiometricAfterManualUnlock()
                                } else {
                                    onAuthCompleted()
                                }
                                null
                            }
                        },
                    )
                }
            }
        }

        when (val transient = activeTransientDialog) {
            null -> Unit
            is TransientDialogState.SortConfirm -> {
                SortConfirmationDialog(
                    onConfirm = {
                        activeTransientDialog = null
                        viewModel.sortTokens(transient.order)
                    },
                    onDismiss = { activeTransientDialog = null },
                )
            }
            is TransientDialogState.ExportEncryptionChoice -> {
                ExportEncryptionChoiceDialog(
                    onExportEncrypted = {
                        activeTransientDialog = null
                        pendingExportEncrypted = true
                        launchExportCreateFile(transient.isJson)
                    },
                    onExportDecrypted = {
                        activeTransientDialog = null
                        pendingExportEncrypted = false
                        launchExportCreateFile(transient.isJson)
                    },
                    onDismiss = { activeTransientDialog = null },
                )
            }
            is TransientDialogState.DeepLinkAddToken -> {
                val imageRefreshVersion by viewModel.imageRefreshVersionFlow.collectAsStateWithLifecycle()
                DeepLinkAddTokenDialog(
                    token = transient.token,
                    imageRefreshVersion = imageRefreshVersion,
                    onConfirmAdd = {
                        activeTransientDialog = null
                        confirmDeepLinkAddToken(transient.uri, transient.token)
                    },
                    onDismiss = { activeTransientDialog = null },
                )
            }
            TransientDialogState.ScreenLockRequired -> {
                ScreenLockRequiredDialog(
                    onOpenSettings = {
                        activeTransientDialog = null
                        openSecuritySettings()
                    },
                    onDismiss = { activeTransientDialog = null },
                )
            }
            TransientDialogState.SetEncryptionKey -> {
                SetEncryptionKeyDialog(
                    onDismiss = { activeTransientDialog = null },
                    onSubmitValidKey = { passphraseChars ->
                        val setupMaterial = tokenCryptoManager.deriveSetupMaterial(passphraseChars)
                        completeTokenEncryptionSetup(setupMaterial)
                    },
                )
            }
            is TransientDialogState.DisableEncryptionWarning -> {
                DisableEncryptionWarningDialog(
                    onConfirm = {
                        activeTransientDialog = null
                        authenticateAndDisableEncryption(transient.targetMode)
                    },
                    onDismiss = { activeTransientDialog = null },
                )
            }
            is TransientDialogState.VerifyKeyPrompt -> {
                androidx.compose.runtime.key(transient) {
                    ManualKeyPromptDialog(
                        titleRes = transient.titleRes,
                        messageRes = transient.messageRes,
                        positiveButtonRes = transient.positiveButtonRes,
                        cancelable = true,
                        biometricCheckboxLabelRes = null,
                        onCancel = { activeTransientDialog = null },
                        onSubmit = { chars, _ ->
                            transient.onSubmit(chars)
                        },
                    )
                }
            }
            is TransientDialogState.BackupImport -> {
                androidx.compose.runtime.key(transient) {
                    BackupImportDialog(
                        requiresPassword = transient.requiresPassword,
                        titleRes = transient.titleRes,
                        encryptedMessageRes = transient.encryptedMessageRes,
                        unencryptedMessageRes = transient.unencryptedMessageRes,
                        passwordHintRes = transient.passwordHintRes,
                        incorrectPasswordRes = transient.incorrectPasswordRes,
                        onDismiss = { activeTransientDialog = null },
                        onSubmit = { passphrase, selectedMode ->
                            try {
                                transient.onImport(passphrase, selectedMode)
                                Glide.get(this@MainActivity).clearMemory()
                                viewModel.notifyTokenImagesChanged()
                                activeTransientDialog = null
                                showSnackbar(R.string.import_succeeded_text)
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: InvalidEncryptionKeyException) {
                                getString(transient.incorrectPasswordRes)
                            } catch (e: Exception) {
                                Log.e(TAG, "Import ${transient.logLabel} failed", e)
                                activeTransientDialog = null
                                showSnackbar(transient.failedSnackbarRes)
                                null
                            }
                        },
                    )
                }
            }
        }
    }

    private fun handleTokenCardClick(clickedToken: OtpToken) {
        lifecycleScope.launch {
            val token = otpTokenDatabase.otpTokenDao().get(clickedToken.id).first() ?: return@launch
            if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                viewModel.onSessionStart()
                return@launch
            }

            val copyMode = settings.copyToClipboardMode
            val alreadyRevealedCode = viewModel.getActiveTokenCode(token.id)
                ?.currentCode
                ?.takeIf { it.isNotEmpty() }
            if (copyMode == CopyToClipboardMode.TAP_REVEALED && alreadyRevealedCode != null) {
                clipboardUtil.copySensitiveText(null, alreadyRevealedCode)
                if (ClipboardUtil.shouldShowAppCopyFeedback()) {
                    showSnackbar(R.string.code_copied)
                }
                return@launch
            }

            generateAndRevealTokenCode(token, copyMode)
        }
    }

    private fun handleAdvanceHotpCounter(clickedToken: OtpToken) {
        lifecycleScope.launch {
            val token = otpTokenDatabase.otpTokenDao().get(clickedToken.id).first() ?: return@launch
            if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                viewModel.onSessionStart()
                return@launch
            }
            generateAndRevealTokenCode(token, settings.copyToClipboardMode)
        }
    }

    private suspend fun generateAndRevealTokenCode(
        token: OtpToken,
        copyMode: CopyToClipboardMode,
    ) {
        val codes = try {
            tokenCodeUtil.generateTokenCode(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                viewModel.onSessionStart()
            } else {
                showSnackbar(R.string.invalid_token)
            }
            return
        }

        val code = codes.currentCode
        if (code.isNullOrEmpty()) {
            if (token.encryptionType == EncryptionType.ENCRYPTED && !tokenCryptoManager.isUnlocked()) {
                viewModel.onSessionStart()
            } else {
                showSnackbar(R.string.invalid_token)
            }
            return
        }

        if (token.tokenType == OtpTokenType.HOTP) {
            otpTokenDatabase.otpTokenDao().incrementCounter(token.id)
        }

        if (copyMode == CopyToClipboardMode.ON_REVEAL) {
            clipboardUtil.copySensitiveText(null, code)
            if (ClipboardUtil.shouldShowAppCopyFeedback()) {
                showSnackbar(R.string.code_copied)
            }
        }

        viewModel.putActiveTokenCode(token.id, codes)
    }

    private fun handleShareTokenToOtherApps(token: OtpToken) {
        AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
            if (!tokenCryptoManager.isUnlocked()) {
                isSessionAuthenticated()
                return@authenticateForSensitiveAction
            }
            try {
                val plainToken = tokenCryptoManager.decryptToken(token)
                val shareIntent = Intent(Intent.ACTION_VIEW, OtpTokenFactory.toUri(plainToken)).apply {
                    putExtra(SHARE_FROM_PACKAGE_NAME_INTENT_EXTRA, packageName)
                }
                startActivity(Intent.createChooser(shareIntent, null))
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Failed decrypting token for sharing", e)
                showSnackbar(R.string.invalid_token)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Token vault locked before sharing token", e)
                if (!tokenCryptoManager.isUnlocked()) {
                    isSessionAuthenticated()
                } else {
                    showSnackbar(R.string.invalid_token)
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid token URI for sharing", e)
                showSnackbar(R.string.invalid_token)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "No activity available to share token", e)
                showSnackbar(R.string.launch_file_browser_failure)
            }
        }
    }

    private fun handleRevealTokenSecret(token: OtpToken, onRevealed: (String) -> Unit) {
        AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
            if (!tokenCryptoManager.isUnlocked()) {
                isSessionAuthenticated()
                return@authenticateForSensitiveAction
            }
            try {
                val plaintextSecret = tokenCryptoManager.decryptSecret(token)
                onRevealed(plaintextSecret)
            } catch (e: GeneralSecurityException) {
                Log.e(TAG, "Failed decrypting token secret", e)
                showSnackbar(R.string.invalid_token)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Token vault locked before secret could be revealed", e)
                if (!tokenCryptoManager.isUnlocked()) {
                    isSessionAuthenticated()
                } else {
                    showSnackbar(R.string.invalid_token)
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid token secret state", e)
                showSnackbar(R.string.invalid_token)
            }
        }
    }

    internal fun requestManualKeyVerification(onVerified: () -> Unit) {
        activeTransientDialog = TransientDialogState.VerifyKeyPrompt(
            titleRes = R.string.verify_encryption_key_title,
            messageRes = R.string.verify_encryption_key_message,
            positiveButtonRes = R.string.ok_text,
        ) { chars ->
            if (tokenCryptoManager.unlockWithPassphrase(chars)) {
                activeTransientDialog = null
                if (!isFinishing && !isDestroyed) {
                    onVerified()
                }
                null
            } else {
                getString(R.string.incorrect_encryption_key)
            }
        }
    }

    private fun applyThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
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
        settings.refreshFlows()
        settings.sharedPreferences.registerOnSharedPreferenceChangeListener(authModePrefListener)
        viewModel.onSessionStart()
    }

    override fun onStop() {
        settings.selectedCategory = viewModel.getCurrentCategoryFilter()
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
        dismissTransientDialog()
        dismissAuthDialog()
        shareQrTokenId = null
        deleteTokenId = null
        finish()
        viewModel.lockSession()
    }

    internal fun clearActiveTokenCodes() {
        viewModel.clearActiveTokenCodes()
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

    internal fun onQrCodeScanned(uri: Uri) {
        if (!isSessionAuthenticated()) {
            pendingDeepLinkUri = uri
            verifyAuthentication()
            return
        }
        showAddTokenDeepLinkConfirmation(uri)
    }

    internal fun pasteKeyUriFromClipboard() {
        if (!isSessionAuthenticated()) {
            verifyAuthentication()
            return
        }
        val rawText = clipboardUtil.readPlainText()?.trim()
        if (rawText.isNullOrEmpty() || rawText.contains('\n') || rawText.contains('\r')) {
            showSnackbar(R.string.invalid_clipboard_key_uri)
            return
        }
        showAddTokenDeepLinkConfirmation(
            uri = Uri.parse(rawText),
            invalidUriMessageRes = R.string.invalid_clipboard_key_uri,
        )
    }

    private fun showAddTokenDeepLinkConfirmation(
        uri: Uri,
        @StringRes invalidUriMessageRes: Int = R.string.invalid_token_uri_received,
    ) {
        val token = try {
            OtpTokenFactory.createFromUri(uri)
        } catch (_: Exception) {
            showSnackbar(invalidUriMessageRes)
            return
        }

        activeTransientDialog = TransientDialogState.DeepLinkAddToken(
            uri = uri,
            token = token,
        )
    }

    private fun confirmDeepLinkAddToken(uri: Uri, token: OtpToken) {
        if (!isSessionAuthenticated()) {
            pendingDeepLinkUri = uri
            verifyAuthentication()
            return
        }
        lifecycleScope.launch {
            try {
                val insertedId = otpTokenDatabase.otpTokenDao().insertAtTop(
                    tokenCryptoManager.encryptTokenForStorage(token)
                )
                viewModel.onTokenAdded(insertedId, token)
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

    internal fun startImportFreeOtpBackup() {
        if (!isSessionAuthenticated()) return
        launchSystemPicker { openFreeOtpBackupLauncher.launch(arrayOf("*/*")) }
    }

    internal fun startImportAegisJson() {
        if (!isSessionAuthenticated()) return
        launchSystemPicker { openAegisJsonLauncher.launch(arrayOf("application/json", "*/*")) }
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
        activeTransientDialog = TransientDialogState.SortConfirm(order)
    }

    internal fun handleExportAction(isJson: Boolean) {
        if (!isSessionAuthenticated()) return
        AuthenticationUtil.authenticateForSensitiveAction(this, settings, tokenCryptoManager) {
            if (settings.authMode == AuthMode.TOKEN_ENCRYPTED) {
                activeTransientDialog = TransientDialogState.ExportEncryptionChoice(isJson)
            } else {
                pendingExportEncrypted = false
                launchExportCreateFile(isJson)
            }
        }
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
                lifecycleScope.launch {
                    try {
                        val inspection = importFromUtil.inspectJsonFile(uri)
                        activeTransientDialog = TransientDialogState.BackupImport(
                            requiresPassword = inspection.requiresPassword,
                            titleRes = R.string.import_json_file,
                            encryptedMessageRes = R.string.import_json_encrypted_message,
                            unencryptedMessageRes = R.string.import_json_unencrypted_message,
                            passwordHintRes = R.string.encryption_key_hint,
                            incorrectPasswordRes = R.string.incorrect_encryption_key,
                            failedSnackbarRes = R.string.import_json_failed_text,
                            logLabel = "FreeOTP+ JSON",
                        ) { passphrase, selectedMode ->
                            importFromUtil.importJsonFile(
                                uri = uri,
                                backupPassphrase = passphrase,
                                mode = selectedMode,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Inspect JSON failed", e)
                        showSnackbar(R.string.import_json_failed_text)
                    }
                }
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
                            showImportEncryptedKeyUriDialog(uri)
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

            DocumentAction.IMPORT_FREEOTP_BACKUP -> {
                lifecycleScope.launch {
                    try {
                        val inspection = importFromUtil.inspectFreeOtpBackup(uri)
                        activeTransientDialog = TransientDialogState.BackupImport(
                            requiresPassword = inspection.requiresPassword,
                            titleRes = R.string.import_freeotp_backup_title,
                            encryptedMessageRes = R.string.import_freeotp_backup_message,
                            unencryptedMessageRes = R.string.import_freeotp_unencrypted_backup_message,
                            passwordHintRes = R.string.freeotp_backup_password_hint,
                            incorrectPasswordRes = R.string.incorrect_freeotp_backup_password,
                            failedSnackbarRes = R.string.import_freeotp_backup_failed_text,
                            logLabel = "FreeOTP backup",
                        ) { passphrase, selectedMode ->
                            importFromUtil.importFreeOtpBackup(
                                fileUri = uri,
                                backupPassphrase = passphrase,
                                mode = selectedMode,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Inspect FreeOTP backup failed", e)
                        showSnackbar(R.string.import_freeotp_backup_failed_text)
                    }
                }
            }

            DocumentAction.IMPORT_AEGIS_JSON -> {
                lifecycleScope.launch {
                    try {
                        val inspection = importFromUtil.inspectAegisBackup(uri)
                        activeTransientDialog = TransientDialogState.BackupImport(
                            requiresPassword = inspection.requiresPassword,
                            titleRes = R.string.import_aegis_json_title,
                            encryptedMessageRes = R.string.import_aegis_encrypted_message,
                            unencryptedMessageRes = R.string.import_aegis_unencrypted_message,
                            passwordHintRes = R.string.freeotp_backup_password_hint,
                            incorrectPasswordRes = R.string.incorrect_freeotp_backup_password,
                            failedSnackbarRes = R.string.import_aegis_failed_text,
                            logLabel = "Aegis JSON",
                        ) { passphrase, selectedMode ->
                            importFromUtil.importAegisBackup(
                                fileUri = uri,
                                backupPassphrase = passphrase,
                                mode = selectedMode,
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Inspect Aegis backup failed", e)
                        showSnackbar(R.string.import_aegis_failed_text)
                    }
                }
            }
        }
    }

    private fun showImportEncryptedKeyUriDialog(uri: Uri) {
        activeTransientDialog = TransientDialogState.VerifyKeyPrompt(
            titleRes = R.string.import_encrypted_backup_title,
            messageRes = R.string.import_encrypted_backup_message,
            positiveButtonRes = R.string.import_text,
        ) { passphrase ->
            try {
                importFromUtil.importKeyUriFile(uri, passphrase)
                Glide.get(this@MainActivity).clearMemory()
                viewModel.notifyTokenImagesChanged()
                activeTransientDialog = null
                showSnackbar(R.string.import_succeeded_text)
                null
            } catch (_: InvalidEncryptionKeyException) {
                getString(R.string.incorrect_encryption_key)
            } catch (e: Exception) {
                Log.e(TAG, "Encrypted Key URI import failed", e)
                activeTransientDialog = null
                showSnackbar(R.string.import_key_uri_failed_text)
                null
            }
        }
    }

    internal fun selectAuthenticationMode(selectedMode: AuthMode) {
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
                            activeTransientDialog = TransientDialogState.SetEncryptionKey
                        },
                        onNoDeviceCredential = {
                            isAuthPromptInProgress = false
                            activeTransientDialog = TransientDialogState.SetEncryptionKey
                        },
                        onCancelOrError = { code, msg ->
                            isAuthPromptInProgress = false
                            showNonFatalAuthErrorIfNeeded(code, msg)
                        },
                    )
                } else {
                    activeTransientDialog = TransientDialogState.SetEncryptionKey
                }
            }

            currentMode == AuthMode.TOKEN_ENCRYPTED -> {
                if (targetMode == AuthMode.APP_LAUNCH && !hasAppLaunchCredential) {
                    activeTransientDialog = TransientDialogState.ScreenLockRequired
                } else {
                    activeTransientDialog = TransientDialogState.DisableEncryptionWarning(targetMode)
                }
            }

            targetMode == AuthMode.APP_LAUNCH -> {
                if (!hasAppLaunchCredential) {
                    activeTransientDialog = TransientDialogState.ScreenLockRequired
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
                    },
                    onNoDeviceCredential = {
                        isAuthPromptInProgress = false
                        activeTransientDialog = TransientDialogState.ScreenLockRequired
                    },
                    onCancelOrError = { code, msg ->
                        isAuthPromptInProgress = false
                        showNonFatalAuthErrorIfNeeded(code, msg)
                    },
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
                    },
                    onNoDeviceCredential = {
                        isAuthPromptInProgress = false
                        disableAppLaunchWhenScreenLockMissing()
                    },
                    onCancelOrError = { code, msg ->
                        isAuthPromptInProgress = false
                        showNonFatalAuthErrorIfNeeded(code, msg)
                    },
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
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun openSecuritySettings() {
        val candidateIntents = listOf(
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD),
            Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS),
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

    private fun disableAppLaunchWhenScreenLockMissing() {
        settings.authMode = AuthMode.NONE
        onAuthCompleted()
        showSnackbar(R.string.app_launch_auth_disabled)
    }

    private suspend fun enableManualOnlyEncryption(
        setupMaterial: TokenCryptoManager.SetupMaterial,
    ) {
        try {
            appSessionManager.prepareAuthenticatedSession()
            tokenCryptoManager.enableTokenEncryption(setupMaterial, null)
            activeTransientDialog = null
            settings.refreshFlows()
            onAuthCompleted()
            showSnackbar(R.string.token_encryption_enabled_manual_only, SnackbarDuration.Long)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed enabling token encryption", e)
            activeTransientDialog = null
            showSnackbar(R.string.authentication_failed)
        } finally {
            setupMaterial.clear()
        }
    }

    private suspend fun completeTokenEncryptionSetup(
        setupMaterial: TokenCryptoManager.SetupMaterial,
    ) {
        if (!AuthenticationUtil.canAuthenticateStrongBiometric(this)) {
            enableManualOnlyEncryption(setupMaterial)
            return
        }

        val encryptCipher = try {
            tokenCryptoManager.createBiometricEncryptCipher()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to initialize biometric encryption cipher, falling back to manual key", e)
            enableManualOnlyEncryption(setupMaterial)
            return
        }

        activeTransientDialog = null
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
                            setupMaterial.keyBytes,
                        )
                        appSessionManager.prepareAuthenticatedSession()
                        tokenCryptoManager.enableTokenEncryption(setupMaterial, wrappedBlob)
                        settings.enableBiometricUnlock = true
                        settings.refreshFlows()
                        onAuthCompleted()
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
            },
        )
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
                },
            )
        } else {
            showVerifyKeyToDisableDialog(targetMode)
        }
    }

    private fun showVerifyKeyToDisableDialog(targetMode: AuthMode) {
        activeTransientDialog = TransientDialogState.VerifyKeyPrompt(
            titleRes = R.string.verify_encryption_key_title,
            messageRes = R.string.verify_encryption_key_message,
            positiveButtonRes = R.string.ok_text,
        ) { chars ->
            if (tokenCryptoManager.unlockWithPassphrase(chars)) {
                try {
                    appSessionManager.prepareAuthenticatedSession()
                    tokenCryptoManager.disableTokenEncryption(targetMode)
                    settings.refreshFlows()
                    activeTransientDialog = null
                    viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
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
    }

    private fun performDisableEncryption(targetMode: AuthMode) {
        lifecycleScope.launch {
            try {
                appSessionManager.prepareAuthenticatedSession()
                tokenCryptoManager.disableTokenEncryption(targetMode)
                settings.refreshFlows()
                viewModel.setAuthState(MainViewModel.AuthState.AUTHENTICATED)
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
        activeAuthGateDialog = AuthGateDialogState.None
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
                if (activeAuthGateDialog is AuthGateDialogState.AppLaunchRetry) {
                    dismissAuthDialog()
                }
            } else {
                if (activeAuthGateDialog is AuthGateDialogState.AppLaunchNoScreenLock) {
                    dismissAuthDialog()
                }
                if (pendingAppLaunchRetryDialog &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    val msg = pendingAppLaunchRetryMessage
                    pendingAppLaunchRetryDialog = false
                    pendingAppLaunchRetryMessage = null
                    activeAuthGateDialog = AuthGateDialogState.AppLaunchRetry(msg)
                    return
                }
            }
        } else {
            pendingAppLaunchRetryDialog = false
            pendingAppLaunchRetryMessage = null
        }

        if (isAuthPromptInProgress || activeAuthGateDialog != AuthGateDialogState.None) {
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
                        activeAuthGateDialog = AuthGateDialogState.AppLaunchNoScreenLock
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
                        activeAuthGateDialog = AuthGateDialogState.AppLaunchRetry(message)
                    },
                )
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
                },
            )
        } else {
            val wasInvalidated = prep.status == TokenCryptoManager.BiometricKeyStatus.INVALIDATED ||
                tokenCryptoManager.wasBiometricInvalidated()
            showManualKeyUnlockDialog(isLaunchGate = true, biometricInvalidated = wasInvalidated)
        }
    }

    private fun showManualKeyUnlockDialog(
        isLaunchGate: Boolean,
        biometricInvalidated: Boolean,
    ) {
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

        activeAuthGateDialog = AuthGateDialogState.ManualKeyUnlock(
            isLaunchGate = isLaunchGate,
            biometricInvalidated = biometricInvalidated,
            canOfferBiometric = canOfferBiometric,
            initialBiometricChecked = settings.enableBiometricUnlock,
            biometricCheckboxLabelRes = biometricLabelRes,
            messageRes = messageRes,
        )
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
            },
        )
    }

    private fun onAuthCompleted() {
        pendingAppLaunchRetryDialog = false
        pendingAppLaunchRetryMessage = null
        settings.refreshFlows()
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
