package org.fedorahosted.freeotp.util

import android.app.Dialog
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.FreeOtpPlusApplication
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
        return keyguardManager?.isDeviceSecure == true
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
        val dialog = createManualKeyPromptDialog(
            activity = activity,
            titleRes = R.string.verify_encryption_key_title,
            messageRes = R.string.verify_encryption_key_message,
            positiveButtonRes = R.string.ok_text
        ) { chars, _, dismissSafely ->
            if (tokenCryptoManager.unlockWithPassphrase(chars)) {
                dismissSafely()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    onAuthenticated()
                }
                null
            } else {
                activity.getString(R.string.incorrect_encryption_key)
            }
        }
        if (activity is MainActivity) {
            activity.showTrackedDialog(dialog)
        } else {
            dialog.show()
        }
    }

    fun applySecureFlag(context: Context, dialog: Dialog) {
        (context.applicationContext as? FreeOtpPlusApplication)?.applySecureFlagIfNeeded(dialog.window)
    }

    fun createManualKeyPromptDialog(
        activity: AppCompatActivity,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveButtonRes: Int,
        cancelable: Boolean = true,
        @StringRes biometricCheckboxLabelRes: Int? = null,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        onSubmit: suspend (
            passphrase: CharArray,
            biometricChecked: Boolean,
            dismissSafely: () -> Unit
        ) -> String?
    ): AlertDialog {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_manual_key_prompt, null)
        val messageView = dialogView.findViewById<TextView>(R.id.dialog_message)
        val keyInputLayout = dialogView.findViewById<TextInputLayout>(R.id.key_input_layout)
        val keyEditText = dialogView.findViewById<TextInputEditText>(R.id.key_edit_text)
        val enableBiometricCheckbox = dialogView.findViewById<MaterialCheckBox>(R.id.enable_biometric_checkbox)

        messageView.setText(messageRes)
        keyEditText.doAfterTextChanged { keyInputLayout.error = null }

        if (biometricCheckboxLabelRes != null) {
            enableBiometricCheckbox.visibility = View.VISIBLE
            enableBiometricCheckbox.isChecked = true
            enableBiometricCheckbox.setText(biometricCheckboxLabelRes)
        } else {
            enableBiometricCheckbox.visibility = View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setView(dialogView)
            .setCancelable(cancelable)
            .setPositiveButton(positiveButtonRes, null)
            .setNegativeButton(R.string.cancel_text) { _, _ ->
                onCancel?.invoke()
            }
            .setOnCancelListener {
                onCancel?.invoke()
            }
            .create()

        dialog.setOnShowListener {
            applySecureFlag(activity, dialog)
            disableAutofillForDialog(dialog, dialogView)
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val chars = extractCharArray(keyEditText)
                var handedOffToCoroutine = false
                try {
                    if (chars.isEmpty()) {
                        keyInputLayout.error = activity.getString(R.string.incorrect_encryption_key)
                        return@setOnClickListener
                    }
                    positiveButton.isEnabled = false
                    val biometricChecked = biometricCheckboxLabelRes != null && enableBiometricCheckbox.isChecked
                    handedOffToCoroutine = true
                    activity.lifecycleScope.launch {
                        val errorMessage = try {
                            onSubmit(chars, biometricChecked) {
                                dismissKeyDialogSafely(dialog, keyEditText)
                            }
                        } finally {
                            chars.fill('\u0000')
                        }
                        if (errorMessage != null) {
                            positiveButton.isEnabled = true
                            keyInputLayout.error = errorMessage
                        }
                    }
                } finally {
                    if (!handedOffToCoroutine) {
                        chars.fill('\u0000')
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            clearAndCancelAutofill(dialog.context, keyEditText)
            onDismiss?.invoke()
        }
        applySecureFlag(activity, dialog)
        disableAutofillForDialog(dialog, dialogView)
        return dialog
    }

    fun disableAutofillForDialog(dialog: AlertDialog, dialogView: View) {
        ViewCompat.setImportantForAutofill(
            dialogView,
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        )
        dialog.window?.decorView?.let { decorView ->
            ViewCompat.setImportantForAutofill(
                decorView,
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            )
        }
        cancelAutofillSession(dialog.context)
    }

    fun dismissKeyDialogSafely(dialog: AlertDialog, vararg inputs: EditText) {
        clearAndCancelAutofill(dialog.context, *inputs)
        dialog.dismiss()
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
        onCancelOrError: (errorCode: Int, errString: CharSequence?) -> Unit = { _, _ -> }
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
                }
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
