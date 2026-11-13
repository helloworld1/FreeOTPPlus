package org.fedorahosted.freeotp.util

import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.fedorahosted.freeotp.R

object AuthenticationUtil {
    const val AUTHENTICATORS =
        BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK

    @VisibleForTesting
    @Volatile
    var hasScreenLockOverride: Boolean? = null

    @VisibleForTesting
    @Volatile
    var authCallbackOverride: ((onAuthenticated: () -> Unit) -> Unit)? = null

    fun hasScreenLock(context: Context): Boolean {
        hasScreenLockOverride?.let { return it }
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isDeviceSecure != true) {
            return false
        }
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateIfScreenLockEnabled(
        activity: AppCompatActivity,
        onAuthenticated: () -> Unit
    ) {
        if (!hasScreenLock(activity)) {
            onAuthenticated()
            return
        }

        authCallbackOverride?.let { override ->
            override {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onAuthenticated()
                }
            }
            return
        }

        val applicationContext = activity.applicationContext
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        Toast.makeText(
                            applicationContext,
                            "${activity.getString(R.string.authentication_error)} $errString",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onAuthenticated()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        applicationContext,
                        R.string.unable_to_authenticate,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.authentication_dialog_title))
            .setSubtitle(activity.getString(R.string.authentication_dialog_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
