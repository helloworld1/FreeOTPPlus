package org.fedorahosted.freeotp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.MigrationUtil
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.util.Settings
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val tokenMigrationUtil: MigrationUtil,
    private val otpTokenDatabase: OtpTokenDatabase,
    private val settings: Settings,
    private val tokenCryptoManager: TokenCryptoManager
) : ViewModel() {

    private val tokenSearchQuery = MutableStateFlow("")
    private val categoryFilter = MutableStateFlow<String?>(null)
    private val authState = MutableStateFlow(computeInitialAuthState())

    private var lastSessionEndTimestamp = 0L

    private fun computeInitialAuthState(): AuthState {
        return when (settings.authMode) {
            AuthMode.NONE -> AuthState.AUTHENTICATED
            AuthMode.APP_LAUNCH -> AuthState.UNAUTHENTICATED
            AuthMode.TOKEN_ENCRYPTED -> {
                if (tokenCryptoManager.isUnlocked()) {
                    AuthState.AUTHENTICATED
                } else {
                    AuthState.UNAUTHENTICATED
                }
            }
        }
    }

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

    fun getCategoryFilter(): Flow<String?> = categoryFilter

    fun getCurrentCategoryFilter(): String? = categoryFilter.value

    fun setAuthState(auth: AuthState) {
        if (auth == AuthState.AUTHENTICATED) {
            lastSessionEndTimestamp = System.currentTimeMillis()
        }
        authState.value = auth
    }

    fun isAuthenticated(): Boolean = authState.value == AuthState.AUTHENTICATED

    fun getAuthState(): Flow<AuthState> = authState

    fun getAllCategories(): Flow<List<String>> = otpTokenDatabase.otpTokenDao().getAllCategories()

    fun getTokenList(): Flow<List<OtpToken>> {
        return combine(authState, tokenSearchQuery, categoryFilter, otpTokenDatabase.otpTokenDao().getAll()) { auth, searchQuery, category, tokens ->
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

    fun keepSessionAliveForResult() {
        if (authState.value == AuthState.AUTHENTICATED) {
            lastSessionEndTimestamp = System.currentTimeMillis()
        }
    }

    fun lockSession() {
        tokenCryptoManager.lock()
        lastSessionEndTimestamp = 0L
        authState.value = AuthState.UNAUTHENTICATED
    }

    fun onSessionStart() {
        when (settings.authMode) {
            AuthMode.NONE -> {
                setAuthState(AuthState.AUTHENTICATED)
            }
            AuthMode.APP_LAUNCH -> {
                val timedOut = (System.currentTimeMillis() - lastSessionEndTimestamp) > TIMEOUT_DELAY_MS
                if (authState.value != AuthState.AUTHENTICATED || timedOut) {
                    setAuthState(AuthState.UNAUTHENTICATED)
                }
            }
            AuthMode.TOKEN_ENCRYPTED -> {
                val timedOut = (System.currentTimeMillis() - lastSessionEndTimestamp) > TIMEOUT_DELAY_MS
                if (authState.value != AuthState.AUTHENTICATED || !tokenCryptoManager.isUnlocked() || timedOut) {
                    tokenCryptoManager.lock()
                    setAuthState(AuthState.UNAUTHENTICATED)
                }
            }
        }
    }

    fun onSessionStop() {
        if (authState.value == AuthState.AUTHENTICATED) {
            lastSessionEndTimestamp = System.currentTimeMillis()
        }
    }

    enum class AuthState {
        AUTHENTICATED,
        UNAUTHENTICATED
    }

    companion object {
        private const val TIMEOUT_DELAY_MS: Long = 120 * 1000L
        const val CATEGORY_UNCATEGORIZED = "uncategorized_special_value"
    }

}
