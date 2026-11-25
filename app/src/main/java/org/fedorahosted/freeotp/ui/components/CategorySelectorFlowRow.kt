package org.fedorahosted.freeotp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.ui.theme.FreeOtpTheme
import org.fedorahosted.freeotp.util.ThemeMode

/**
 * Multi-select Material 3 FlowRow of category FilterChips + "+ New category" AssistChip.
 * Replaces legacy CategoryChipHelper and item_category_filter_chip.xml / item_add_category_chip.xml.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelectorFlowRow(
    knownCategories: List<String>,
    selectedCategories: List<String>,
    onToggleCategory: (String) -> Unit,
    onCreateCategory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTestTags.CATEGORY_FLOW_ROW),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (category in knownCategories) {
            val isSelected = selectedCategories.any { it.equals(category, ignoreCase = true) }
            FilterChip(
                selected = isSelected,
                onClick = { onToggleCategory(category) },
                label = { Text(text = category) },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_check_24px),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier.testTag(UiTestTags.editCategoryChipTag(category)),
            )
        }

        AssistChip(
            onClick = { showAddDialog = true },
            label = { Text(text = stringResource(R.string.add_category)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.add_24px),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            modifier = Modifier.testTag(UiTestTags.ADD_CATEGORY_CHIP),
        )
    }

    if (showAddDialog) {
        AddCategoryDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newCategory ->
                onCreateCategory(newCategory)
                showAddDialog = false
            },
        )
    }
}

@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var categoryName by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val trimmed = categoryName.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = rememberSecureDialogProperties(),
        title = {
            SecureDialogWindowEffect()
            Text(text = stringResource(R.string.add_category))
        },
        text = {
            OutlinedTextField(
                value = categoryName,
                onValueChange = { categoryName = it },
                label = { Text(text = stringResource(R.string.category)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (trimmed.isNotEmpty()) {
                            onConfirm(trimmed)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(UiTestTags.CATEGORY_NAME_INPUT),
            )
            LaunchedEffect(Unit) {
                runCatching { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (trimmed.isNotEmpty()) {
                        onConfirm(trimmed)
                    }
                },
                enabled = trimmed.isNotEmpty(),
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

@Preview(showBackground = true)
@Composable
private fun CategorySelectorFlowRowPreview() {
    FreeOtpTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp)) {
            CategorySelectorFlowRow(
                knownCategories = listOf("Cloud", "Personal", "Work"),
                selectedCategories = listOf("Work"),
                onToggleCategory = {},
                onCreateCategory = {},
            )
        }
    }
}
