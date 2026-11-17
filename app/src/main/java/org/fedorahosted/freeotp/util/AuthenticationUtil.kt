package org.fedorahosted.freeotp.util

import android.app.KeyguardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.data.AuthMode
import org.fedorahosted.freeotp.data.TokenCryptoManager
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

    @VisibleForTesting
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
        if (keyguardManager?.isDeviceSecure != true) {
            return false
        }
        return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun canAuthenticateStrongBiometric(context: Context): Boolean {
        hasStrongBiometricOverride?.let { return it }
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
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

    fun authenticateForSensitiveAction(
        activity: AppCompatActivity,
        settings: Settings,
        tokenCryptoManager: TokenCryptoManager,
        onAuthenticated: () -> Unit
    ) {
        if (authCallbackOverride != null ||
            settings.authMode != AuthMode.TOKEN_ENCRYPTED ||
            hasScreenLock(activity)
        ) {
            authenticateIfScreenLockEnabled(activity, onAuthenticated)
            return
        }

        // In TOKEN_ENCRYPTED mode without a device screen lock, verify the user's encryption key
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_manual_key_prompt, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)

        messageView.setText(R.string.verify_encryption_key_message)
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.verify_encryption_key_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok_text, null)
            .setNegativeButton(R.string.cancel_text, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val chars = keyEditText.text?.toString()?.toCharArray() ?: CharArray(0)
                if (chars.isEmpty()) {
                    keyInputLayout.error = activity.getString(R.string.incorrect_encryption_key)
                    return@setOnClickListener
                }
                positiveButton.isEnabled = false
                activity.lifecycleScope.launch {
                    val keyBytes = tokenCryptoManager.verifyPassphrase(chars)
                    chars.fill('\u0000')
                    if (keyBytes != null) {
                        keyBytes.fill(0)
                        dialog.dismiss()
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onAuthenticated()
                        }
                    } else {
                        positiveButton.isEnabled = true
                        keyInputLayout.error = activity.getString(R.string.incorrect_encryption_key)
                    }
                }
            }
        }
        dialog.show()
    }

    fun authenticateAppLaunch(
        activity: AppCompatActivity,
        finishOnCancel: Boolean,
        onSuccess: () -> Unit
    ) {
        appLaunchAuthCallbackOverride?.let { override ->
            override(
                {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onSuccess()
                    }
                },
                {
                    if (finishOnCancel && !activity.isFinishing && !activity.isDestroyed) {
                        activity.finish()
                    }
                }
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
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        Toast.makeText(
                            applicationContext,
                            "${activity.getString(R.string.authentication_error)} $errString",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    if (finishOnCancel && errorCode != BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        activity.finish()
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

    fun authenticateBiometricCrypto(
        activity: AppCompatActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFallbackToManual: (userInitiated: Boolean) -> Unit,
        onCancel: () -> Unit
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
                }
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
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> {
                            Toast.makeText(
                                applicationContext,
                                "${activity.getString(R.string.authentication_error)} $errString",
                                Toast.LENGTH_SHORT
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
                                    Toast.LENGTH_SHORT
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
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
