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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
            ThreeActionDialogButtons(
                primaryButton = {
                    TextButton(
                        onClick = onExportEncrypted,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_POSITIVE_BUTTON),
                    ) {
                        Text(
                            text = stringResource(R.string.export_encrypted),
                            textAlign = TextAlign.End,
                        )
                    }
                },
                secondaryButton = {
                    TextButton(
                        onClick = onExportDecrypted,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_NEGATIVE_BUTTON),
                    ) {
                        Text(
                            text = stringResource(R.string.export_decrypted),
                            textAlign = TextAlign.End,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag(UiTestTags.DIALOG_NEUTRAL_BUTTON),
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

/**
 * Adaptive Material 3 button layout for 3-action [AlertDialog]s.
 *
 * Places `[dismiss] ... [secondary] [primary]` on a single horizontal row only when all three
 * buttons fit on one line without soft-wrapping; otherwise stacks `[primary]`, `[secondary]`,
 * `[dismiss]` vertically aligned to the trailing edge so button text never wraps mid-word.
 */
@Composable
internal fun ThreeActionDialogButtons(
    primaryButton: @Composable () -> Unit,
    secondaryButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 8.dp,
    verticalSpacing: Dp = 2.dp,
) {
    Layout(
        content = {
            primaryButton()
            secondaryButton()
            dismissButton()
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        require(measurables.size == 3) { "Expected exactly 3 dialog action buttons" }
        val primaryMeasurable = measurables[0]
        val secondaryMeasurable = measurables[1]
        val dismissMeasurable = measurables[2]

        val hSpacingPx = horizontalSpacing.roundToPx()
        val vSpacingPx = verticalSpacing.roundToPx()
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )

        val singleLineRequiredWidth =
            primaryMeasurable.maxIntrinsicWidth(Constraints.Infinity).toLong() +
                secondaryMeasurable.maxIntrinsicWidth(Constraints.Infinity).toLong() +
                dismissMeasurable.maxIntrinsicWidth(Constraints.Infinity).toLong() +
                hSpacingPx.toLong() * 2L

        val primaryPlaceable = primaryMeasurable.measure(childConstraints)
        val secondaryPlaceable = secondaryMeasurable.measure(childConstraints)
        val dismissPlaceable = dismissMeasurable.measure(childConstraints)

        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            maxOf(primaryPlaceable.width, secondaryPlaceable.width, dismissPlaceable.width)
        }

        val measuredRowWidth =
            primaryPlaceable.width.toLong() +
                secondaryPlaceable.width.toLong() +
                dismissPlaceable.width.toLong() +
                hSpacingPx.toLong() * 2L

        val fitsSingleRow = constraints.hasBoundedWidth &&
            singleLineRequiredWidth <= layoutWidth.toLong() &&
            measuredRowWidth <= layoutWidth.toLong()

        if (fitsSingleRow) {
            val rowHeight = maxOf(
                primaryPlaceable.height,
                secondaryPlaceable.height,
                dismissPlaceable.height,
            )
            layout(layoutWidth, rowHeight) {
                val primaryY = (rowHeight - primaryPlaceable.height) / 2
                val secondaryY = (rowHeight - secondaryPlaceable.height) / 2
                val dismissY = (rowHeight - dismissPlaceable.height) / 2
                val primaryX = layoutWidth - primaryPlaceable.width
                val secondaryX = primaryX - hSpacingPx - secondaryPlaceable.width
                dismissPlaceable.placeRelative(0, dismissY)
                secondaryPlaceable.placeRelative(secondaryX, secondaryY)
                primaryPlaceable.placeRelative(primaryX, primaryY)
            }
        } else {
            val totalHeight = primaryPlaceable.height +
                secondaryPlaceable.height +
                dismissPlaceable.height +
                vSpacingPx * 2
            layout(layoutWidth, totalHeight) {
                var y = 0
                primaryPlaceable.placeRelative(layoutWidth - primaryPlaceable.width, y)
                y += primaryPlaceable.height + vSpacingPx
                secondaryPlaceable.placeRelative(layoutWidth - secondaryPlaceable.width, y)
                y += secondaryPlaceable.height + vSpacingPx
                dismissPlaceable.placeRelative(layoutWidth - dismissPlaceable.width, y)
            }
        }
    }
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

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ExportEncryptionChoiceDialogLightPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.export_encryption_choice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.export_encryption_choice_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                ThreeActionDialogButtons(
                    primaryButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.export_encrypted))
                        }
                    },
                    secondaryButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.export_decrypted))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.cancel_text))
                        }
                    },
                )
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

@Preview(showBackground = true, widthDp = 320)
@Composable
private fun ExportEncryptionChoiceDialogDarkPreview() {
    FreeOtpTheme(themeMode = ThemeMode.DARK) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.export_encryption_choice_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.export_encryption_choice_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))
                ThreeActionDialogButtons(
                    primaryButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.export_encrypted))
                        }
                    },
                    secondaryButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.export_decrypted))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {}) {
                            Text(text = stringResource(R.string.cancel_text))
                        }
                    },
                )
            }
        }
    }
}
