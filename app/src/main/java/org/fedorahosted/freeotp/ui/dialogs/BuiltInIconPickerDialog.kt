package org.fedorahosted.freeotp.ui.dialogs

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.components.SecureDialogWindowEffect
import org.fedorahosted.freeotp.ui.components.UiTestTags
import org.fedorahosted.freeotp.ui.components.rememberSecureDialogProperties
import org.liberty.android.freeotp.token_images.TokenImage
import org.liberty.android.freeotp.token_images.displayName
import java.util.Locale

@Composable
fun TokenImageSourceChooserDialog(
    @StringRes titleRes: Int,
    onChooseBuiltInIcon: () -> Unit,
    onChooseFromDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(titleRes))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onChooseBuiltInIcon() }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.choose_builtin_icon),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onChooseFromDevice() }
                        .padding(vertical = 14.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.choose_image_from_device),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
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
fun BuiltInIconPickerDialog(
    onIconSelected: (TokenImage) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val allItems = remember {
        TokenImage.entries.sortedBy { it.displayName().lowercase(Locale.ROOT) }
    }
    val filteredItems = remember(searchQuery, allItems) {
        val trimmed = searchQuery.trim()
        if (trimmed.isEmpty()) {
            allItems
        } else {
            allItems.filter { entry ->
                entry.displayName().contains(trimmed, ignoreCase = true) ||
                    entry.name.contains(trimmed, ignoreCase = true) ||
                    entry.altIssuers.any { it.contains(trimmed, ignoreCase = true) }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.choose_builtin_icon))
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(text = stringResource(R.string.search_icons)) },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search_24px),
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close_24px),
                                    contentDescription = stringResource(R.string.cancel_text),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTestTags.ICON_SEARCH_INPUT),
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 76.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 380.dp)
                        .testTag(UiTestTags.ICON_GRID),
                ) {
                    items(items = filteredItems, key = { it.name }) { item ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onIconSelected(item) }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                                .testTag(UiTestTags.builtInIconItemTag(item.name)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Image(
                                painter = painterResource(item.resource),
                                contentDescription = item.displayName(),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(percent = 17)),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.displayName(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    hyphens = Hyphens.Auto,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
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
