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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.TokenImportMode
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.SecurePassphraseField
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties
import org.fedorahosted.freeotp.ui.components.rememberSecurePassphraseController

/**
 * Unified Compose Material 3 dialog for importing FreeOTP+ JSON, FreeOTP XML, and Aegis JSON backups.
 *
 * - Uses [SecurePassphraseField] (`CharArray` extraction + `\u0000` scrubbing) when [requiresPassword] is true.
 * - Defaults to [TokenImportMode.APPEND] and displays the overwrite warning when [TokenImportMode.REPLACE_ALL] is selected.
 * - [onSubmit] returns a localized inline error string on wrong password, or `null` when handled/dismissed.
 */
@Composable
fun BackupImportDialog(
    requiresPassword: Boolean,
    @StringRes titleRes: Int,
    @StringRes encryptedMessageRes: Int,
    @StringRes unencryptedMessageRes: Int,
    @StringRes passwordHintRes: Int = R.string.freeotp_backup_password_hint,
    @StringRes incorrectPasswordRes: Int = R.string.incorrect_freeotp_backup_password,
    onDismiss: () -> Unit,
    onSubmit: suspend (passphrase: CharArray?, mode: TokenImportMode) -> String?,
) {
    val coroutineScope = rememberCoroutineScope()
    val keyController = rememberSecurePassphraseController()
    val incorrectPasswordMessage = stringResource(incorrectPasswordRes)
    var selectedMode by rememberSaveable { mutableStateOf(TokenImportMode.APPEND) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }

    val triggerSubmit = {
        if (!isSubmitting) {
            val chars = if (requiresPassword) keyController.extractCharArray() else CharArray(0)
            var handedOff = false
            try {
                if (requiresPassword && chars.isEmpty()) {
                    errorMessage = incorrectPasswordMessage
                } else {
                    isSubmitting = true
                    handedOff = true
                    coroutineScope.launch(Dispatchers.Main.immediate) {
                        try {
                            val inlineError = onSubmit(
                                if (requiresPassword) chars else null,
                                selectedMode,
                            )
                            if (inlineError != null) {
                                isSubmitting = false
                                errorMessage = inlineError
                            } else {
                                keyController.scrubAndCancelAutofill()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } finally {
                            chars.fill('\u0000')
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
                    text = stringResource(if (requiresPassword) encryptedMessageRes else unencryptedMessageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (requiresPassword) {
                    SecurePassphraseField(
                        controller = keyController,
                        hint = stringResource(passwordHintRes),
                        errorMessage = errorMessage,
                        imeAction = EditorInfo.IME_ACTION_DONE,
                        enabled = !isSubmitting,
                        testTag = UiTestTags.SECURE_KEY_INPUT,
                        onTextChanged = { errorMessage = null },
                        onImeAction = triggerSubmit,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(
                            selected = selectedMode == TokenImportMode.APPEND,
                            enabled = !isSubmitting,
                            role = Role.RadioButton,
                            onClick = { selectedMode = TokenImportMode.APPEND },
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag(UiTestTags.IMPORT_MODE_APPEND_RADIO),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedMode == TokenImportMode.APPEND,
                        onClick = null,
                        enabled = !isSubmitting,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.import_mode_append),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .selectable(
                            selected = selectedMode == TokenImportMode.REPLACE_ALL,
                            enabled = !isSubmitting,
                            role = Role.RadioButton,
                            onClick = { selectedMode = TokenImportMode.REPLACE_ALL },
                        )
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag(UiTestTags.IMPORT_MODE_REPLACE_ALL_RADIO),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedMode == TokenImportMode.REPLACE_ALL,
                        onClick = null,
                        enabled = !isSubmitting,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.import_mode_replace_all),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (selectedMode == TokenImportMode.REPLACE_ALL) {
                    Text(
                        text = stringResource(R.string.import_json_file_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 6.dp, start = 4.dp, end = 4.dp)
                            .testTag(UiTestTags.REPLACE_WARNING_TEXT),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = triggerSubmit,
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.import_text))
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
