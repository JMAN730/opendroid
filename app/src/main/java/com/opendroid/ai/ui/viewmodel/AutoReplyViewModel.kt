package com.opendroid.ai.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TEXT_EDIT_DEBOUNCE_MILLIS = 1000L

@HiltViewModel
class AutoReplyViewModel internal constructor(
    private val settingsRepository: SettingsRepository,
    // Deliberately not viewModelScope: the write started by onCleared() has to outlive the
    // ViewModel, or the last edit is lost exactly when the user leaves the screen.
    private val writeScope: CoroutineScope,
    private val debounceMillis: Long
) : ViewModel() {

    @Inject
    constructor(settingsRepository: SettingsRepository) : this(
        settingsRepository,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        TEXT_EDIT_DEBOUNCE_MILLIS
    )

    // Snapshot state rather than a StateFlow: the text fields read this back on every keystroke,
    // and an asynchronous round trip drops characters and moves the cursor.
    var config by mutableStateOf(AutoReplyConfig())
        private set
    var isLoading by mutableStateOf(true)
        private set

    private val pendingConfig = AtomicReference<AutoReplyConfig?>(null)
    private val writeMutex = Mutex()
    private var debounceJob: Job? = null

    init {
        writeScope.launch {
            config = settingsRepository.autoReplyConfig.first()
            isLoading = false
        }
    }

    fun saveConfig(newConfig: AutoReplyConfig, debounce: Boolean = false) {
        config = newConfig
        pendingConfig.set(newConfig)
        debounceJob?.cancel()
        if (debounce) {
            debounceJob = writeScope.launch {
                delay(debounceMillis)
                writePendingConfig()
            }
        } else {
            writePendingConfig()
        }
    }

    /** Writes a debounced edit now instead of waiting out the delay. */
    fun flushPendingConfig() {
        debounceJob?.cancel()
        writePendingConfig()
    }

    override fun onCleared() {
        flushPendingConfig()
    }

    // A separate job, so cancelling the debounce can never interrupt a write already under way.
    private fun writePendingConfig() {
        writeScope.launch {
            // Each write takes the newest config, and the lock keeps an older write from landing
            // after it.
            writeMutex.withLock {
                val toWrite = pendingConfig.getAndSet(null) ?: return@withLock
                try {
                    settingsRepository.updateAutoReplyConfig(toWrite)
                } catch (e: Exception) {
                    android.util.Log.e("AutoReplySettings", "Failed to save config: ${e.message}", e)
                }
            }
        }
    }
}
