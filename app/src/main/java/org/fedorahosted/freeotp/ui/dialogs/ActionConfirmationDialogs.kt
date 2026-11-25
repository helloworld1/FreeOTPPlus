package org.fedorahosted.freeotp.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties

@Composable
fun SortConfirmationDialog(
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
            Text(text = stringResource(R.string.sort_tokens_warning_title))
        },
        text = {
            Text(text = stringResource(R.string.sort_tokens_warning_message))
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

@Composable
fun ExportEncryptionChoiceDialog(
    onExportEncrypted: () -> Unit,
    onExportDecrypted: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.export_encryption_choice_title))
        },
        text = {
            Text(text = stringResource(R.string.export_encryption_choice_message))
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag(UiTestTags.DIALOG_NEUTRAL_BUTTON),
                ) {
                    Text(text = stringResource(R.string.cancel_text))
                }
                SpacerWeight()
                TextButton(
                    onClick = onExportDecrypted,
                    modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
                ) {
                    Text(text = stringResource(R.string.export_decrypted))
                }
                TextButton(
                    onClick = onExportEncrypted,
                    modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
                ) {
                    Text(text = stringResource(R.string.export_encrypted))
                }
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SpacerWeight() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
}

@Composable
fun DeepLinkAddTokenDialog(
    tokenDetails: String,
    onConfirmAdd: () -> Unit,
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
            Text(text = stringResource(R.string.add_token))
        },
        text = {
            Text(text = stringResource(R.string.add_token_confirmation_message, tokenDetails))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirmAdd,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.add))
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
