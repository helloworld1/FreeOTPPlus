package org.fedorahosted.freeotp.ui.dialogs

import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.util.CryptoEngine
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.SecurePassphraseField
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties
import org.fedorahosted.freeotp.ui.components.rememberSecurePassphraseController

@Composable
fun SetEncryptionKeyDialog(
    @StringRes titleRes: Int = R.string.set_encryption_key_title,
    @StringRes messageRes: Int = R.string.set_encryption_key_message,
    @StringRes positiveButtonRes: Int = R.string.ok_text,
    onDismiss: () -> Unit,
    onSubmitValidKey: suspend (passphrase: CharArray) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val keyController = rememberSecurePassphraseController()
    val confirmController = rememberSecurePassphraseController()
    val tooShortMessage = stringResource(R.string.encryption_key_too_short, CryptoEngine.MIN_KEY_LENGTH)
    val keysDoNotMatchMessage = stringResource(R.string.encryption_keys_do_not_match)
    var keyError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val triggerSubmit = {
        if (!isSubmitting) {
            val passphraseChars = keyController.extractCharArray()
            val confirmChars = confirmController.extractCharArray()
            var handedOff = false
            try {
                when {
                    passphraseChars.size < CryptoEngine.MIN_KEY_LENGTH -> {
                        keyError = tooShortMessage
                    }
                    !passphraseChars.contentEquals(confirmChars) -> {
                        confirmError = keysDoNotMatchMessage
                    }
                    else -> {
                        isSubmitting = true
                        keyController.scrubAndCancelAutofill()
                        confirmController.scrubAndCancelAutofill()
                        handedOff = true
                        coroutineScope.launch(Dispatchers.Main.immediate) {
                            try {
                                onSubmitValidKey(passphraseChars)
                            } catch (e: CancellationException) {
                                throw e
                            } finally {
                                passphraseChars.fill('\u0000')
                            }
                        }
                    }
                }
            } finally {
                confirmChars.fill('\u0000')
                if (!handedOff) {
                    passphraseChars.fill('\u0000')
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(titleRes))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(messageRes, CryptoEngine.MIN_KEY_LENGTH),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                SecurePassphraseField(
                    controller = keyController,
                    hint = stringResource(R.string.encryption_key_hint),
                    errorMessage = keyError,
                    errorTestTag = UiTestTags.SECURE_KEY_ERROR,
                    imeAction = EditorInfo.IME_ACTION_NEXT,
                    enabled = !isSubmitting,
                    testTag = UiTestTags.SECURE_KEY_INPUT,
                    onTextChanged = { keyError = null },
                )

                Spacer(modifier = Modifier.height(12.dp))

                SecurePassphraseField(
                    controller = confirmController,
                    hint = stringResource(R.string.confirm_encryption_key_hint),
                    errorMessage = confirmError,
                    errorTestTag = UiTestTags.SECURE_CONFIRM_KEY_ERROR,
                    imeAction = EditorInfo.IME_ACTION_DONE,
                    enabled = !isSubmitting,
                    testTag = UiTestTags.SECURE_CONFIRM_KEY_INPUT,
                    onTextChanged = { confirmError = null },
                    onImeAction = triggerSubmit,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = triggerSubmit,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(positiveButtonRes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}

@Composable
fun ManualKeyPromptDialog(
    @StringRes titleRes: Int,
    @StringRes messageRes: Int,
    @StringRes positiveButtonRes: Int,
    cancelable: Boolean = true,
    @StringRes biometricCheckboxLabelRes: Int? = null,
    initialBiometricChecked: Boolean = true,
    onCancel: () -> Unit,
    onSubmit: suspend (passphrase: CharArray, biometricChecked: Boolean) -> String?,
) {
    val coroutineScope = rememberCoroutineScope()
    val keyController = rememberSecurePassphraseController()
    val incorrectKeyMessage = stringResource(R.string.incorrect_encryption_key)
    var biometricChecked by rememberSaveable(biometricCheckboxLabelRes, initialBiometricChecked) {
        mutableStateOf(initialBiometricChecked)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val triggerSubmit = {
        if (!isSubmitting) {
            val chars = keyController.extractCharArray()
            var handedOff = false
            try {
                if (chars.isEmpty()) {
                    errorMessage = incorrectKeyMessage
                } else {
                    isSubmitting = true
                    val useBiometric = biometricCheckboxLabelRes != null && biometricChecked
                    handedOff = true
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        val inlineError = try {
                            onSubmit(chars, useBiometric)
                        } catch (e: CancellationException) {
                            throw e
                        } finally {
                            chars.fill('\u0000')
                        }
                        if (inlineError != null) {
                            isSubmitting = false
                            errorMessage = inlineError
                        } else {
                            keyController.scrubAndCancelAutofill()
                        }
                    }
                }
            } finally {
                if (!handedOff) {
                    chars.fill('\u0000')
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (cancelable && !isSubmitting) {
                onCancel()
            }
        },
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = cancelable && !isSubmitting,
            dismissOnClickOutside = cancelable && !isSubmitting,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(titleRes))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(messageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                SecurePassphraseField(
                    controller = keyController,
                    hint = stringResource(R.string.encryption_key_hint),
                    errorMessage = errorMessage,
                    imeAction = EditorInfo.IME_ACTION_DONE,
                    enabled = !isSubmitting,
                    testTag = UiTestTags.SECURE_KEY_INPUT,
                    onTextChanged = { errorMessage = null },
                    onImeAction = triggerSubmit,
                )

                if (biometricCheckboxLabelRes != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .toggleable(
                                value = biometricChecked,
                                enabled = !isSubmitting,
                                role = Role.Checkbox,
                                onValueChange = { biometricChecked = it },
                            )
                            .padding(vertical = 8.dp, horizontal = 4.dp)
                            .testTag(UiTestTags.ENABLE_BIOMETRIC_CHECKBOX),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = biometricChecked,
                            onCheckedChange = null,
                            enabled = !isSubmitting,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(biometricCheckboxLabelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = triggerSubmit,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(positiveButtonRes))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}

@Composable
fun ScreenLockRequiredDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.screen_lock_required_title))
        },
        text = {
            Text(text = stringResource(R.string.screen_lock_required_message))
        },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.open_settings_button))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}

@Composable
fun ScreenLockDisabledDialog(
    onSetUpScreenLock: () -> Unit,
    onTurnOffAppLaunchAuth: () -> Unit,
    onCancelAndExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.screen_lock_disabled_title))
        },
        text = {
            Text(text = stringResource(R.string.screen_lock_disabled_message))
        },
        confirmButton = {
            ThreeActionDialogButtons(
                primaryButton = {
                    TextButton(
                        onClick = onSetUpScreenLock,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
                    ) {
                        Text(
                            text = stringResource(R.string.set_up_screen_lock_button),
                            textAlign = TextAlign.End,
                        )
                    }
                },
                secondaryButton = {
                    TextButton(
                        onClick = onTurnOffAppLaunchAuth,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_NEUTRAL_BUTTON),
                    ) {
                        Text(
                            text = stringResource(R.string.turn_off_button),
                            textAlign = TextAlign.End,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancelAndExit,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
                    ) {
                        Text(
                            text = stringResource(R.string.cancel_text),
                            textAlign = TextAlign.End,
                        )
                    }
                },
            )
        },
    )
}

@Composable
fun AppLaunchRetryDialog(
    errorMessage: String?,
    onRetryUnlock: () -> Unit,
    onCancelAndExit: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.authentication_dialog_title))
        },
        text = {
            Text(text = errorMessage ?: stringResource(R.string.authentication_dialog_subtitle))
        },
        confirmButton = {
            TextButton(
                onClick = onRetryUnlock,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.unlock_text))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelAndExit,
                modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}

@Composable
fun DisableEncryptionWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        icon = {
            Icon(
                painter = painterResource(R.drawable.alert),
                contentDescription = null,
            )
        },
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.disable_token_encryption_title))
        },
        text = {
            Text(text = stringResource(R.string.disable_token_encryption_warning))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.ok_text))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}
