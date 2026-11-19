package org.fedorahosted.freeotp.ui

import android.util.ArrayMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.legacy.TokenCode
import org.fedorahosted.freeotp.util.AppSessionManager
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenMigrationUtil: MigrationUtil,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val appSessionManager: AppSessionManager
) : ViewModel() {

    private val tokenSearchQuery = MutableStateFlow("")
    private val categoryFilter = MutableStateFlow<String?>(null)
    private val activeTokenCodes: MutableMap<Long, TokenCode> = ArrayMap()

    init {
        viewModelScope.launch {
            otpTokenDatabase.otpTokenDao().getAllCategories().collect { availableCategories ->
                val current = categoryFilter.value
                if (current != null &&
                    current != CATEGORY_UNCATEGORIZED &&
                    availableCategories.none { it.equals(current, ignoreCase = true) }
                ) {
                    categoryFilter.value = null
                }
            }
        }
        viewModelScope.launch {
            appSessionManager.authState.collect { state ->
                if (state == AuthState.UNAUTHENTICATED) {
                    activeTokenCodes.clear()
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
    }

    fun getCategoryFilter(): Flow<String?> = categoryFilter

    fun getCurrentCategoryFilter(): String? = categoryFilter.value

    fun putActiveTokenCode(tokenId: Long, code: TokenCode) {
        activeTokenCodes[tokenId] = code
    }

    fun getActiveTokenCode(tokenId: Long): TokenCode? {
        val code = activeTokenCodes[tokenId] ?: return null
        if (code.currentCode == null) {
            activeTokenCodes.remove(tokenId)
            return null
        }
        return code
    }

    fun removeActiveTokenCode(tokenId: Long) {
        activeTokenCodes.remove(tokenId)
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

    fun getTokenList(): Flow<List<OtpToken>> {
        return combine(appSessionManager.authState, tokenSearchQuery, categoryFilter, otpTokenDatabase.otpTokenDao().getAll()) { auth, searchQuery, category, tokens ->
            if (auth != AuthState.AUTHENTICATED) {
                return@combine emptyList()
            }

            var filteredTokens = tokens
            if (searchQuery.isNotEmpty()) {
                filteredTokens = filteredTokens.filter { token ->
                    token.label.contains(searchQuery, true)
                        || token.issuer?.contains(searchQuery, true) ?: false
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

            filteredTokens
        }
    }

    fun lockSession() {
        activeTokenCodes.clear()
        appSessionManager.lockSession()
    }

    fun onSessionStart() {
        appSessionManager.refreshSessionState()
    }

    enum class AuthState {
        AUTHENTICATED,
        UNAUTHENTICATED
    }

    companion object {
        const val CATEGORY_UNCATEGORIZED = "uncategorized_special_value"
    }
}
