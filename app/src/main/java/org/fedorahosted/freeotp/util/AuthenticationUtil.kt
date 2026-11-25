package org.fedorahosted.freeotp.util

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.text.Editable
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
import org.fedorahosted.freeotp.ui.MainActivity
import javax.crypto.Cipher

object AuthenticationUtil {
    const val AUTHENTICATORS =
        BiometricManager.Authenticators.DEVICE_CREDENTIAL or BiometricManager.Authenticators.BIOMETRIC_WEAK

    @VisibleForTesting
    @Volatile
    var hasScreenLockOverride: Boolean? = null

    @VisibleForTesting
    @Volatile
    var hasStrongBiometricOverride: Boolean? = null

    @VisibleForTesting
    @Volatile
    var authCallbackOverride: ((onAuthenticated: () -> Unit) -> Unit)? = null

    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    @Volatile
    var appLaunchAuthCallbackOverride: ((onSuccess: () -> Unit, onCancel: () -> Unit) -> Unit)? = null

    @VisibleForTesting
    @Volatile
    var biometricCryptoCallbackOverride: ((
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFallbackToManual: (userInitiated: Boolean) -> Unit,
        onCancel: () -> Unit
    ) -> Unit)? = null

    fun hasScreenLock(context: Context): Boolean {
        hasScreenLockOverride?.let { return it }
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    fun canAuthenticateStrongBiometric(context: Context): Boolean {
        hasStrongBiometricOverride?.let { return it }
        val status = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return resolveStrongBiometricAvailability(
            sdkInt = Build.VERSION.SDK_INT,
            biometricManagerStatus = status,
            hasScreenLock = { hasScreenLock(context) },
            fingerprintServiceProvider = { context.getSystemService("fingerprint") },
        )
    }

    internal fun resolveStrongBiometricAvailability(
        sdkInt: Int,
        biometricManagerStatus: Int,
        hasScreenLock: () -> Boolean,
        fingerprintServiceProvider: () -> Any?,
    ): Boolean {
        if (biometricManagerStatus == BiometricManager.BIOMETRIC_SUCCESS) {
            return true
        }
        // Work around b/543076354: androidx.core 1.18+ turned FingerprintManagerCompat into a
        // no-op, causing androidx.biometric 1.1.0's canAuthenticate(BIOMETRIC_STRONG) to return
        // BIOMETRIC_STATUS_UNKNOWN / BIOMETRIC_ERROR_HW_NOT_PRESENT on Android 10 (API 29) and
        // lower even when a Class 3 fingerprint is enrolled.
        if (sdkInt < Build.VERSION_CODES.R) {
            return hasEnrolledPlatformFingerprint(hasScreenLock, fingerprintServiceProvider)
        }
        return false
    }

    internal fun hasEnrolledPlatformFingerprint(
        hasScreenLock: () -> Boolean,
        fingerprintServiceProvider: () -> Any?,
    ): Boolean {
        if (!hasScreenLock()) {
            return false
        }
        return runCatching {
            val service = fingerprintServiceProvider() ?: return false
            val clazz = runCatching {
                Class.forName("android.hardware.fingerprint.FingerprintManager")
            }.getOrElse { service.javaClass }
            val isHardwareDetected = clazz.getMethod("isHardwareDetected").invoke(service) as? Boolean ?: false
            val hasEnrolled = clazz.getMethod("hasEnrolledFingerprints").invoke(service) as? Boolean ?: false
            isHardwareDetected && hasEnrolled
        }.getOrDefault(false)
    }

    fun authenticateIfScreenLockEnabled(
        activity: AppCompatActivity,
        onAuthenticated: () -> Unit,
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
                            Toast.LENGTH_SHORT,
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
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.authentication_dialog_title))
            .setSubtitle(activity.getString(R.string.authentication_dialog_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    fun authenticateForSensitiveAction(
        activity: AppCompatActivity,
        settings: Settings,
        tokenCryptoManager: TokenCryptoManager,
        onAuthenticated: () -> Unit,
    ) {
        if (settings.authMode != AuthMode.NONE && activity is MainActivity && !activity.isSessionAuthenticated()) {
            return
        }
        if (authCallbackOverride != null ||
            settings.authMode != AuthMode.TOKEN_ENCRYPTED ||
            hasScreenLock(activity)
        ) {
            authenticateIfScreenLockEnabled(activity, onAuthenticated)
            return
        }

        // In TOKEN_ENCRYPTED mode without a device screen lock, verify the user's encryption key
        if (activity is MainActivity) {
            activity.requestManualKeyVerification(onAuthenticated)
        }
    }

    fun extractCharArray(editText: EditText): CharArray {
        val editable = editText.text ?: return CharArray(0)
        val length = editable.length
        if (length == 0) return CharArray(0)
        return CharArray(length) { index -> editable[index] }
    }

    fun clearAndCancelAutofill(context: Context, vararg inputs: EditText) {
        for (input in inputs) {
            scrubEditable(input.text)
        }
        cancelAutofillSession(context)
    }

    private fun scrubEditable(editable: Editable?) {
        if (editable == null) return
        for (i in 0 until editable.length) {
            editable.replace(i, i + 1, "\u0000")
        }
        editable.clear()
    }

    private fun cancelAutofillSession(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(AutofillManager::class.java)?.cancel()
        }
    }

    fun authenticateAppLaunch(
        activity: AppCompatActivity,
        onSuccess: () -> Unit,
        onNoDeviceCredential: () -> Unit = {},
        onCancelOrError: (errorCode: Int, errString: CharSequence?) -> Unit = { _, _ -> },
    ) {
        appLaunchAuthCallbackOverride?.let { override ->
            override(
                {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSuccess()
                    }
                },
                {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onCancelOrError(BiometricPrompt.ERROR_USER_CANCELED, null)
                    }
                },
            )
            return
        }

        if (!hasScreenLock(activity)) {
            if (!activity.isFinishing && !activity.isDestroyed) {
                onNoDeviceCredential()
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
                    if (activity.isFinishing || activity.isDestroyed) {
                        return
                    }
                    if (errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || !hasScreenLock(activity)) {
                        onNoDeviceCredential()
                    } else {
                        onCancelOrError(errorCode, errString)
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSuccess()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        applicationContext,
                        R.string.unable_to_authenticate,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.authentication_dialog_title))
            .setSubtitle(activity.getString(R.string.authentication_dialog_subtitle))
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    fun authenticateBiometricCrypto(
        activity: AppCompatActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFallbackToManual: (userInitiated: Boolean) -> Unit,
        onCancel: () -> Unit,
    ) {
        biometricCryptoCallbackOverride?.let { override ->
            override(
                cipher,
                { authenticatedCipher ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSuccess(authenticatedCipher)
                    }
                },
                { userInitiated ->
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onFallbackToManual(userInitiated)
                    }
                },
                {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onCancel()
                    }
                },
            )
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
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onFallbackToManual(true)
                            }
                        }
                        BiometricPrompt.ERROR_LOCKOUT,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        BiometricPrompt.ERROR_HW_UNAVAILABLE,
                        BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        -> {
                            Toast.makeText(
                                applicationContext,
                                "${activity.getString(R.string.authentication_error)} $errString",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onFallbackToManual(false)
                            }
                        }
                        else -> {
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.ERROR_CANCELED
                            ) {
                                Toast.makeText(
                                    applicationContext,
                                    "${activity.getString(R.string.authentication_error)} $errString",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                onCancel()
                            }
                        }
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSuccess(result.cryptoObject?.cipher ?: cipher)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(
                        applicationContext,
                        R.string.unable_to_authenticate,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
