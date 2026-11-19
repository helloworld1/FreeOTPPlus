package org.fedorahosted.freeotp.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.ui.MainActivity
import org.fedorahosted.freeotp.ui.MainViewModel.AuthState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSessionManager @Inject constructor(
    private val settings: Settings,
    private val tokenCryptoManager: TokenCryptoManager
) : Application.ActivityLifecycleCallbacks {

    @VisibleForTesting
    var timeoutDelayMs: Long = DEFAULT_TIMEOUT_DELAY_MS

    @VisibleForTesting
    var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundLockRunnable = Runnable {
        if (startedActivityCount == 0 && settings.authMode != AuthMode.NONE) {
            lockSession()
        }
    }

    private var hasAuthenticatedSession = false
    private var startedActivityCount = 0
    private var backgroundedAtElapsedRealtime: Long? = null

    private val _authState = MutableStateFlow(computeCurrentAuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private fun computeCurrentAuthState(): AuthState {
        return when (settings.authMode) {
            AuthMode.NONE -> AuthState.AUTHENTICATED
            AuthMode.APP_LAUNCH -> {
                if (hasAuthenticatedSession) AuthState.AUTHENTICATED else AuthState.UNAUTHENTICATED
            }
            AuthMode.TOKEN_ENCRYPTED -> {
                if (tokenCryptoManager.isUnlocked()) {
                    hasAuthenticatedSession = true
                    AuthState.AUTHENTICATED
                } else {
                    hasAuthenticatedSession = false
                    AuthState.UNAUTHENTICATED
                }
            }
        }
    }

    @MainThread
    fun refreshSessionState(): Boolean {
        val mode = settings.authMode
        if (mode == AuthMode.NONE) {
            cancelBackgroundLockTimer()
            backgroundedAtElapsedRealtime = null
            _authState.value = AuthState.AUTHENTICATED
            return true
        }

        val bgAt = backgroundedAtElapsedRealtime
        val timedOut = bgAt != null && (elapsedRealtimeProvider() - bgAt) > timeoutDelayMs
        if (timedOut) {
            lockSession()
            return false
        }

        val nextState = computeCurrentAuthState()
        _authState.value = nextState
        return nextState == AuthState.AUTHENTICATED
    }

    @MainThread
    fun isAuthenticated(): Boolean {
        return refreshSessionState()
    }

    @MainThread
    fun setAuthState(state: AuthState) {
        if (state == AuthState.AUTHENTICATED) {
            hasAuthenticatedSession = true
            cancelBackgroundLockTimer()
            if (startedActivityCount > 0) {
                backgroundedAtElapsedRealtime = null
            } else {
                backgroundedAtElapsedRealtime = elapsedRealtimeProvider()
                scheduleBackgroundLockTimer()
            }
            _authState.value = AuthState.AUTHENTICATED
        } else {
            lockSession()
        }
    }

    @MainThread
    fun lockSession() {
        cancelBackgroundLockTimer()
        hasAuthenticatedSession = false
        backgroundedAtElapsedRealtime = null
        tokenCryptoManager.lock()
        _authState.value = if (settings.authMode == AuthMode.NONE) {
            AuthState.AUTHENTICATED
        } else {
            AuthState.UNAUTHENTICATED
        }
    }

    private fun scheduleBackgroundLockTimer() {
        cancelBackgroundLockTimer()
        mainHandler.postDelayed(backgroundLockRunnable, timeoutDelayMs)
    }

    private fun cancelBackgroundLockTimer() {
        mainHandler.removeCallbacks(backgroundLockRunnable)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) {
            return
        }
        startedActivityCount++
        cancelBackgroundLockTimer()
        refreshSessionState()
        backgroundedAtElapsedRealtime = null
    }

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        if (activity !is MainActivity) {
            return
        }
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
        if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
            if (settings.authMode != AuthMode.NONE && _authState.value == AuthState.AUTHENTICATED) {
                backgroundedAtElapsedRealtime = elapsedRealtimeProvider()
                scheduleBackgroundLockTimer()
            }
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        const val DEFAULT_TIMEOUT_DELAY_MS: Long = 120 * 1000L
    }
}
