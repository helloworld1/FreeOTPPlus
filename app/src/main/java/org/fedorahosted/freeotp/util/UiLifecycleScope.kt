package org.fedorahosted.freeotp.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.*


fun LifecycleOwner.uiLifecycleScope(block: suspend CoroutineScope.() -> Unit) {
    lifecycle.addObserver(UiLifecycleScope(block))
}

private class UiLifecycleScope(val block: suspend CoroutineScope.() -> Unit) : LifecycleObserver {

    private var scope: CoroutineScope? = null

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate() {
        scope = CoroutineScope(Job() + Dispatchers.Main)
        scope?.launch {
            block()
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        scope?.coroutineContext?.cancel()
    }
}
