package com.opendroid.ai.core.llm

import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Drops the cached on-device engine when nothing is likely to use it soon, since it keeps the
 * whole model (0.5 to 3.6 GB) mapped until the process dies: when the active provider moves off
 * on-device inference, and when the system reports memory pressure. The next on-device request
 * loads the engine again.
 */
class OnDeviceEngineReleaseMonitor(
    private val scope: CoroutineScope,
    private val releaseEngine: suspend () -> Unit
) {
    /** Releases the engine each time [activeProviders] moves from on-device to another provider. */
    fun watch(activeProviders: Flow<String>): Job = scope.launch {
        activeProviders
            .map(ProviderCatalog::isOnDevice)
            .distinctUntilChanged()
            .catch { error -> Log.w(TAG, "Stopped watching the active provider", error) }
            .collect { onDevice -> if (!onDevice) releaseEngine() }
    }

    /**
     * Releases the engine for an `onTrimMemory` [level] that signals memory pressure. A hidden UI
     * alone is not pressure: the agent keeps running behind the overlay and would reload the model.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            // Called on the main thread; the release waits for a running session to end.
            scope.launch { releaseEngine() }
        }
    }

    private companion object {
        const val TAG = "OnDeviceEngineRelease"
    }
}
