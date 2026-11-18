package org.fedorahosted.freeotp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.util.AuthenticationUtil
import java.util.Locale

class CategoryChipHelper(
    private val activity: AppCompatActivity,
    private val chipGroup: ChipGroup,
    private val onSelectionChanged: (List<String>) -> Unit = {}
) {
    private val knownCategories = mutableListOf<String>()
    private val selectedCategories = mutableListOf<String>()

    fun initialize(
        availableCategories: List<String>,
        initialSelected: List<String>,
        savedInstanceState: Bundle? = null
    ) {
        val restoredKnown = savedInstanceState?.getStringArrayList(STATE_KNOWN_CATEGORIES)
        val restoredSelected = savedInstanceState?.getStringArrayList(STATE_SELECTED_CATEGORIES)

        val effectiveSelected = CategoryUtil.normalizeCategories(restoredSelected ?: initialSelected)
        val mergedKnown = CategoryUtil.normalizeCategories(
            availableCategories + (restoredKnown ?: emptyList()) + effectiveSelected
        ).sortedWith(String.CASE_INSENSITIVE_ORDER)

        knownCategories.clear()
        knownCategories.addAll(mergedKnown)

        selectedCategories.clear()
        for (sel in effectiveSelected) {
            val canonical = findCanonical(sel) ?: sel
            if (selectedCategories.none { it.equals(canonical, ignoreCase = true) }) {
                selectedCategories.add(canonical)
            }
        }

        renderChips()
    }

    fun saveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_KNOWN_CATEGORIES, ArrayList(knownCategories))
        outState.putStringArrayList(STATE_SELECTED_CATEGORIES, ArrayList(selectedCategories))
    }

    fun getSelectedCategories(): List<String> = selectedCategories.toList()

    fun setSelectedCategories(categories: List<String>, notify: Boolean = true) {
        val normalized = CategoryUtil.normalizeCategories(categories)
        var addedNewKnown = false
        val canonicalList = mutableListOf<String>()
        for (cat in normalized) {
            val existing = findCanonical(cat)
            if (existing != null) {
                canonicalList.add(existing)
            } else {
                knownCategories.add(cat)
                addedNewKnown = true
                canonicalList.add(cat)
            }
        }
        if (addedNewKnown) {
            knownCategories.sortWith(String.CASE_INSENSITIVE_ORDER)
        }
        selectedCategories.clear()
        selectedCategories.addAll(canonicalList)
        renderChips()
        if (notify) {
            onSelectionChanged(getSelectedCategories())
        }
    }

    private fun findCanonical(category: String): String? {
        return knownCategories.firstOrNull { it.equals(category, ignoreCase = true) }
    }

    private fun isSelected(category: String): Boolean {
        return selectedCategories.any { it.equals(category, ignoreCase = true) }
    }

    private fun renderChips() {
        val inflater = LayoutInflater.from(activity)
        chipGroup.removeAllViews()

        for (category in knownCategories) {
            val chip = inflater.inflate(R.layout.item_category_filter_chip, chipGroup, false) as Chip
            chip.text = category
            chip.isChecked = isSelected(category)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (!isSelected(category)) {
                        selectedCategories.add(category)
                    }
                } else {
                    selectedCategories.removeAll { it.equals(category, ignoreCase = true) }
                }
                onSelectionChanged(getSelectedCategories())
            }
            chipGroup.addView(chip)
        }

        val addChip = inflater.inflate(R.layout.item_add_category_chip, chipGroup, false) as Chip
        addChip.setOnClickListener {
            showAddCategoryDialog()
        }
        chipGroup.addView(addChip)
    }

    private fun showAddCategoryDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_add_category, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.category_edit_text)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.add_category)
            .setView(dialogView)
            .setPositiveButton(R.string.add, null)
            .setNegativeButton(R.string.cancel_text, null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val submitNewCategory = {
            val entered = editText.text?.toString()?.trim().orEmpty()
            if (entered.isNotEmpty()) {
                val existing = findCanonical(entered)
                val target = if (existing != null) {
                    existing
                } else {
                    knownCategories.add(entered)
                    knownCategories.sortWith(String.CASE_INSENSITIVE_ORDER)
                    entered
                }
                if (!isSelected(target)) {
                    selectedCategories.add(target)
                }
                AuthenticationUtil.dismissKeyDialogSafely(dialog, editText)
                renderChips()
                onSelectionChanged(getSelectedCategories())
            }
        }

        dialog.setOnShowListener {
            AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = !editText.text?.toString()?.trim().isNullOrEmpty()

            editText.doAfterTextChanged { text ->
                positiveButton.isEnabled = !text?.toString()?.trim().isNullOrEmpty()
            }
            positiveButton.setOnClickListener {
                submitNewCategory()
            }
            editText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE && positiveButton.isEnabled) {
                    submitNewCategory()
                    true
                } else {
                    false
                }
            }
            editText.requestFocus()
        }
        dialog.setOnDismissListener {
            AuthenticationUtil.clearAndCancelAutofill(activity, editText)
        }
        AuthenticationUtil.disableAutofillForDialog(dialog, dialogView)
        dialog.show()
    }

    companion object {
        private const val STATE_KNOWN_CATEGORIES = "category_chip_known_categories"
        private const val STATE_SELECTED_CATEGORIES = "category_chip_selected_categories"
    }
}
