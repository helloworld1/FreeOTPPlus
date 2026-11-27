package org.fedorahosted.freeotp.ui.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.TokenAvatar
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

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
    token: OtpToken,
    imageRefreshVersion: Long = 0L,
    onConfirmAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    var isSubmitting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.add_token))
        },
        text = {
            AddTokenConfirmationContent(
                token = token,
                imageRefreshVersion = imageRefreshVersion,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSubmitting) {
                        isSubmitting = true
                        onConfirmAdd()
                    }
                },
                enabled = !isSubmitting,
                modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
            ) {
                Text(text = stringResource(R.string.add))
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
internal fun AddTokenConfirmationContent(
    token: OtpToken,
    imageRefreshVersion: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val formattedCategories = remember(token.categories) {
        CategoryUtil.formatForDisplay(token.categories)
    }
    val confirmationPrompt = stringResource(R.string.add_token_confirmation_message, "").trim()

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = confirmationPrompt,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(52.dp),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(5.dp),
                        ) {
                            TokenAvatar(
                                token = token,
                                size = 42.dp,
                                imageRefreshVersion = imageRefreshVersion,
                                modifier = Modifier.testTag(UiTestTags.ADD_TOKEN_DIALOG_AVATAR),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        if (token.issuer.isNullOrBlank()) {
                            Text(
                                text = token.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(UiTestTags.ADD_TOKEN_DIALOG_ISSUER),
                            )
                        } else {
                            Text(
                                text = token.issuer.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(UiTestTags.ADD_TOKEN_DIALOG_ISSUER),
                            )
                            if (token.label.isNotEmpty()) {
                                Text(
                                    text = token.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag(UiTestTags.ADD_TOKEN_DIALOG_LABEL),
                                )
                            }
                        }

                        if (formattedCategories.isNotBlank()) {
                            Text(
                                text = formattedCategories,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .testTag(UiTestTags.ADD_TOKEN_DIALOG_CATEGORY),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
private fun DeepLinkAddTokenDialogLightPreview() {
    val sampleToken = OtpTokenFactory.create(
        type = OtpTokenType.TOTP,
        issuer = "GitHub",
        label = "octocat@github.com",
        secret = "JBSWY3DPEHPK3PXP",
        categories = listOf("Work", "DevOps"),
    )
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.add_token),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AddTokenConfirmationContent(token = sampleToken)
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {}) {
                        Text(text = stringResource(R.string.cancel_text))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {}) {
                        Text(text = stringResource(R.string.add))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
private fun DeepLinkAddTokenDialogDarkInitialBadgePreview() {
    val sampleToken = OtpTokenFactory.create(
        type = OtpTokenType.TOTP,
        issuer = "Acme Internal SSO",
        label = "alice@acme.example",
        secret = "JBSWY3DPEHPK3PXP",
    )
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.add_token),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                AddTokenConfirmationContent(token = sampleToken)
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {}) {
                        Text(text = stringResource(R.string.cancel_text))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {}) {
                        Text(text = stringResource(R.string.add))
                    }
                }
            }
        }
    }
}
