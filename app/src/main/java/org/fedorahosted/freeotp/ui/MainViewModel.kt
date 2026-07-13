package org.fedorahosted.freeotp.ui

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
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenMigrationUtil: MigrationUtil,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val settings: Settings
) : ViewModel() {

    private val tokenSearchQuery = MutableStateFlow("")
    private val categoryFilter = MutableStateFlow<String?>(null)
    private val authState = MutableStateFlow(
        if (settings.requireAuthentication) {
            AuthState.UNAUTHENTICATED
        } else {
            AuthState.AUTHENTICATED
        }
    )

    private var lastSessionEndTimestamp = 0L;

    fun migrateOldData() {
        viewModelScope.launch {
            if (!tokenMigrationUtil.isMigrated()) {
                tokenMigrationUtil.migrate()
            }
        }
    }

    fun setTokenSearchQuery(query: String) {
        tokenSearchQuery.value = query
    }

    fun setCategoryFilter(category: String?) {
        categoryFilter.value = category
    }

    fun setAuthState(auth: AuthState) {
        authState.value = auth
    }

    fun getAuthState(): Flow<AuthState> = authState

    fun getAllCategories(): Flow<List<String>> = otpTokenDatabase.otpTokenDao().getAllCategories()

    fun hasUncategorizedTokens(): Flow<Boolean> = otpTokenDatabase.otpTokenDao().hasUncategorizedTokens()

    fun getTokenList(): Flow<List<OtpToken>> {
        return combine(authState, tokenSearchQuery, categoryFilter, otpTokenDatabase.otpTokenDao().getAll()) {auth, searchQuery, category, tokens ->
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
                        token.category.isNullOrBlank()
                    } else {
                        token.category == category
                    }
                }
            }

            filteredTokens
        }
    }

    fun onSessionStart() {
        if (settings.requireAuthentication && (System.currentTimeMillis() - lastSessionEndTimestamp) > TIMEOUT_DELAY_MS) {
            setAuthState(AuthState.UNAUTHENTICATED)
        } else {
            setAuthState(AuthState.AUTHENTICATED)
        }
    }

    fun onSessionStop() {
        lastSessionEndTimestamp = System.currentTimeMillis()
    }

    enum class AuthState {
        AUTHENTICATED,
        UNAUTHENTICATED
    }

    companion object {
        private const val TIMEOUT_DELAY_MS: Long = 120 * 1000L;
        const val CATEGORY_UNCATEGORIZED = "uncategorized_special_value"
    }

}
