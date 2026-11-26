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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.ui.components.CategorySelectorFlowRow
import org.fedorahosted.freeotp.ui.components.TokenAvatar
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.dialogs.BuiltInIconPickerDialog
import org.fedorahosted.freeotp.ui.dialogs.TokenImageSourceChooserDialog
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

private val DIGIT_OPTIONS = listOf(5, 6, 7, 8)

@Composable
fun AddTokenScreen(
    viewModel: AddTokenViewModel,
    onNavigateBack: () -> Unit,
    onVaultLocked: () -> Unit,
    onShowSnackbar: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSourceChooser by rememberSaveable { mutableStateOf(false) }
    var showBuiltInPicker by rememberSaveable { mutableStateOf(false) }

    val pickDeviceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            viewModel.savePickedDeviceImage(uri) {
                Toast.makeText(context, R.string.fail_to_add_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AddTokenScreenContent(
        state = state,
        onIssuerChange = viewModel::updateIssuer,
        onLabelChange = viewModel::updateLabel,
        onSecretChange = viewModel::updateSecret,
        onTokenTypeChange = viewModel::updateTokenType,
        onDigitsChange = viewModel::updateDigits,
        onAlgorithmChange = viewModel::updateAlgorithm,
        onIntervalChange = viewModel::updateInterval,
        onCounterChange = viewModel::updateCounter,
        onToggleCategory = viewModel::toggleCategory,
        onCreateCategory = viewModel::createAndSelectCategory,
        onAvatarClick = { showSourceChooser = true },
        onCancel = onNavigateBack,
        onSubmit = {
            viewModel.submitNewToken { result ->
                when (result) {
                    AddTokenSubmitResult.Success -> onNavigateBack()
                    AddTokenSubmitResult.VaultLocked -> onVaultLocked()
                    is AddTokenSubmitResult.Error -> onShowSnackbar(result.messageRes)
                }
            }
        },
        modifier = modifier,
    )

    if (showSourceChooser) {
        TokenImageSourceChooserDialog(
            titleRes = R.string.select_token_image,
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
internal fun AddTokenScreenContent(
    state: AddTokenUiState,
    onIssuerChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onTokenTypeChange: (OtpTokenType) -> Unit,
    onDigitsChange: (Int) -> Unit,
    onAlgorithmChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onCounterChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onCreateCategory: (String) -> Unit,
    onAvatarClick: () -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val algorithms = stringArrayResource(R.array.algorithms)
    var algorithmExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.add_token),
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onCancel,
                            enabled = !state.isSubmitting,
                            modifier = Modifier.testTag(UiTestTags.CANCEL_BUTTON),
                        ) {
                            Text(text = stringResource(R.string.cancel_text))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onSubmit,
                            enabled = state.isValid,
                            modifier = Modifier.testTag(UiTestTags.ADD_SUBMIT_BUTTON),
                        ) {
                            Text(text = stringResource(R.string.add))
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
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
                                imagePath = state.selectedImagePath,
                                issuer = state.issuer,
                                label = state.label,
                                size = 72.dp,
                                contentDescription = stringResource(R.string.select_token_image),
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

            Spacer(modifier = Modifier.height(20.dp))

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

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = state.secret,
                onValueChange = onSecretChange,
                label = { Text(text = stringResource(R.string.secret)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTestTags.SECRET_INPUT),
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

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.type),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.tokenType == OtpTokenType.TOTP,
                    onClick = { onTokenTypeChange(OtpTokenType.TOTP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag(UiTestTags.TYPE_TOTP_BUTTON),
                ) {
                    Text(text = "TOTP")
                }
                SegmentedButton(
                    selected = state.tokenType == OtpTokenType.HOTP,
                    onClick = { onTokenTypeChange(OtpTokenType.HOTP) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag(UiTestTags.TYPE_HOTP_BUTTON),
                ) {
                    Text(text = "HOTP")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.digits),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DIGIT_OPTIONS.forEachIndexed { index, digitOption ->
                    SegmentedButton(
                        selected = state.digits == digitOption,
                        onClick = { onDigitsChange(digitOption) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DIGIT_OPTIONS.size),
                        modifier = Modifier.testTag(UiTestTags.digitsButtonTag(digitOption)),
                    ) {
                        Text(text = digitOption.toString())
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ExposedDropdownMenuBox(
                expanded = algorithmExpanded,
                onExpandedChange = { algorithmExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.algorithm,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(R.string.algorithm)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = algorithmExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .testTag(UiTestTags.ALGORITHM_DROPDOWN),
                )
                ExposedDropdownMenu(
                    expanded = algorithmExpanded,
                    onDismissRequest = { algorithmExpanded = false },
                ) {
                    algorithms.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(text = option) },
                            onClick = {
                                onAlgorithmChange(option)
                                algorithmExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (state.tokenType == OtpTokenType.TOTP) {
                OutlinedTextField(
                    value = state.interval,
                    onValueChange = onIntervalChange,
                    label = { Text(text = stringResource(R.string.interval)) },
                    suffix = { Text(text = "s") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTestTags.INTERVAL_INPUT),
                )
            } else {
                OutlinedTextField(
                    value = state.counter,
                    onValueChange = onCounterChange,
                    label = { Text(text = stringResource(R.string.counter)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTestTags.COUNTER_INPUT),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AddTokenScreenPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        AddTokenScreenContent(
            state = AddTokenUiState(
                issuer = "GitHub",
                label = "alice@github.com",
                secret = "JBSWY3DPEHPK3PXP",
                knownCategories = listOf("Cloud", "Work"),
                selectedCategories = listOf("Work"),
            ),
            onIssuerChange = {},
            onLabelChange = {},
            onSecretChange = {},
            onTokenTypeChange = {},
            onDigitsChange = {},
            onAlgorithmChange = {},
            onIntervalChange = {},
            onCounterChange = {},
            onToggleCategory = {},
            onCreateCategory = {},
            onAvatarClick = {},
            onCancel = {},
            onSubmit = {},
        )
    }
}
