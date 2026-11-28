package org.fedorahosted.freeotp.ui.addedit

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.ui.components.CategorySelectorFlowRow
import org.fedorahosted.freeotp.ui.components.TokenAvatar
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.dialogs.BuiltInIconPickerDialog
import org.fedorahosted.freeotp.ui.dialogs.TokenImageSourceChooserDialog
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.ui.tokenlist.TokenDisplayUtil
import org.fedorahosted.freeotp.util.ThemeMode

@Composable
fun EditTokenScreen(
    tokenId: Long,
    viewModel: EditTokenViewModel,
    onNavigateBack: () -> Unit,
    onRequestRevealSecret: (token: OtpToken, onRevealed: (String) -> Unit) -> Unit,
    onCopySensitiveSecret: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSourceChooser by rememberSaveable { mutableStateOf(false) }
    var showBuiltInPicker by rememberSaveable { mutableStateOf(false) }

    // Security invariant: never persist revealed OTP secrets in SavedState / Bundle
    var revealedSecret by remember(tokenId) { mutableStateOf<String?>(null) }
    DisposableEffect(tokenId) {
        onDispose {
            revealedSecret = null
        }
    }

    LaunchedEffect(tokenId) {
        viewModel.loadTokenIfNeeded(tokenId)
    }

    LaunchedEffect(state.tokenMissing) {
        if (state.tokenMissing) {
            onNavigateBack()
        }
    }

    val pickDeviceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.savePickedDeviceImage(uri) {
                Toast.makeText(context, R.string.fail_to_add_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    EditTokenScreenContent(
        state = state,
        revealedSecret = revealedSecret,
        onIssuerChange = viewModel::updateIssuer,
        onLabelChange = viewModel::updateLabel,
        onToggleCategory = viewModel::toggleCategory,
        onCreateCategory = viewModel::createAndSelectCategory,
        onAvatarClick = { showSourceChooser = true },
        onRemoveImage = viewModel::removeTokenImage,
        onRestoreDefaults = viewModel::restoreDefaults,
        onRevealSecretClick = {
            val token = state.storedToken ?: return@EditTokenScreenContent
            if (revealedSecret == null) {
                onRequestRevealSecret(token) { decrypted ->
                    revealedSecret = decrypted
                }
            }
        },
        onCopySecretClick = {
            revealedSecret?.let(onCopySensitiveSecret)
        },
        onCancel = onNavigateBack,
        onSave = {
            viewModel.saveTokenChanges(onSaved = onNavigateBack)
        },
        modifier = modifier,
    )

    if (showSourceChooser) {
        TokenImageSourceChooserDialog(
            titleRes = R.string.change_token_image,
            onChooseBuiltInIcon = {
                showSourceChooser = false
                showBuiltInPicker = true
            },
            onChooseFromDevice = {
                showSourceChooser = false
                pickDeviceImageLauncher.launch("image/*")
            },
            onDismiss = { showSourceChooser = false },
        )
    }

    if (showBuiltInPicker) {
        BuiltInIconPickerDialog(
            onIconSelected = { icon ->
                viewModel.selectBuiltInIcon(icon.name)
                showBuiltInPicker = false
            },
            onDismiss = { showBuiltInPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTokenScreenContent(
    state: EditTokenUiState,
    revealedSecret: String?,
    onIssuerChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onCreateCategory: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onRemoveImage: () -> Unit,
    onRestoreDefaults: () -> Unit,
    onRevealSecretClick: () -> Unit,
    onCopySecretClick: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val storedToken = state.storedToken

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag(UiTestTags.NAV_BACK_BUTTON),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.cancel_text),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding(),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    EditTokenBottomBarActions(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = 10.dp, end = 16.dp, bottom = 10.dp),
                        restoreButton = {
                            TextButton(
                                onClick = onRestoreDefaults,
                                enabled = state.canRestore,
                                modifier = Modifier.testTag(UiTestTags.RESTORE_DEFAULTS_BUTTON),
                            ) {
                                Text(
                                    text = stringResource(R.string.restore_defaults),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                        cancelButton = {
                            TextButton(
                                onClick = onCancel,
                                enabled = !state.isSaving,
                                modifier = Modifier.testTag(UiTestTags.CANCEL_BUTTON),
                            ) {
                                Text(
                                    text = stringResource(R.string.cancel_text),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                        saveButton = {
                            Button(
                                onClick = onSave,
                                enabled = state.canSave,
                                modifier = Modifier.testTag(UiTestTags.SAVE_BUTTON),
                            ) {
                                Text(
                                    text = stringResource(R.string.save),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        if (storedToken == null) {
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .align(Alignment.CenterHorizontally),
            ) {
                OutlinedCard(
                    modifier = Modifier
                        .size(88.dp)
                        .align(Alignment.Center)
                        .clickable(onClick = onAvatarClick)
                        .testTag(UiTestTags.TOKEN_AVATAR_BUTTON),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TokenAvatar(
                                imagePath = state.displayedImagePath,
                                issuer = state.issuer,
                                label = state.label,
                                size = 72.dp,
                                contentDescription = stringResource(R.string.change_token_image),
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.BottomEnd),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(5.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.edit_16px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            TextButton(
                onClick = onRemoveImage,
                enabled = state.displayedImagePath != null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp)
                    .testTag(UiTestTags.REMOVE_TOKEN_IMAGE_BUTTON),
            ) {
                Text(text = stringResource(R.string.remove_user_token_image))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.issuer,
                onValueChange = onIssuerChange,
                label = { Text(text = stringResource(R.string.issuer)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTags.ISSUER_INPUT),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.label,
                onValueChange = onLabelChange,
                label = { Text(text = stringResource(R.string.account)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTags.LABEL_INPUT),
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.category),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            CategorySelectorFlowRow(
                knownCategories = state.knownCategories,
                selectedCategories = state.selectedCategories,
                onToggleCategory = onToggleCategory,
                onCreateCategory = onCreateCategory,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.token_details),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        DetailRow(
                            label = stringResource(R.string.algorithm),
                            value = storedToken.algorithm,
                            valueTestTag = UiTestTags.DETAIL_ALGORITHM,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        DetailRow(
                            label = stringResource(R.string.digits),
                            value = storedToken.digits.toString(),
                            valueTestTag = UiTestTags.DETAIL_DIGITS,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val isHotp = storedToken.tokenType == OtpTokenType.HOTP
                        DetailRow(
                            label = stringResource(if (isHotp) R.string.counter else R.string.interval),
                            value = if (isHotp) {
                                TokenDisplayUtil.formatHotpCounter(storedToken.counter)
                            } else {
                                storedToken.period.toString()
                            },
                            valueTestTag = UiTestTags.DETAIL_INTERVAL_OR_COUNTER,
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = stringResource(R.string.secret),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 40.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = revealedSecret ?: stringResource(R.string.click_to_display),
                                style = if (revealedSecret != null) {
                                    MaterialTheme.typography.bodyLarge.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection = TextDirection.Ltr,
                                        textAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                                            TextAlign.Right
                                        } else {
                                            TextAlign.Left
                                        },
                                    )
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (revealedSecret == null) {
                                            Modifier.clickable(onClick = onRevealSecretClick)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .padding(vertical = 8.dp)
                                    .testTag(UiTestTags.DETAIL_SECRET),
                            )

                            if (revealedSecret != null) {
                                IconButton(
                                    onClick = onCopySecretClick,
                                    modifier = Modifier.testTag(UiTestTags.COPY_SECRET_BUTTON),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.content_copy_24px),
                                        contentDescription = stringResource(R.string.copy_to_clipboard),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EditTokenBottomBarActions(
    restoreButton: @Composable () -> Unit,
    cancelButton: @Composable () -> Unit,
    saveButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            restoreButton()
            cancelButton()
            saveButton()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val minSpacerPx = 12.dp.roundToPx()
        val actionGapPx = 6.dp.roundToPx()
        val verticalGapPx = 4.dp.roundToPx()

        val restoreIntrinsic = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val cancelIntrinsic = measurables[1].maxIntrinsicWidth(Constraints.Infinity)
        val saveIntrinsic = measurables[2].maxIntrinsicWidth(Constraints.Infinity)

        val totalWidth = constraints.maxWidth
        val fitsSingleRow = restoreIntrinsic + minSpacerPx + cancelIntrinsic + actionGapPx + saveIntrinsic <= totalWidth

        val unconstrainedWidth = constraints.copy(minWidth = 0)
        val restorePlaceable = measurables[0].measure(unconstrainedWidth)
        val cancelPlaceable = measurables[1].measure(unconstrainedWidth)
        val savePlaceable = measurables[2].measure(unconstrainedWidth)

        if (fitsSingleRow) {
            val rowHeight = maxOf(restorePlaceable.height, cancelPlaceable.height, savePlaceable.height)
            layout(totalWidth, rowHeight) {
                val restoreY = (rowHeight - restorePlaceable.height) / 2
                val cancelY = (rowHeight - cancelPlaceable.height) / 2
                val saveY = (rowHeight - savePlaceable.height) / 2
                if (layoutDirection == LayoutDirection.Ltr) {
                    restorePlaceable.placeRelative(0, restoreY)
                    val saveX = totalWidth - savePlaceable.width
                    val cancelX = saveX - actionGapPx - cancelPlaceable.width
                    cancelPlaceable.placeRelative(cancelX, cancelY)
                    savePlaceable.placeRelative(saveX, saveY)
                } else {
                    restorePlaceable.place(totalWidth - restorePlaceable.width, restoreY)
                    savePlaceable.place(0, saveY)
                    cancelPlaceable.place(savePlaceable.width + actionGapPx, cancelY)
                }
            }
        } else {
            val topHeight = restorePlaceable.height
            val bottomHeight = maxOf(cancelPlaceable.height, savePlaceable.height)
            val totalHeight = topHeight + verticalGapPx + bottomHeight
            layout(totalWidth, totalHeight) {
                val bottomY = topHeight + verticalGapPx
                val cancelY = bottomY + (bottomHeight - cancelPlaceable.height) / 2
                val saveY = bottomY + (bottomHeight - savePlaceable.height) / 2
                if (layoutDirection == LayoutDirection.Ltr) {
                    restorePlaceable.place(0, 0)
                    val saveX = totalWidth - savePlaceable.width
                    val cancelX = (saveX - actionGapPx - cancelPlaceable.width).coerceAtLeast(0)
                    cancelPlaceable.place(cancelX, cancelY)
                    savePlaceable.place(saveX, saveY)
                } else {
                    restorePlaceable.place(totalWidth - restorePlaceable.width, 0)
                    savePlaceable.place(0, saveY)
                    cancelPlaceable.place(savePlaceable.width + actionGapPx, cancelY)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueTestTag: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(valueTestTag),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EditTokenScreenPreview() {
    val sampleToken = OtpTokenFactory.create(
        type = OtpTokenType.HOTP,
        issuer = "GitHub",
        label = "octocat@github.com",
        secret = "JBSWY3DPEHPK3PXP",
        algorithm = "SHA256",
        digits = 6,
        initialCounter = 12L,
        categories = listOf("Work"),
    )
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        EditTokenScreenContent(
            state = EditTokenUiState(
                loadedTokenId = 1L,
                storedToken = sampleToken,
                initialIssuer = "GitHub",
                initialLabel = "octocat@github.com",
                initialCategories = listOf("Work"),
                issuer = "GitHub",
                label = "octocat@github.com",
                knownCategories = listOf("Personal", "Work"),
                selectedCategories = listOf("Work"),
            ),
            revealedSecret = null,
            onIssuerChange = {},
            onLabelChange = {},
            onToggleCategory = {},
            onCreateCategory = {},
            onAvatarClick = {},
            onRemoveImage = {},
            onRestoreDefaults = {},
            onRevealSecretClick = {},
            onCopySecretClick = {},
            onCancel = {},
            onSave = {},
        )
    }
}
