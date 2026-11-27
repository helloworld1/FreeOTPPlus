package org.fedorahosted.freeotp.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.TokenSortOrder
import org.fedorahosted.freeotp.ui.about.AboutScreen
import org.fedorahosted.freeotp.ui.addedit.AddTokenScreen
import org.fedorahosted.freeotp.ui.addedit.AddTokenViewModel
import org.fedorahosted.freeotp.ui.addedit.EditTokenScreen
import org.fedorahosted.freeotp.ui.addedit.EditTokenViewModel
import org.fedorahosted.freeotp.ui.components.DialogWindowRegistry
import org.fedorahosted.freeotp.ui.components.LocalDialogWindowRegistry
import org.fedorahosted.freeotp.ui.dialogs.DeleteTokenDialog
import org.fedorahosted.freeotp.ui.dialogs.ShareQrCodeDialog
import org.fedorahosted.freeotp.ui.navigation.FreeOtpDestination
import org.fedorahosted.freeotp.ui.scan.ScanTokenScreen
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.ui.tokenlist.TokenListScreen
import org.fedorahosted.freeotp.util.CopyToClipboardMode
import org.fedorahosted.freeotp.util.ThemeMode
import org.fedorahosted.freeotp.util.TokenCodeGrouping
import org.fedorahosted.freeotp.util.TokenQRCodeDecoder
import org.fedorahosted.freeotp.util.TokenTimerStyle
import java.util.concurrent.ExecutorService

@Composable
fun FreeOtpApp(
    viewModel: MainViewModel,
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    dialogWindowRegistry: DialogWindowRegistry,
    otpTokenDatabase: OtpTokenDatabase,
    tokenCryptoManager: TokenCryptoManager,
    tokenQRCodeDecoder: TokenQRCodeDecoder,
    executorService: ExecutorService,
    shareQrTokenId: Long?,
    deleteTokenId: Long?,
    onDismissShareQrDialog: () -> Unit,
    onDismissDeleteTokenDialog: () -> Unit,
    onOpenShareQrDialog: (OtpToken) -> Unit,
    onOpenDeleteTokenDialog: (OtpToken) -> Unit,
    onTokenCardClick: (OtpToken) -> Unit,
    onAdvanceHotp: (OtpToken) -> Unit,
    onShareToOtherApps: (OtpToken) -> Unit,
    onRequestRevealSecret: (OtpToken, (String) -> Unit) -> Unit,
    onCopySensitiveSecret: (String) -> Unit,
    onCopyShareQrUri: (String) -> Unit,
    onShowSnackbar: (Int) -> Unit,
    onVaultLocked: () -> Unit,
    isSessionAuthenticated: () -> Boolean,
    onQrCodeScanned: (Uri) -> Unit,
    onPasteKeyUri: () -> Unit,
    onImportJson: () -> Unit,
    onImportKeyUri: () -> Unit,
    onImportTokenImages: () -> Unit,
    onImportFreeOtpBackup: () -> Unit,
    onImportAegisJson: () -> Unit,
    onExportJson: () -> Unit,
    onExportKeyUri: () -> Unit,
    onExportTokenImages: () -> Unit,
    onRequestSort: (TokenSortOrder) -> Unit,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSelectTokenTimerStyle: (TokenTimerStyle) -> Unit,
    onSelectTokenCodeGrouping: (TokenCodeGrouping) -> Unit,
    onSelectAuthMode: (AuthMode) -> Unit,
    onSelectCopyToClipboardMode: (CopyToClipboardMode) -> Unit,
    onQuitAndLock: () -> Unit,
    dialogHostContent: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val authState by viewModel.authStateFlow.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeModeFlow.collectAsStateWithLifecycle()
    val tokenTimerStyle by viewModel.tokenTimerStyleFlow.collectAsStateWithLifecycle()
    val tokenCodeGrouping by viewModel.tokenCodeGroupingFlow.collectAsStateWithLifecycle()
    val authMode by viewModel.authModeFlow.collectAsStateWithLifecycle()
    val copyToClipboardMode by viewModel.copyToClipboardModeFlow.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQueryFlow.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.categoryFilterFlow.collectAsStateWithLifecycle()
    val imageRefreshVersion by viewModel.imageRefreshVersionFlow.collectAsStateWithLifecycle()
    val activeTokenCodes by viewModel.activeTokenCodesFlow.collectAsStateWithLifecycle()
    val scrollToTokenRequest by viewModel.scrollToTokenRequestFlow.collectAsStateWithLifecycle()

    val listState by viewModel.getTokenListState().collectAsStateWithLifecycle(
        initialValue = MainViewModel.TokenListState(
            tokens = emptyList(),
            hasInstalledTokens = false,
            hasActiveFilterOrSearch = false,
            isAuthenticated = authState == MainViewModel.AuthState.AUTHENTICATED,
        ),
    )
    val availableCategories by viewModel.getAllCategories().collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )

    LaunchedEffect(imageRefreshVersion) {
        if (imageRefreshVersion > 0L) {
            Glide.get(context).clearMemory()
        }
    }

    // Fail-closed lock cleanup: pop child routes and dismiss token dialogs whenever session locks
    LaunchedEffect(authState) {
        if (authState == MainViewModel.AuthState.UNAUTHENTICATED) {
            onDismissShareQrDialog()
            onDismissDeleteTokenDialog()
            snackbarHostState.currentSnackbarData?.dismiss()
            if (navController.currentDestination?.route != FreeOtpDestination.TOKEN_LIST) {
                navController.popBackStack(FreeOtpDestination.TOKEN_LIST, inclusive = false)
            }
        }
    }

    CompositionLocalProvider(LocalDialogWindowRegistry provides dialogWindowRegistry) {
        FreeOtpTheme(themeMode = themeMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                if (authState == MainViewModel.AuthState.AUTHENTICATED) {
                    NavHost(
                        navController = navController,
                        startDestination = FreeOtpDestination.TOKEN_LIST,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable(FreeOtpDestination.TOKEN_LIST) {
                            TokenListScreen(
                                listState = listState,
                                searchQuery = searchQuery,
                                selectedCategory = selectedCategory,
                                availableCategories = availableCategories,
                                activeTokenCodes = activeTokenCodes,
                                imageRefreshVersion = imageRefreshVersion,
                                themeMode = themeMode,
                                authMode = authMode,
                                copyToClipboardMode = copyToClipboardMode,
                                snackbarHostState = snackbarHostState,
                                onSearchQueryChange = viewModel::setTokenSearchQuery,
                                onSelectCategory = viewModel::setCategoryFilter,
                                onTokenCardClick = onTokenCardClick,
                                onAdvanceHotp = onAdvanceHotp,
                                onTokenCodeExpired = viewModel::removeActiveTokenCode,
                                onCommitTokenReorder = viewModel::moveTokenPairs,
                                onEditToken = { token ->
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    navController.navigate(FreeOtpDestination.editToken(token.id)) {
                                        launchSingleTop = true
                                    }
                                },
                                onDeleteToken = onOpenDeleteTokenDialog,
                                onShareToOtherApps = onShareToOtherApps,
                                onShareQrCode = onOpenShareQrDialog,
                                onNavigateToScanQr = {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    navController.navigate(FreeOtpDestination.SCAN_TOKEN) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToAddToken = {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    navController.navigate(FreeOtpDestination.ADD_TOKEN) {
                                        launchSingleTop = true
                                    }
                                },
                                onPasteKeyUri = onPasteKeyUri,
                                onNavigateToAbout = {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    navController.navigate(FreeOtpDestination.ABOUT) {
                                        launchSingleTop = true
                                    }
                                },
                                onImportJson = onImportJson,
                                onImportKeyUri = onImportKeyUri,
                                onImportTokenImages = onImportTokenImages,
                                onImportFreeOtpBackup = onImportFreeOtpBackup,
                                onImportAegisJson = onImportAegisJson,
                                onExportJson = onExportJson,
                                onExportKeyUri = onExportKeyUri,
                                onExportTokenImages = onExportTokenImages,
                                onRequestSort = onRequestSort,
                                onSelectThemeMode = onSelectThemeMode,
                                tokenTimerStyle = tokenTimerStyle,
                                onSelectTokenTimerStyle = onSelectTokenTimerStyle,
                                tokenCodeGrouping = tokenCodeGrouping,
                                onSelectTokenCodeGrouping = onSelectTokenCodeGrouping,
                                onSelectAuthMode = onSelectAuthMode,
                                onSelectCopyToClipboardMode = onSelectCopyToClipboardMode,
                                onQuitAndLock = onQuitAndLock,
                                scrollToTokenRequest = scrollToTokenRequest,
                                onConsumeScrollToTokenRequest = viewModel::consumeScrollToTokenRequest,
                            )
                        }

                        composable(FreeOtpDestination.ADD_TOKEN) {
                            val addViewModel: AddTokenViewModel = hiltViewModel()
                            AddTokenScreen(
                                viewModel = addViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onVaultLocked = onVaultLocked,
                                onShowSnackbar = onShowSnackbar,
                                onTokenAdded = viewModel::onTokenAdded,
                            )
                        }

                        composable(
                            route = FreeOtpDestination.EDIT_TOKEN,
                            arguments = listOf(
                                navArgument(FreeOtpDestination.ARG_TOKEN_ID) {
                                    type = NavType.LongType
                                }
                            ),
                        ) { backStackEntry ->
                            val tokenId = backStackEntry.arguments?.getLong(FreeOtpDestination.ARG_TOKEN_ID) ?: 0L
                            val editViewModel: EditTokenViewModel = hiltViewModel()
                            EditTokenScreen(
                                tokenId = tokenId,
                                viewModel = editViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onRequestRevealSecret = onRequestRevealSecret,
                                onCopySensitiveSecret = onCopySensitiveSecret,
                            )
                        }

                        composable(FreeOtpDestination.SCAN_TOKEN) {
                            ScanTokenScreen(
                                tokenQRCodeDecoder = tokenQRCodeDecoder,
                                executorService = executorService,
                                onNavigateBack = {
                                    if (navController.currentDestination?.route == FreeOtpDestination.SCAN_TOKEN) {
                                        navController.popBackStack()
                                    }
                                },
                                onQrScanned = onQrCodeScanned,
                            )
                        }

                        composable(FreeOtpDestination.ABOUT) {
                            AboutScreen(
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                    // Token-scoped dialogs (only composed while session is authenticated)
                    if (shareQrTokenId != null) {
                        val token by otpTokenDatabase.otpTokenDao().get(shareQrTokenId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val activeToken = token
                        if (activeToken != null) {
                            ShareQrCodeDialog(
                                storedToken = activeToken,
                                tokenCryptoManager = tokenCryptoManager,
                                isSessionAuthenticated = isSessionAuthenticated,
                                onCopyUri = onCopyShareQrUri,
                                onInvalidTokenError = { onShowSnackbar(R.string.invalid_token) },
                                onDismiss = onDismissShareQrDialog,
                            )
                        }
                    }

                    if (deleteTokenId != null) {
                        val token by otpTokenDatabase.otpTokenDao().get(deleteTokenId)
                            .collectAsStateWithLifecycle(initialValue = null)
                        val activeToken = token
                        if (activeToken != null) {
                            val coroutineScope = rememberCoroutineScope()
                            DeleteTokenDialog(
                                token = activeToken,
                                imageRefreshVersion = imageRefreshVersion,
                                onDismiss = onDismissDeleteTokenDialog,
                                onConfirmDelete = {
                                    coroutineScope.launch(Dispatchers.Main.immediate) {
                                        viewModel.removeActiveTokenCode(activeToken.id)
                                        otpTokenDatabase.otpTokenDao().deleteById(activeToken.id)
                                        onDismissDeleteTokenDialog()
                                    }
                                },
                            )
                        }
                    }
                }

                // Activity-level auth, import/export, sort, and deep-link dialogs
                dialogHostContent()
            }
        }
    }
}
