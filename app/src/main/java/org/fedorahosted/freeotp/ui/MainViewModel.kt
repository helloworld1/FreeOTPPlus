package org.fedorahosted.freeotp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.TokenSortOrder
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.util.AppSessionManager
import org.fedorahosted.freeotp.util.CATEGORY_UNCATEGORIZED_VALUE
import org.fedorahosted.freeotp.util.CopyToClipboardMode
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.ThemeMode
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenMigrationUtil: MigrationUtil,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val appSessionManager: AppSessionManager,
    private val settings: Settings,
) : ViewModel() {

    data class ActiveTokenCodeEntry(
        val code: TokenCode,
        val generation: Int,
    )

    private val tokenSearchQuery = MutableStateFlow("")
    val searchQueryFlow: StateFlow<String> = tokenSearchQuery.asStateFlow()

    private val categoryFilter = MutableStateFlow<String?>(settings.selectedCategory)
    val categoryFilterFlow: StateFlow<String?> = categoryFilter.asStateFlow()

    private val imageRefreshVersion = MutableStateFlow(0L)
    val imageRefreshVersionFlow: StateFlow<Long> = imageRefreshVersion.asStateFlow()

    private val _activeTokenCodes = MutableStateFlow<Map<Long, ActiveTokenCodeEntry>>(emptyMap())
    val activeTokenCodesFlow: StateFlow<Map<Long, ActiveTokenCodeEntry>> = _activeTokenCodes.asStateFlow()

    val authStateFlow: StateFlow<AuthState> = appSessionManager.authState
    val themeModeFlow: StateFlow<ThemeMode> = settings.themeModeFlow
    val authModeFlow: StateFlow<AuthMode> = settings.authModeFlow
    val copyToClipboardModeFlow: StateFlow<CopyToClipboardMode> = settings.copyToClipboardModeFlow
    val copyToClipboardFlow: StateFlow<Boolean> = settings.copyToClipboardFlow

    init {
        viewModelScope.launch {
            otpTokenDatabase.otpTokenDao().getAllCategories().collect { availableCategories ->
                val current = categoryFilter.value
                if (current != null && current != CATEGORY_UNCATEGORIZED) {
                    if (!tokenMigrationUtil.isMigrated()) {
                        return@collect
                    }
                    val canonicalMatch = availableCategories.firstOrNull {
                        it.equals(current, ignoreCase = true)
                    }
                    if (canonicalMatch == null) {
                        categoryFilter.value = null
                        settings.selectedCategory = null
                    } else if (canonicalMatch != current) {
                        categoryFilter.value = canonicalMatch
                        settings.selectedCategory = canonicalMatch
                    }
                }
            }
        }
        viewModelScope.launch {
            appSessionManager.authState.collect { state ->
                if (state == AuthState.UNAUTHENTICATED) {
                    _activeTokenCodes.value = emptyMap()
                }
            }
        }
    }

    fun migrateOldData() {
        viewModelScope.launch {
            if (!tokenMigrationUtil.isMigrated()) {
                tokenMigrationUtil.migrate()
            } else {
                tokenMigrationUtil.purgeLegacyTokensIfMigrated()
            }
        }
    }

    fun setTokenSearchQuery(query: String) {
        tokenSearchQuery.value = query
    }

    fun getCurrentSearchQuery(): String = tokenSearchQuery.value

    fun setCategoryFilter(category: String?) {
        categoryFilter.value = category
        settings.selectedCategory = category
    }

    fun getCategoryFilter(): Flow<String?> = categoryFilter

    fun getCurrentCategoryFilter(): String? = categoryFilter.value

    fun getImageRefreshVersion(): Flow<Long> = imageRefreshVersion

    fun notifyTokenImagesChanged() {
        imageRefreshVersion.value++
    }

    fun sortTokens(order: TokenSortOrder) {
        viewModelScope.launch {
            otpTokenDatabase.otpTokenDao().sortAll(order)
        }
    }

    fun moveTokenPairs(pairs: List<Pair<Long, Long>>) {
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            otpTokenDatabase.otpTokenDao().movePairs(pairs)
        }
    }

    fun putActiveTokenCode(tokenId: Long, code: TokenCode) {
        _activeTokenCodes.update { existing ->
            val nextGen = (existing[tokenId]?.generation ?: 0) + 1
            existing + (tokenId to ActiveTokenCodeEntry(code = code, generation = nextGen))
        }
    }

    fun getActiveTokenCode(tokenId: Long): TokenCode? {
        val entry = _activeTokenCodes.value[tokenId] ?: return null
        if (entry.code.currentCode == null) {
            removeActiveTokenCode(tokenId)
            return null
        }
        return entry.code
    }

    fun removeActiveTokenCode(tokenId: Long) {
        _activeTokenCodes.update { existing ->
            if (tokenId in existing) existing - tokenId else existing
        }
    }

    fun clearActiveTokenCodes() {
        _activeTokenCodes.value = emptyMap()
    }

    fun setAuthState(auth: AuthState) {
        appSessionManager.setAuthState(auth)
        if (auth == AuthState.AUTHENTICATED && !tokenMigrationUtil.isMigrated()) {
            migrateOldData()
        }
    }

    fun isAuthenticated(): Boolean = appSessionManager.isAuthenticated()

    fun getAuthState(): Flow<AuthState> = appSessionManager.authState

    fun getAllCategories(): Flow<List<String>> = otpTokenDatabase.otpTokenDao().getAllCategories()

    data class TokenListState(
        val tokens: List<OtpToken>,
        val hasInstalledTokens: Boolean,
        val hasActiveFilterOrSearch: Boolean,
        val isAuthenticated: Boolean,
    )

    fun getTokenListState(): Flow<TokenListState> {
        return combine(
            appSessionManager.authState,
            tokenSearchQuery,
            categoryFilter,
            otpTokenDatabase.otpTokenDao().getAll(),
        ) { auth, searchQuery, category, tokens ->
            if (auth != AuthState.AUTHENTICATED) {
                return@combine TokenListState(
                    tokens = emptyList(),
                    hasInstalledTokens = false,
                    hasActiveFilterOrSearch = false,
                    isAuthenticated = false,
                )
            }

            var filteredTokens = tokens
            if (searchQuery.isNotEmpty()) {
                filteredTokens = filteredTokens.filter { token ->
                    token.label.contains(searchQuery, true) ||
                        token.issuer?.contains(searchQuery, true) ?: false
                }
            }

            if (category != null) {
                filteredTokens = filteredTokens.filter { token ->
                    if (category == CATEGORY_UNCATEGORIZED) {
                        token.categories.isEmpty()
                    } else {
                        token.categories.any { it.equals(category, ignoreCase = true) }
                    }
                }
            }

            TokenListState(
                tokens = filteredTokens,
                hasInstalledTokens = tokens.isNotEmpty(),
                hasActiveFilterOrSearch = searchQuery.isNotEmpty() || category != null,
                isAuthenticated = true,
            )
        }
    }

    fun getTokenList(): Flow<List<OtpToken>> {
        return getTokenListState().map { it.tokens }
    }

    fun lockSession() {
        _activeTokenCodes.value = emptyMap()
        appSessionManager.lockSession()
    }

    fun onSessionStart() {
        settings.refreshFlows()
        val previousSelected = settings.selectedCategory
        if (categoryFilter.value != previousSelected) {
            categoryFilter.value = previousSelected
        }
        appSessionManager.refreshSessionState()
    }

    enum class AuthState {
        AUTHENTICATED,
        UNAUTHENTICATED,
    }

    companion object {
        const val CATEGORY_UNCATEGORIZED = CATEGORY_UNCATEGORIZED_VALUE
    }
}
