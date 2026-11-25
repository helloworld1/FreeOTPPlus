package org.fedorahosted.freeotp.ui.dialogs

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
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
fun DeleteTokenDialog(
    token: OtpToken,
    imageRefreshVersion: Long = 0L,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    var isDeleting by remember { mutableStateOf(false) }
    val formattedCategories = remember(token.categories) {
        CategoryUtil.formatForDisplay(token.categories)
    }

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        properties = rememberSecureDialogProperties(
            dismissOnBackPress = !isDeleting,
            dismissOnClickOutside = !isDeleting,
        ),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.delete_question))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.delete_summary),
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
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(5.dp)) {
                                    TokenAvatar(
                                        token = token,
                                        size = 42.dp,
                                        imageRefreshVersion = imageRefreshVersion,
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
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag(UiTestTags.DELETE_ISSUER),
                                    )
                                } else {
                                    Text(
                                        text = token.issuer.orEmpty(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag(UiTestTags.DELETE_ISSUER),
                                    )
                                    Text(
                                        text = token.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag(UiTestTags.DELETE_LABEL),
                                    )
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
                                            .testTag(UiTestTags.DELETE_CATEGORY),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isDeleting) {
                        isDeleting = true
                        onConfirmDelete()
                    }
                },
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.testTag(UiTestTags.DELETE_CONFIRM_BUTTON),
            ) {
                Text(text = stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
                modifier = Modifier.testTag(UiTestTags.CANCEL_BUTTON),
            ) {
                Text(text = stringResource(R.string.cancel_text))
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun DeleteTokenDialogPreview() {
    val sampleToken = OtpTokenFactory.create(
        type = OtpTokenType.TOTP,
        issuer = "GitHub",
        label = "octocat@github.com",
        secret = "JBSWY3DPEHPK3PXP",
        categories = listOf("Work", "Cloud"),
    )
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        DeleteTokenDialog(
            token = sampleToken,
            onDismiss = {},
            onConfirmDelete = {},
        )
    }
}
