package org.fedorahosted.freeotp.ui.addedit

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.apps.authenticator.Base32String
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.OtpToken
import org.fedorahosted.freeotp.data.OtpTokenDatabase
import org.fedorahosted.freeotp.data.OtpTokenFactory
import org.fedorahosted.freeotp.data.OtpTokenType
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.data.util.CategoryUtil
import org.fedorahosted.freeotp.data.util.TokenImageBackupUtil
import org.fedorahosted.freeotp.util.ImageUtil
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject

data class AddTokenUiState(
    val issuer: String = "",
    val label: String = "",
    val secret: String = "",
    val tokenType: OtpTokenType = OtpTokenType.TOTP,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val interval: String = "30",
    val counter: String = "0",
    val selectedImagePath: String? = null,
    val knownCategories: List<String> = emptyList(),
    val selectedCategories: List<String> = emptyList(),
    val isSubmitting: Boolean = false,
) {
    val isValid: Boolean
        get() {
            val intervalValid = tokenType != OtpTokenType.TOTP ||
                (interval.toIntOrNull()?.let { it > 0 } == true)
            val counterValid = tokenType != OtpTokenType.HOTP || counter.toLongOrNull() != null
            return label.isNotEmpty() &&
                secret.trimEnd('=').length >= 8 &&
                intervalValid &&
                counterValid &&
                !isSubmitting
        }
}

sealed interface AddTokenSubmitResult {
    data class Success(val insertedTokenId: Long, val addedToken: OtpToken) : AddTokenSubmitResult
    data object VaultLocked : AddTokenSubmitResult
    data class Error(val messageRes: Int) : AddTokenSubmitResult
}

@HiltViewModel
class AddTokenViewModel @Inject constructor(
    private val otpTokenDatabase: OtpTokenDatabase,
    private val tokenCryptoManager: TokenCryptoManager,
    private val imageUtil: ImageUtil,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddTokenUiState())
    val uiState: StateFlow<AddTokenUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val dbCategories = otpTokenDatabase.otpTokenDao().getAllCategories().first()
            _uiState.update { state ->
                val merged = CategoryUtil.normalizeCategories(dbCategories + state.knownCategories)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                state.copy(knownCategories = merged)
            }
        }
    }

    fun updateIssuer(value: String) {
        _uiState.update { it.copy(issuer = value) }
    }

    fun updateLabel(value: String) {
        _uiState.update { it.copy(label = value) }
    }

    fun updateSecret(rawValue: String) {
        val cleaned = normalizeTypedBase32Secret(rawValue)
        _uiState.update { it.copy(secret = cleaned) }
    }

    fun generateRandomSecret() {
        val generated = generateStrongBase32Secret(_uiState.value.algorithm)
        _uiState.update { it.copy(secret = generated) }
    }

    fun updateTokenType(type: OtpTokenType) {
        _uiState.update { it.copy(tokenType = type) }
    }

    fun updateDigits(digits: Int) {
        _uiState.update { it.copy(digits = digits) }
    }

    fun updateAlgorithm(algorithm: String) {
        _uiState.update { it.copy(algorithm = algorithm) }
    }

    fun updateInterval(value: String) {
        _uiState.update { it.copy(interval = value.filter { ch -> ch.isDigit() }) }
    }

    fun updateCounter(value: String) {
        _uiState.update { it.copy(counter = value.filter { ch -> ch.isDigit() }) }
    }

    fun selectBuiltInIcon(iconName: String) {
        val safePath = TokenImageBackupUtil.resolveSafeTokenImagePath(
            TokenImageBackupUtil.toBuiltInImagePath(iconName),
            appContext.filesDir,
        )
        _uiState.update { it.copy(selectedImagePath = safePath) }
    }

    fun savePickedDeviceImage(sourceUri: Uri, onError: () -> Unit) {
        viewModelScope.launch {
            try {
                val savedUri = imageUtil.saveImageUriToFile(sourceUri)
                val safePath = TokenImageBackupUtil.resolveSafeTokenImagePath(
                    savedUri.toString(),
                    appContext.filesDir,
                )
                _uiState.update { it.copy(selectedImagePath = safePath) }
            } catch (_: Exception) {
                onError()
            }
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

    fun submitNewToken(onResult: (AddTokenSubmitResult) -> Unit) {
        val current = _uiState.value
        if (!current.isValid) return
        val interval = current.interval.toIntOrNull() ?: return
        if (interval <= 0) return
        val initialCounter = if (current.tokenType == OtpTokenType.HOTP) {
            current.counter.toLongOrNull() ?: return
        } else {
            0L
        }

        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val token = OtpTokenFactory.create(
                    type = current.tokenType,
                    issuer = current.issuer,
                    label = current.label,
                    secret = current.secret,
                    algorithm = current.algorithm.ifBlank { "SHA1" },
                    digits = current.digits,
                    period = interval,
                    initialCounter = initialCounter,
                    imagePath = current.selectedImagePath,
                    categories = current.selectedCategories,
                )
                val insertedId = otpTokenDatabase.otpTokenDao().insertAtTop(
                    tokenCryptoManager.encryptTokenForStorage(token)
                )
                onResult(AddTokenSubmitResult.Success(insertedId, token))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                _uiState.update { it.copy(isSubmitting = false) }
                if (!tokenCryptoManager.isUnlocked()) {
                    onResult(AddTokenSubmitResult.VaultLocked)
                } else {
                    onResult(AddTokenSubmitResult.Error(R.string.invalid_token))
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isSubmitting = false) }
                onResult(AddTokenSubmitResult.Error(R.string.invalid_token_uri_received))
            }
        }
    }

    companion object {
        private val SECURE_RANDOM = SecureRandom()
        internal const val DEFAULT_SECRET_BYTES = 20 // 160 bits (32 Base32 chars) for SHA1 / MD5
        internal const val SHA256_SECRET_BYTES = 32 // 256 bits (52 Base32 chars) for SHA256
        internal const val SHA512_SECRET_BYTES = 64 // 512 bits (103 Base32 chars) for SHA512

        internal fun secretByteLengthForAlgorithm(algorithm: String): Int {
            return when (algorithm.trim().uppercase(Locale.US)) {
                "SHA256" -> SHA256_SECRET_BYTES
                "SHA512" -> SHA512_SECRET_BYTES
                else -> DEFAULT_SECRET_BYTES
            }
        }

        internal fun generateStrongBase32Secret(
            algorithm: String = "SHA1",
            secureRandom: SecureRandom = SECURE_RANDOM,
        ): String {
            val bytes = ByteArray(secretByteLengthForAlgorithm(algorithm))
            return try {
                secureRandom.nextBytes(bytes)
                Base32String.encode(bytes)
            } finally {
                bytes.fill(0)
            }
        }

        internal fun normalizeTypedBase32Secret(raw: String): String {
            if (raw.isEmpty()) return ""
            val filtered = buildString(raw.length) {
                for (ch in raw) {
                    if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '2'..'7' || ch == '=') {
                        append(ch)
                    }
                }
            }
            if (filtered.isEmpty()) return ""
            // Strip non-trailing '=' padding characters (matching legacy AddTokenFragment behavior)
            val result = StringBuilder(filtered.length)
            var haveNonPaddingData = false
            for (i in filtered.length - 1 downTo 0) {
                val ch = filtered[i]
                if (ch != '=') {
                    haveNonPaddingData = true
                    result.append(ch)
                } else if (!haveNonPaddingData) {
                    result.append(ch)
                }
            }
            return result.reverse().toString()
        }
    }
}
