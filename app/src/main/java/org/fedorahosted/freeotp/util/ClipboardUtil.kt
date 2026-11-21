package org.fedorahosted.freeotp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardUtil @Inject constructor(
    @ApplicationContext context: Context
) {
    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "FreeOtpClipboardTimer").apply { isDaemon = true }
    }
    private val nextClipId = AtomicLong(1L)

    @VisibleForTesting
    var expirationDurationMs: Long = DEFAULT_CLIP_EXPIRATION_MS

    private var pendingExpireFuture: ScheduledFuture<*>? = null
    private var activeClipId: Long = 0L
    private var activeCopiedAtElapsedMs: Long = 0L
    // Only retained on API 23 where ClipDescription.extras (API 24+) does not exist
    private var activeApi23Plaintext: String? = null
    private var pendingClearClipIdOnForeground: Long = 0L

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        mainHandler.post {
            val expectedId = activeClipId
            if (expectedId == 0L) return@post

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val description = runCatching { clipboardManager.primaryClipDescription }.getOrNull()
                if (description != null) {
                    val currentId = description.extras?.getLong(EXTRA_CLIP_ID, -1L) ?: -1L
                    if (currentId != expectedId) {
                        disarmTracking()
                    }
                } else if (SystemClock.elapsedRealtime() - activeCopiedAtElapsedMs >= OWN_CLIP_CALLBACK_GRACE_MS) {
                    // Another app modified the clipboard while FreeOTP+ was in the background
                    disarmTracking()
                }
            } else {
                val currentText = runCatching {
                    clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                }.getOrNull()
                if (currentText != activeApi23Plaintext) {
                    disarmTracking()
                }
            }
        }
    }

    init {
        mainHandler.post {
            runCatching { clipboardManager.addPrimaryClipChangedListener(clipChangedListener) }
        }
    }

    @MainThread
    fun copySensitiveText(
        label: CharSequence?,
        text: CharSequence,
        ttlMs: Long = expirationDurationMs
    ) {
        pendingExpireFuture?.cancel(false)
        pendingExpireFuture = null
        pendingClearClipIdOnForeground = 0L

        val clipId = nextClipId.getAndIncrement()
        val clip = ClipData.newPlainText(label, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(EXTRA_IS_SENSITIVE_KEY, true)
                putLong(EXTRA_CLIP_ID, clipId)
            }
            activeApi23Plaintext = null
        } else {
            activeApi23Plaintext = text.toString()
        }

        activeClipId = clipId
        activeCopiedAtElapsedMs = SystemClock.elapsedRealtime()
        clipboardManager.setPrimaryClip(clip)

        if (ttlMs > 0L) {
            pendingExpireFuture = timerExecutor.schedule(
                { mainHandler.post { clearSensitiveClipIfOwned(expectedTimerClipId = clipId) } },
                ttlMs,
                TimeUnit.MILLISECONDS
            )
        }
    }

    @MainThread
    fun clearSensitiveClipIfOwned(expectedTimerClipId: Long? = null) {
        if (expectedTimerClipId != null && activeClipId != expectedTimerClipId) {
            return
        }
        val targetId = if (activeClipId != 0L) activeClipId else pendingClearClipIdOnForeground
        if (targetId == 0L && activeApi23Plaintext == null) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val description = runCatching { clipboardManager.primaryClipDescription }.getOrNull()
            if (description != null) {
                val currentId = description.extras?.getLong(EXTRA_CLIP_ID, -1L) ?: -1L
                disarmTracking()
                pendingClearClipIdOnForeground = 0L
                if (currentId == targetId) {
                    performClearClipboard()
                }
            } else {
                // Backgrounded on Android 10+ (or already empty): try clearing now and verify on next foreground
                pendingClearClipIdOnForeground = targetId
                disarmTracking()
                performClearClipboard()
            }
        } else {
            val expectedText = activeApi23Plaintext
            val currentText = runCatching {
                clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            }.getOrNull()
            disarmTracking()
            if (expectedText != null && currentText == expectedText) {
                performClearClipboard()
            }
        }
    }

    @MainThread
    fun onAppForegrounded() {
        if (pendingClearClipIdOnForeground != 0L) {
            clearSensitiveClipIfOwned()
        }
    }

    private fun performClearClipboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { clipboardManager.clearPrimaryClip() }
        } else {
            runCatching { clipboardManager.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }

    private fun disarmTracking() {
        pendingExpireFuture?.cancel(false)
        pendingExpireFuture = null
        activeClipId = 0L
        activeCopiedAtElapsedMs = 0L
        activeApi23Plaintext = null
    }

    companion object {
        const val DEFAULT_CLIP_EXPIRATION_MS: Long = 60_000L
        const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
        private const val EXTRA_CLIP_ID = "org.fedorahosted.freeotp.CLIP_ID"
        private const val OWN_CLIP_CALLBACK_GRACE_MS = 500L

        /**
         * Android 13+ (API 33 / TIRAMISU) displays a system visual clipboard confirmation overlay,
         * so custom in-app "Copied to clipboard" Snackbars/Toasts should only be shown on API <= 32.
         */
        fun shouldShowAppCopyFeedback(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
            return sdkInt <= Build.VERSION_CODES.S_V2
        }
    }
}
