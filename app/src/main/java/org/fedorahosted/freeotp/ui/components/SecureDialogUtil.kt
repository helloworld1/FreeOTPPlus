package org.fedorahosted.freeotp.ui.components

import android.os.Build
import android.view.View
import android.view.Window
import android.view.autofill.AutofillManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.view.ViewCompat
import org.fedorahosted.freeotp.FreeOtpPlusApplication
import java.util.Collections

/**
 * Tracks active Compose Dialog windows so MainActivity.getOpenDialogWindows() can inspect them
 * in FlagSecureTest and AuthenticationGateTest, and ensures FLAG_SECURE + autofill exclusion
 * are enforced on every dialog window.
 */
class DialogWindowRegistry {
    private val windows: MutableSet<Window> = Collections.synchronizedSet(LinkedHashSet())

    fun register(window: Window) {
        windows.add(window)
    }

    fun unregister(window: Window) {
        windows.remove(window)
    }

    fun snapshot(): List<Window> = synchronized(windows) {
        windows.filter { it.decorView.isAttachedToWindow }
    }
}

val LocalDialogWindowRegistry = staticCompositionLocalOf<DialogWindowRegistry?> { null }

@Composable
fun rememberSecureDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
): DialogProperties {
    val context = LocalContext.current
    val isScreenshotMode = (context.applicationContext as? FreeOtpPlusApplication)?.isScreenshotMode == true
    return remember(isScreenshotMode, dismissOnBackPress, dismissOnClickOutside, usePlatformDefaultWidth) {
        DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            securePolicy = if (isScreenshotMode) SecureFlagPolicy.Inherit else SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = usePlatformDefaultWidth,
        )
    }
}

/**
 * Call inside the body of every Compose AlertDialog / Dialog so its underlying Android Window
 * receives FLAG_SECURE, autofill exclusion, and registration in [LocalDialogWindowRegistry].
 */
@Composable
fun SecureDialogWindowEffect() {
    val context = LocalContext.current
    val view = LocalView.current
    val registry = LocalDialogWindowRegistry.current
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(dialogWindow, registry) {
        if (dialogWindow != null) {
            (context.applicationContext as? FreeOtpPlusApplication)?.applySecureFlagIfNeeded(dialogWindow)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ViewCompat.setImportantForAutofill(
                    dialogWindow.decorView,
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
                )
                context.getSystemService(AutofillManager::class.java)?.cancel()
            }
            registry?.register(dialogWindow)
        }
        onDispose {
            if (dialogWindow != null) {
                registry?.unregister(dialogWindow)
            }
        }
    }
}
