package com.opendroid.ai

import android.app.Application
import android.util.Log
import com.opendroid.ai.core.crash.CrashLogRecorder
import com.opendroid.ai.core.crash.DeviceMetadata
import com.opendroid.ai.core.crash.OpenDroidCrashHandler
import com.opendroid.ai.core.llm.OnDeviceEngineReleaseMonitor
import com.opendroid.ai.core.llm.providers.LiteRTLMProvider
import com.opendroid.ai.core.memory.LegacyPoisonedMemoryCleanup
import com.opendroid.ai.core.memory.graph.PersonalGrowthEngine
import com.opendroid.ai.core.security.LegacyPreferenceMigration
import com.opendroid.ai.data.crash.CrashLogRepository
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OpenDroidApp : Application() {

    @Inject
    lateinit var legacyPoisonedMemoryCleanup: LegacyPoisonedMemoryCleanup

    @Inject
    lateinit var personalGrowthEngine: PersonalGrowthEngine

    @Inject
    lateinit var crashLogRepository: CrashLogRepository

    @Inject
    lateinit var legacyPreferenceMigration: LegacyPreferenceMigration

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var liteRTProvider: dagger.Lazy<LiteRTLMProvider>

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // The cached LiteRT engine keeps its whole model mapped; drop it when it is not needed.
    private val engineReleaseMonitor by lazy {
        OnDeviceEngineReleaseMonitor(appScope) { liteRTProvider.get().releaseEngine() }
    }

    override fun onCreate() {
        super.onCreate()

        // Installed first so that a crash in any later startup step is captured.
        installCrashHandler()

        // Retire the legacy preference files into the direct-Keystore stores. This opens the
        // Keystore and the legacy keyset, so it runs off the main thread; splash routing and
        // onboarding await it before reading. It is a no-op once every value has been imported.
        legacyPreferenceMigration.start()

        // One-time startup cleanup: remove the invalid-action entries that
        // previous versions of the app stored in semantic memory
        appScope.launch {
            try {
                legacyPoisonedMemoryCleanup.cleanPoisonedMemories()
            } catch (e: Exception) {
                // Silently ignore cleanup errors to not block app startup
            }
            // Collapse the per-run routine memories older versions wrote. A no-op once they're gone.
            try {
                personalGrowthEngine.pruneDuplicateRoutineMemories()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to collapse duplicate routine memories", e)
            }
        }

        engineReleaseMonitor.watch(settingsRepository.llmConfig.map { it.activeProvider })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engineReleaseMonitor.onTrimMemory(level)
    }

    private fun installCrashHandler() {
        try {
            val recorder = CrashLogRecorder(
                sink = crashLogRepository,
                // Read here, at startup, rather than at crash time - a dying
                // process is the wrong place to be querying PackageManager.
                metadata = DeviceMetadata.fromContext(this),
                onRecordingFailed = { Log.e(TAG, "Failed to record crash", it) }
            )
            OpenDroidCrashHandler.install(recorder)
        } catch (t: Throwable) {
            // Crash logging is best-effort. Failing to install it must never be
            // the reason the app does not start.
            Log.e(TAG, "Failed to install crash handler", t)
        }
    }

    companion object {
        private const val TAG = "OpenDroidApp"
    }
}
