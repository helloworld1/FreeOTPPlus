package org.fedorahosted.freeotp.ui.addedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.util.ImageUtil
import java.util.Locale
import javax.inject.Inject

data class EditTokenUiState(
    val loadedTokenId: Long? = null,
    val storedToken: OtpToken? = null,
    val tokenMissing: Boolean = false,
    val initialIssuer: String = "",
    val initialLabel: String = "",
    val initialCategories: List<String> = emptyList(),
    val initialImagePath: String? = null,
    val issuer: String = "",
    val label: String = "",
    val displayedImagePath: String? = null,
    val knownCategories: List<String> = emptyList(),
    val selectedCategories: List<String> = emptyList(),
    val isSaving: Boolean = false,
) {
    val isLoaded: Boolean
        get() = storedToken != null

    val hasChanges: Boolean
        get() {
            if (!isLoaded) return false
            return label != initialLabel ||
                issuer != initialIssuer ||
                categoriesDiffer(selectedCategories, initialCategories) ||
                displayedImagePath != initialImagePath
        }

    val canSave: Boolean
        get() = isLoaded && label.isNotEmpty() && hasChanges && !isSaving

    val canRestore: Boolean
        get() = isLoaded && hasChanges && !isSaving

    companion object {
        private fun categoriesDiffer(first: List<String>, second: List<String>): Boolean {
            val setA = CategoryUtil.normalizeCategories(first).map { it.lowercase(Locale.ROOT) }.toSet()
            val setB = CategoryUtil.normalizeCategories(second).map { it.lowercase(Locale.ROOT) }.toSet()
            return setA != setB
        }
    }
}

@HiltViewModel
class EditTokenViewModel @Inject constructor(
    private val otpTokenDatabase: OtpTokenDatabase,
    private val imageUtil: ImageUtil,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EditTokenUiState())
    val uiState: StateFlow<EditTokenUiState> = _uiState.asStateFlow()

    fun loadTokenIfNeeded(tokenId: Long) {
        if (tokenId == 0L) {
            _uiState.update { it.copy(tokenMissing = true) }
            return
        }
        if (_uiState.value.loadedTokenId == tokenId) {
            return
        }
        _uiState.update { it.copy(loadedTokenId = tokenId) }
        viewModelScope.launch {
            val token = otpTokenDatabase.otpTokenDao().get(tokenId).first()
            if (token == null) {
                _uiState.update { it.copy(tokenMissing = true) }
                return@launch
            }
            val dbCategories = otpTokenDatabase.otpTokenDao().getAllCategories().first()
            val normalizedInitialCategories = CategoryUtil.normalizeCategories(token.categories)
            val mergedKnown = CategoryUtil.normalizeCategories(dbCategories + normalizedInitialCategories)
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            val canonicalSelected = normalizedInitialCategories.map { sel ->
                mergedKnown.firstOrNull { it.equals(sel, ignoreCase = true) } ?: sel
            }
            val safeImagePath = TokenImageBackupUtil.resolveSafeTokenImagePath(
                token.imagePath,
                appContext.filesDir,
            )

            _uiState.update {
                EditTokenUiState(
                    loadedTokenId = tokenId,
                    storedToken = token,
                    tokenMissing = false,
                    initialIssuer = token.issuer ?: "",
                    initialLabel = token.label,
                    initialCategories = canonicalSelected,
                    initialImagePath = safeImagePath,
                    issuer = token.issuer ?: "",
                    label = token.label,
                    displayedImagePath = safeImagePath,
                    knownCategories = mergedKnown,
                    selectedCategories = canonicalSelected,
                    isSaving = false,
                )
            }
        }
    }

    fun updateIssuer(value: String) {
        _uiState.update { it.copy(issuer = value) }
    }

    fun updateLabel(value: String) {
        _uiState.update { it.copy(label = value) }
    }

    fun selectBuiltInIcon(iconName: String) {
        val safePath = TokenImageBackupUtil.resolveSafeTokenImagePath(
            TokenImageBackupUtil.toBuiltInImagePath(iconName),
            appContext.filesDir,
        )
        _uiState.update { it.copy(displayedImagePath = safePath) }
    }

    fun savePickedDeviceImage(sourceUri: Uri, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                val savedUri = imageUtil.saveImageUriToFile(sourceUri)
                val safePath = TokenImageBackupUtil.resolveSafeTokenImagePath(
                    savedUri.toString(),
                    appContext.filesDir,
                )
                _uiState.update { it.copy(displayedImagePath = safePath) }
            } catch (_: Exception) {
                onError()
            }
        }
    }

    fun removeTokenImage() {
        _uiState.update { it.copy(displayedImagePath = null) }
    }

    fun restoreDefaults() {
        _uiState.update { state ->
            state.copy(
                issuer = state.initialIssuer,
                label = state.initialLabel,
                displayedImagePath = state.initialImagePath,
                selectedCategories = state.initialCategories,
            )
        }
    }

    fun toggleCategory(category: String) {
        _uiState.update { state ->
            val exists = state.selectedCategories.any { it.equals(category, ignoreCase = true) }
            val nextSelected = if (exists) {
                state.selectedCategories.filterNot { it.equals(category, ignoreCase = true) }
            } else {
                state.selectedCategories + category
            }
            state.copy(selectedCategories = nextSelected)
        }
    }

    fun createAndSelectCategory(rawCategory: String) {
        val entered = rawCategory.trim()
        if (entered.isEmpty()) return
        _uiState.update { state ->
            val canonical = state.knownCategories.firstOrNull { it.equals(entered, ignoreCase = true) }
                ?: entered
            val nextKnown = if (state.knownCategories.any { it.equals(canonical, ignoreCase = true) }) {
                state.knownCategories
            } else {
                (state.knownCategories + canonical).sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
            val nextSelected = if (state.selectedCategories.any { it.equals(canonical, ignoreCase = true) }) {
                state.selectedCategories
            } else {
                state.selectedCategories + canonical
            }
            state.copy(
                knownCategories = nextKnown,
                selectedCategories = nextSelected,
            )
        }
    }

    fun saveTokenChanges(onSaved: () -> Unit) {
        val current = _uiState.value
        val tokenId = current.loadedTokenId ?: return
        if (!current.canSave) return

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val latest = otpTokenDatabase.otpTokenDao().get(tokenId).first()
            if (latest != null) {
                val updated = latest.copy(
                    issuer = current.issuer.takeIf { it.isNotBlank() },
                    label = current.label,
                    categories = current.selectedCategories,
                    imagePath = current.displayedImagePath,
                )
                otpTokenDatabase.otpTokenDao().update(updated)
            }
            onSaved()
        }
    }
}
