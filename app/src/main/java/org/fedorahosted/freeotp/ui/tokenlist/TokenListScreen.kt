package org.fedorahosted.freeotp.ui.tokenlist

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenSortOrder
import org.fedorahosted.freeotp.ui.MainViewModel
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

private enum class OverflowSubMenu {
    ROOT,
    IMPORT,
    EXPORT,
    SORT,
    THEME,
    AUTH_MODE,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokenListScreen(
    listState: MainViewModel.TokenListState,
    searchQuery: String,
    selectedCategory: String?,
    availableCategories: List<String>,
    activeTokenCodes: Map<Long, MainViewModel.ActiveTokenCodeEntry>,
    imageRefreshVersion: Long,
    themeMode: ThemeMode,
    authMode: AuthMode,
    copyToClipboard: Boolean,
    snackbarHostState: SnackbarHostState,
    onSearchQueryChange: (String) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onTokenCardClick: (OtpToken) -> Unit,
    onTokenCodeExpired: (Long) -> Unit,
    onCommitTokenReorder: (List<Pair<Long, Long>>) -> Unit,
    onEditToken: (OtpToken) -> Unit,
    onDeleteToken: (OtpToken) -> Unit,
    onShareToOtherApps: (OtpToken) -> Unit,
    onShareQrCode: (OtpToken) -> Unit,
    onNavigateToScanQr: () -> Unit,
    onNavigateToAddToken: () -> Unit,
    onNavigateToAbout: () -> Unit,
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
    onSelectAuthMode: (AuthMode) -> Unit,
    onToggleCopyToClipboard: () -> Unit,
    onQuitAndLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableGridState(
        tokens = listState.tokens,
        gridState = gridState,
        onCommitMovePairs = onCommitTokenReorder,
    )

    // Scroll to index 0 when new tokens are prepended (added/imported) without changing search/category filter
    var previousFilterKey by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var previousTopTokenId by remember { mutableStateOf<Long?>(null) }
    var previousTokenCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState.tokens, searchQuery, selectedCategory) {
        val currentFilterKey = searchQuery to selectedCategory
        val topId = listState.tokens.firstOrNull()?.id
        val currentCount = listState.tokens.size
        val shouldScrollToTop = previousFilterKey == currentFilterKey &&
            previousTopTokenId != null &&
            topId != null &&
            topId != previousTopTokenId &&
            currentCount > previousTokenCount
        previousFilterKey = currentFilterKey
        previousTopTokenId = topId
        previousTokenCount = currentCount
        if (shouldScrollToTop) {
            gridState.scrollToItem(0)
        }
    }

    var isSearchExpanded by rememberSaveable { mutableStateOf(searchQuery.isNotEmpty()) }
    var requestSearchFocus by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var currentSubMenu by remember { mutableStateOf(OverflowSubMenu.ROOT) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            isSearchExpanded = true
        }
    }

    LaunchedEffect(isSearchExpanded, requestSearchFocus) {
        if (isSearchExpanded && requestSearchFocus) {
            requestSearchFocus = false
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    BackHandler(enabled = listState.isAuthenticated && isSearchExpanded) {
        onSearchQueryChange("")
        isSearchExpanded = false
    }

    val closeMenu = {
        menuExpanded = false
        currentSubMenu = OverflowSubMenu.ROOT
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (listState.isAuthenticated && isSearchExpanded) {
                        IconButton(
                            onClick = {
                                onSearchQueryChange("")
                                isSearchExpanded = false
                            },
                            modifier = Modifier.testTag(UiTestTags.SEARCH_COLLAPSE_BUTTON),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.arrow_back_24px),
                                contentDescription = stringResource(R.string.cancel_text),
                            )
                        }
                    }
                },
                title = {
                    if (listState.isAuthenticated && isSearchExpanded) {
                        TextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            placeholder = {
                                Text(
                                    text = stringResource(android.R.string.search_go),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingIcon = if (searchQuery.isNotEmpty()) {
                                {
                                    IconButton(
                                        onClick = { onSearchQueryChange("") },
                                        modifier = Modifier.testTag(UiTestTags.SEARCH_CLEAR_BUTTON),
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_close_24px),
                                            contentDescription = stringResource(R.string.cancel_text),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .testTag(UiTestTags.SEARCH_FIELD),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                actions = {
                    if (listState.isAuthenticated) {
                        if (!isSearchExpanded) {
                            IconButton(
                                onClick = {
                                    isSearchExpanded = true
                                    requestSearchFocus = true
                                },
                                modifier = Modifier.testTag(UiTestTags.SEARCH_BUTTON),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_search_24px),
                                    contentDescription = stringResource(android.R.string.search_go),
                                )
                            }
                        }

                        val isFilterActive = selectedCategory != null
                        Box {
                            IconButton(
                                onClick = { filterMenuExpanded = true },
                                modifier = Modifier
                                    .semantics { selected = isFilterActive }
                                    .testTag(UiTestTags.CATEGORY_FILTER_BUTTON),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.filter_list_24px),
                                    contentDescription = stringResource(R.string.category),
                                    tint = if (isFilterActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }

                            CategoryFilterDropdownMenu(
                                expanded = filterMenuExpanded,
                                selectedCategory = selectedCategory,
                                availableCategories = availableCategories,
                                onSelectCategory = { category ->
                                    filterMenuExpanded = false
                                    onSelectCategory(category)
                                },
                                onDismiss = { filterMenuExpanded = false },
                            )
                        }

                        if (authMode != AuthMode.NONE && !isSearchExpanded) {
                            IconButton(
                                onClick = onQuitAndLock,
                                modifier = Modifier.testTag(UiTestTags.QUIT_AND_LOCK_BUTTON),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.lock_24px),
                                    contentDescription = stringResource(R.string.quit_and_lock),
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = {
                                    currentSubMenu = OverflowSubMenu.ROOT
                                    menuExpanded = true
                                },
                                modifier = Modifier.testTag(UiTestTags.OVERFLOW_MENU_BUTTON),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.more_vert_24px),
                                    contentDescription = null,
                                )
                            }

                            TokenListOverflowMenu(
                                expanded = menuExpanded,
                                currentSubMenu = currentSubMenu,
                                themeMode = themeMode,
                                authMode = authMode,
                                copyToClipboard = copyToClipboard,
                                onSubMenuChange = { currentSubMenu = it },
                                onDismiss = closeMenu,
                                onNavigateToScanQr = {
                                    closeMenu()
                                    onNavigateToScanQr()
                                },
                                onNavigateToAddToken = {
                                    closeMenu()
                                    onNavigateToAddToken()
                                },
                                onImportJson = {
                                    closeMenu()
                                    onImportJson()
                                },
                                onImportKeyUri = {
                                    closeMenu()
                                    onImportKeyUri()
                                },
                                onImportTokenImages = {
                                    closeMenu()
                                    onImportTokenImages()
                                },
                                onImportFreeOtpBackup = {
                                    closeMenu()
                                    onImportFreeOtpBackup()
                                },
                                onImportAegisJson = {
                                    closeMenu()
                                    onImportAegisJson()
                                },
                                onExportJson = {
                                    closeMenu()
                                    onExportJson()
                                },
                                onExportKeyUri = {
                                    closeMenu()
                                    onExportKeyUri()
                                },
                                onExportTokenImages = {
                                    closeMenu()
                                    onExportTokenImages()
                                },
                                onRequestSort = { order ->
                                    closeMenu()
                                    onRequestSort(order)
                                },
                                onSelectThemeMode = { mode ->
                                    closeMenu()
                                    onSelectThemeMode(mode)
                                },
                                onSelectAuthMode = { mode ->
                                    closeMenu()
                                    onSelectAuthMode(mode)
                                },
                                onToggleCopyToClipboard = {
                                    onToggleCopyToClipboard()
                                    closeMenu()
                                },
                                onNavigateToAbout = {
                                    closeMenu()
                                    onNavigateToAbout()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.testTag(UiTestTags.TOP_APP_BAR),
            )
        },
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        },
        floatingActionButton = {
            if (listState.isAuthenticated) {
                FloatingActionButton(
                    onClick = onNavigateToScanQr,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .testTag(UiTestTags.SCAN_FAB),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_24px),
                        contentDescription = stringResource(R.string.scan_qr_code),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        if (!listState.isAuthenticated) {
            return@Scaffold
        }

        val displayedTokens = reorderState.displayedTokens
        if (displayedTokens.isEmpty()) {
            val emptyMessageRes = if (listState.hasInstalledTokens || listState.hasActiveFilterOrSearch) {
                R.string.no_matching_tokens
            } else {
                R.string.no_keys
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(emptyMessageRes),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag(UiTestTags.EMPTY_VIEW),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 320.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = 12.dp,
                    top = 6.dp,
                    end = 12.dp,
                    bottom = 96.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .reorderableTokenGrid(reorderState)
                    .testTag(UiTestTags.TOKEN_LIST),
            ) {
                items(
                    items = displayedTokens,
                    key = { it.id },
                ) { token ->
                    val isDragging = reorderState.draggedTokenId == token.id
                    val isManipulated = reorderState.isItemManipulated(token.id)
                    val dragScale by animateFloatAsState(
                        targetValue = if (isDragging) 1.03f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tokenDragScale",
                    )
                    val dragElevation by animateDpAsState(
                        targetValue = if (isDragging) 10.dp else 0.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                        label = "tokenDragElevation",
                    )
                    TokenCard(
                        token = token,
                        activeEntry = activeTokenCodes[token.id],
                        imageRefreshVersion = imageRefreshVersion,
                        isDragging = isDragging,
                        onCardClick = onTokenCardClick,
                        onCodeExpired = onTokenCodeExpired,
                        onEdit = onEditToken,
                        onDelete = onDeleteToken,
                        onShareToOtherApps = onShareToOtherApps,
                        onShareQrCode = onShareQrCode,
                        modifier = Modifier
                            .then(
                                if (!isManipulated) {
                                    Modifier.animateItem(
                                        fadeInSpec = null,
                                        fadeOutSpec = null,
                                        placementSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                            visibilityThreshold = IntOffset.VisibilityThreshold,
                                        ),
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .zIndex(if (isManipulated) 1f else 0f)
                            .graphicsLayer {
                                val translation = reorderState.translationFor(token.id)
                                translationX = translation.x
                                translationY = translation.y
                                scaleX = dragScale
                                scaleY = dragScale
                                shadowElevation = dragElevation.toPx()
                                shape = TokenCardShape
                                clip = false
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterDropdownMenu(
    expanded: Boolean,
    selectedCategory: String?,
    availableCategories: List<String>,
    onSelectCategory: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.CATEGORY_FILTER_MENU),
    ) {
        val allSelected = selectedCategory == null
        CategoryFilterRadioRow(
            label = stringResource(R.string.all_categories),
            selected = allSelected,
            testTag = UiTestTags.CATEGORY_FILTER_ALL,
            onClick = { onSelectCategory(null) },
        )

        val uncatSelected = selectedCategory == MainViewModel.CATEGORY_UNCATEGORIZED
        CategoryFilterRadioRow(
            label = stringResource(R.string.uncategorized),
            selected = uncatSelected,
            testTag = UiTestTags.CATEGORY_FILTER_UNCATEGORIZED,
            onClick = { onSelectCategory(MainViewModel.CATEGORY_UNCATEGORIZED) },
        )

        if (availableCategories.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            availableCategories.forEach { category ->
                val catSelected = selectedCategory != null &&
                    selectedCategory != MainViewModel.CATEGORY_UNCATEGORIZED &&
                    selectedCategory.equals(category, ignoreCase = true)
                CategoryFilterRadioRow(
                    label = category,
                    selected = catSelected,
                    testTag = UiTestTags.categoryFilterItemTag(category),
                    onClick = { onSelectCategory(category) },
                )
            }
        }
    }
}

@Composable
private fun CategoryFilterRadioRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TokenListOverflowMenu(
    expanded: Boolean,
    currentSubMenu: OverflowSubMenu,
    themeMode: ThemeMode,
    authMode: AuthMode,
    copyToClipboard: Boolean,
    onSubMenuChange: (OverflowSubMenu) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToScanQr: () -> Unit,
    onNavigateToAddToken: () -> Unit,
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
    onSelectAuthMode: (AuthMode) -> Unit,
    onToggleCopyToClipboard: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTestTags.OVERFLOW_MENU),
    ) {
        when (currentSubMenu) {
            OverflowSubMenu.ROOT -> {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.scan_qr_code)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_qr_code_24px), contentDescription = null)
                    },
                    onClick = onNavigateToScanQr,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.add_token)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.add_24px), contentDescription = null)
                    },
                    onClick = onNavigateToAddToken,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_text)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.download_24px), contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_chevron_right_24px), contentDescription = null)
                    },
                    onClick = { onSubMenuChange(OverflowSubMenu.IMPORT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.export_text)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.upload_24px), contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_chevron_right_24px), contentDescription = null)
                    },
                    onClick = { onSubMenuChange(OverflowSubMenu.EXPORT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sort)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.sort_24px), contentDescription = null)
                    },
                    trailingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_chevron_right_24px), contentDescription = null)
                    },
                    onClick = { onSubMenuChange(OverflowSubMenu.SORT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.theme)) },
                    trailingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_chevron_right_24px), contentDescription = null)
                    },
                    onClick = { onSubMenuChange(OverflowSubMenu.THEME) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.authentication_mode)) },
                    trailingIcon = {
                        Icon(painter = painterResource(R.drawable.ic_chevron_right_24px), contentDescription = null)
                    },
                    onClick = { onSubMenuChange(OverflowSubMenu.AUTH_MODE) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = copyToClipboard,
                            role = Role.Checkbox,
                            onValueChange = { onToggleCopyToClipboard() },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.copy_to_clipboard),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Checkbox(
                        checked = copyToClipboard,
                        onCheckedChange = null,
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.about_freeotp)) },
                    leadingIcon = {
                        Icon(painter = painterResource(R.drawable.info_24px), contentDescription = null)
                    },
                    onClick = onNavigateToAbout,
                )
            }

            OverflowSubMenu.IMPORT -> {
                SubMenuHeader(
                    titleRes = R.string.import_text,
                    onBack = { onSubMenuChange(OverflowSubMenu.ROOT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_json_format)) },
                    onClick = onImportJson,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_key_uri_format)) },
                    onClick = onImportKeyUri,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_token_images)) },
                    onClick = onImportTokenImages,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_freeotp_backup)) },
                    onClick = onImportFreeOtpBackup,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.import_aegis_json)) },
                    onClick = onImportAegisJson,
                )
            }

            OverflowSubMenu.EXPORT -> {
                SubMenuHeader(
                    titleRes = R.string.export_text,
                    onBack = { onSubMenuChange(OverflowSubMenu.ROOT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.export_json_format)) },
                    onClick = onExportJson,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.export_key_uri_format)) },
                    onClick = onExportKeyUri,
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.export_token_images)) },
                    onClick = onExportTokenImages,
                )
            }

            OverflowSubMenu.SORT -> {
                SubMenuHeader(
                    titleRes = R.string.sort,
                    onBack = { onSubMenuChange(OverflowSubMenu.ROOT) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sort_by_account_asc)) },
                    onClick = { onRequestSort(TokenSortOrder.ACCOUNT_ASC) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sort_by_account_desc)) },
                    onClick = { onRequestSort(TokenSortOrder.ACCOUNT_DESC) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sort_by_issuer_asc)) },
                    onClick = { onRequestSort(TokenSortOrder.ISSUER_ASC) },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.sort_by_issuer_desc)) },
                    onClick = { onRequestSort(TokenSortOrder.ISSUER_DESC) },
                )
            }

            OverflowSubMenu.THEME -> {
                SubMenuHeader(
                    titleRes = R.string.theme,
                    onBack = { onSubMenuChange(OverflowSubMenu.ROOT) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.theme_system,
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onSelectThemeMode(ThemeMode.SYSTEM) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.theme_light,
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onSelectThemeMode(ThemeMode.LIGHT) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.theme_dark,
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onSelectThemeMode(ThemeMode.DARK) },
                )
            }

            OverflowSubMenu.AUTH_MODE -> {
                SubMenuHeader(
                    titleRes = R.string.authentication_mode,
                    onBack = { onSubMenuChange(OverflowSubMenu.ROOT) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.auth_mode_none,
                    selected = authMode == AuthMode.NONE,
                    onClick = { onSelectAuthMode(AuthMode.NONE) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.auth_mode_app_launch,
                    selected = authMode == AuthMode.APP_LAUNCH,
                    onClick = { onSelectAuthMode(AuthMode.APP_LAUNCH) },
                )
                SubMenuRadioItem(
                    titleRes = R.string.auth_mode_token_encrypted,
                    selected = authMode == AuthMode.TOKEN_ENCRYPTED,
                    onClick = { onSelectAuthMode(AuthMode.TOKEN_ENCRYPTED) },
                )
            }
        }
    }
}

@Composable
private fun SubMenuHeader(
    @StringRes titleRes: Int,
    onBack: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(titleRes),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingIcon = {
            Icon(
                painter = painterResource(R.drawable.arrow_back_24px),
                contentDescription = stringResource(R.string.cancel_text),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = onBack,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SubMenuRadioItem(
    @StringRes titleRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TokenListScreenPreview() {
    val tokens = listOf(
        OtpTokenFactory.create(
            type = OtpTokenType.HOTP,
            issuer = "GitHub",
            label = "octocat@github.com",
            secret = "JBSWY3DPEHPK3PXP",
            initialCounter = 5L,
            categories = listOf("Work"),
        ).copy(id = 1L),
        OtpTokenFactory.create(
            type = OtpTokenType.TOTP,
            issuer = "Microsoft",
            label = "alice@microsoft.com",
            secret = "JBSWY3DPEHPK3PXP",
            categories = listOf("Personal"),
        ).copy(id = 2L),
    )
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        TokenListScreen(
            listState = MainViewModel.TokenListState(
                tokens = tokens,
                hasInstalledTokens = true,
                hasActiveFilterOrSearch = false,
                isAuthenticated = true,
            ),
            searchQuery = "",
            selectedCategory = "Work",
            availableCategories = listOf("Personal", "Work"),
            activeTokenCodes = emptyMap(),
            imageRefreshVersion = 0L,
            themeMode = ThemeMode.LIGHT,
            authMode = AuthMode.TOKEN_ENCRYPTED,
            copyToClipboard = true,
            snackbarHostState = remember { SnackbarHostState() },
            onSearchQueryChange = {},
            onSelectCategory = {},
            onTokenCardClick = {},
            onTokenCodeExpired = {},
            onCommitTokenReorder = {},
            onEditToken = {},
            onDeleteToken = {},
            onShareToOtherApps = {},
            onShareQrCode = {},
            onNavigateToScanQr = {},
            onNavigateToAddToken = {},
            onNavigateToAbout = {},
            onImportJson = {},
            onImportKeyUri = {},
            onImportTokenImages = {},
            onImportFreeOtpBackup = {},
            onImportAegisJson = {},
            onExportJson = {},
            onExportKeyUri = {},
            onExportTokenImages = {},
            onRequestSort = {},
            onSelectThemeMode = {},
            onSelectAuthMode = {},
            onToggleCopyToClipboard = {},
            onQuitAndLock = {},
        )
    }
}
