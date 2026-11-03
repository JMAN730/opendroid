package com.opendroid.ai.ui.viewmodel

import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.ProviderCredentialPersistenceState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** This map with [key] set back to what [stored] holds, or removed if [stored] has no entry. */
internal fun <K, V> Map<K, V>.withEntryFrom(stored: Map<K, V>, key: K): Map<K, V> =
    if (key in stored) this + (key to stored.getValue(key)) else this - key

/**
 * Persists a config edit the UI has already shown. A failed save produces no DataStore emission,
 * so nothing else would correct the UI, and Settings would show a value the agent is not running
 * with: a YOLO that reads as OFF.
 */
internal class OptimisticConfigSaver(
    private val uiConfig: MutableStateFlow<LLMConfig>,
    private val persist: suspend ((LLMConfig) -> LLMConfig) -> ProviderCredentialPersistenceState,
    private val readStored: suspend () -> LLMConfig
) {
    // persist hops to the IO dispatcher before DataStore queues its edit, so two saves started
    // back to back could land in either order. Mutex waiters are served first come, first served.
    private val writeOrder = Mutex()

    /**
     * Saves [update]; on failure, [revert] puts only the fields of this edit back to the stored
     * values, because other fields may hold debounced edits still waiting to save.
     *
     * @return whether the edit was saved
     */
    suspend fun save(
        revert: LLMConfig.(stored: LLMConfig) -> LLMConfig,
        update: (LLMConfig) -> LLMConfig
    ): Boolean {
        val shown = uiConfig.value
        if (writeOrder.withLock { persist(update) } == ProviderCredentialPersistenceState.Ready) return true
        val stored = try {
            readStored()
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        val current = uiConfig.value
        // A newer edit of these fields owns them now; reverting would undo it.
        if (current.revert(shown) != current) return false
        uiConfig.value = current.revert(stored)
        return false
    }
}
