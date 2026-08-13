package com.opendroid.ai.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.AgentState
import com.opendroid.ai.core.voice.SpeechRecognitionEngine
import com.opendroid.ai.core.voice.TextToSpeechEngine
import com.opendroid.ai.core.voice.VoiceApprovalIntent
import com.opendroid.ai.core.voice.VoiceApprovalParser
import com.opendroid.ai.core.voice.WakeWordDetector
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class OpenDroidService : Service() {

    @Inject
    lateinit var agentLoop: AgentLoop

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var mcpServer: McpServer

    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var speechRecognitionEngine: SpeechRecognitionEngine
    private lateinit var textToSpeechEngine: TextToSpeechEngine

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var showFloatingButton = false
    private var enginesInitialized = false
    @Volatile private var pendingApprovalListen = false

    /**
     * True only when [startForegroundCompat] actually promoted with the `microphone` type.
     * A `specialUse` fallback (e.g. after a boot start) doesn't carry microphone foreground
     * eligibility, so [WakeWordDetector] would just fail onError -> restart every second
     * forever; wake word is gated on this instead of being started unconditionally.
     *
     * Not final: [promoteToMicrophoneIfPossible] retries the promotion on every subsequent
     * start, so opening the app or granting the permission recovers wake word detection.
     */
    private var micForegroundEligible = false

    companion object {
        const val ACTION_TRIGGER_RECORD = "com.opendroid.ai.action.TRIGGER_RECORD"
        private const val TAG = "OpenDroidService"
        private const val CHANNEL_ID = "opendroid_channel"
        private const val NOTIFICATION_ID = 2024
        
        /**
         * Never lets a refused foreground start take the process down: Android throws
         * ForegroundServiceStartNotAllowedException (an IllegalStateException, not a
         * SecurityException) when the caller is not in a state that may start an FGS.
         */
        fun start(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Foreground start refused", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenDroidService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Post the notification before anything else: the platform kills the process with
        // ForegroundServiceDidNotStartInTimeException if startForeground is late, and engine
        // construction below (TTS binds to a remote engine) is slow enough to miss the window.
        createNotificationChannel()
        if (!startForegroundCompat()) {
            stopSelf()
            return
        }

        // Initialize engines. enginesInitialized stays false until every step below
        // succeeds, so a construction or mcpServer.start() failure can't leave onDestroy's
        // cleanup skipped or onStartCommand STICKY-restarting into the same crash (issue 185).
        try {
            wakeWordDetector = WakeWordDetector(this)
            speechRecognitionEngine = SpeechRecognitionEngine(this)
            textToSpeechEngine = TextToSpeechEngine(this, settingsRepository)

            // Bind Agent Loop TTS
            agentLoop.onSpeakCallback = { text ->
                textToSpeechEngine.speak(text)
            }

            // Set TTS completion listener to transition back to Idle
            textToSpeechEngine.onCompletionListener = {
                // Only Speaking resets to Idle - speech that happens to play while a
                // plan sits in PlanProposed must not knock the approval gate over.
                if (agentLoop.agentState.value is AgentState.Speaking) {
                    agentLoop.setAgentState(AgentState.Idle)
                }
                if (pendingApprovalListen) {
                    pendingApprovalListen = false
                    startListeningForApproval()
                }
            }

            mcpServer.start()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Engine startup failed", e)
            destroyInitializedEngines()
            stopSelf()
            return
        }
        enginesInitialized = true

        // Monitor floating button config to start/stop wake word detection dynamically
        serviceScope.launch {
            settingsRepository.llmConfig.collectLatest { config ->
                showFloatingButton = config.showFloatingButton
                if (showFloatingButton) {
                    wakeWordDetector.stopListening()
                } else {
                    startWakeWordDetection()
                }
            }
        }

        // Hands-free plan approval (upstream issue 18 spec, voice section):
        // speak the prompt once per proposed plan; auto-listen for the reply
        // only in wake-word mode, where the user is known to be voice-driven.
        serviceScope.launch {
            var promptedPlanId: String? = null
            agentLoop.agentState.collectLatest { state ->
                if (state is AgentState.PlanProposed && state.plan.planId != promptedPlanId) {
                    promptedPlanId = state.plan.planId
                    if (!showFloatingButton) {
                        pendingApprovalListen = true
                    }
                    textToSpeechEngine.speak(
                        "I've planned: ${state.plan.goal}, ${state.plan.estimatedSteps} steps. " +
                        "Say approve to run, or cancel."
                    )
                }
            }
        }
    }

    /**
     * Returns false when the platform refuses every foreground type available to us - the
     * caller must then stop the service rather than run headless, since a service that never
     * reaches the foreground is killed with ForegroundServiceDidNotStartInTimeException.
     */
    private fun startForegroundCompat(): Boolean {
        val sdkInt = android.os.Build.VERSION.SDK_INT
        val micGranted = isMicPermissionGranted()

        val preferred = ForegroundServiceStartPolicy.preferredType(sdkInt, micGranted)
        if (startForegroundWithType(preferred)) {
            micForegroundEligible = ForegroundServiceStartPolicy.carriesMicrophoneEligibility(preferred)
            return true
        }

        // Both a missing RECORD_AUDIO grant and a disallowed start context reject the
        // microphone type; specialUse still works in the former case. A specialUse start
        // never carries microphone eligibility, so micForegroundEligible stays false and
        // wake word detection is suppressed until a later start from a context allowed to
        // promote to microphone - see promoteToMicrophoneIfPossible.
        val fallback = ForegroundServiceStartPolicy.fallbackType(sdkInt, micGranted)
        if (fallback != ForegroundServiceType.NONE && startForegroundWithType(fallback)) {
            micForegroundEligible = ForegroundServiceStartPolicy.carriesMicrophoneEligibility(fallback)
            return true
        }
        return false
    }

    /**
     * Second chance at the `microphone` foreground type for a service that had to settle for
     * `specialUse`, which happens whenever the first start came from BOOT_COMPLETED (Android 14
     * bans a microphone FGS started from boot). Android delivers later `startForegroundService`
     * calls - from the launcher activity or right after the RECORD_AUDIO grant - to
     * [onStartCommand] without rerunning [onCreate], so without this the boot fallback would
     * leave wake word detection off until the service is destroyed and recreated.
     *
     * Calling `startForeground` again on a running service adds the type when the app is
     * currently eligible for it, and throws otherwise - which [startForegroundWithType] absorbs,
     * leaving the existing `specialUse` foreground state untouched so we simply try again on
     * the next start.
     */
    private fun promoteToMicrophoneIfPossible() {
        if (micForegroundEligible) return
        val sdkInt = android.os.Build.VERSION.SDK_INT
        if (!isMicPermissionGranted()) return

        val preferred = ForegroundServiceStartPolicy.preferredType(sdkInt, micGranted = true)
        if (!ForegroundServiceStartPolicy.carriesMicrophoneEligibility(preferred)) return
        if (!startForegroundWithType(preferred)) return

        micForegroundEligible = true
        // Wake word was suppressed while ineligible; start it now unless the user drives the
        // agent with the floating button instead.
        if (!showFloatingButton) startWakeWordDetection()
    }

    private fun isMicPermissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startForegroundWithType(type: ForegroundServiceType): Boolean = try {
        val notification = createNotification()
        when (type) {
            ForegroundServiceType.MANIFEST -> startForeground(NOTIFICATION_ID, notification)
            else -> androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification, platformTypeOf(type)
            )
        }
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException is an IllegalStateException, not a
        // SecurityException, so both have to be absorbed here.
        android.util.Log.w(TAG, "startForeground refused for type $type", e)
        false
    }

    /**
     * Resolves a [ForegroundServiceType] to the platform constant, behind a real SDK check so
     * a type is never requested on a platform that does not know it.
     */
    private fun platformTypeOf(type: ForegroundServiceType): Int = when (type) {
        ForegroundServiceType.MICROPHONE ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        ForegroundServiceType.SPECIAL_USE ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ForegroundServiceType.MANIFEST, ForegroundServiceType.NONE -> 0
    }

    private fun startWakeWordDetection() {
        if (!micForegroundEligible) return
        wakeWordDetector.startListening {
            // Wake word detected! Prompt user with a sound or greeting and start listing for query
            textToSpeechEngine.speak("OpenDroid online.")
            startListeningForQuery()
        }
    }

    private fun startListeningForQuery() {
        // Temporarily pause wake word to avoid hearing itself
        wakeWordDetector.stopListening()

        // Set agent state to Listening
        agentLoop.setAgentState(AgentState.Listening)

        // Start speech recognizer for query input
        speechRecognitionEngine.startListening(
            onResult = { query ->
                agentLoop.processQuery(query, this)
                // Resume wake word detection only if floating button is disabled
                if (!showFloatingButton) {
                    startWakeWordDetection()
                } else {
                    agentLoop.setAgentState(AgentState.Idle)
                }
            },
            onError = { _ ->
                agentLoop.setAgentState(AgentState.Idle)
                // Resume wake word detection only if floating button is disabled
                if (!showFloatingButton) {
                    startWakeWordDetection()
                }
            }
        )
    }

    /**
     * One-shot reply capture after the spoken approval prompt. Unrecognized
     * speech (or an error) is a deliberate no-op: the plan stays in
     * PlanProposed with the visual modal still on screen. Never a grant path -
     * nothing spoken may widen the allowlist.
     */
    private fun startListeningForApproval() {
        wakeWordDetector.stopListening()
        speechRecognitionEngine.startListening(
            onResult = { utterance ->
                when (VoiceApprovalParser.parse(utterance)) {
                    VoiceApprovalIntent.APPROVE -> agentLoop.approveProposedPlan(this)
                    VoiceApprovalIntent.REJECT -> agentLoop.rejectProposedPlan()
                    VoiceApprovalIntent.NONE -> Unit
                }
                if (!showFloatingButton) startWakeWordDetection()
            },
            onError = { _ ->
                if (!showFloatingButton) startWakeWordDetection()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onCreate stopSelf paths leave engines unset; START_STICKY would restart into
        // the same refused start and crash-loop (issue 185).
        if (!enginesInitialized) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Every start is a chance to escape a boot-time specialUse fallback: this one may be
        // coming from the foreground app or a fresh RECORD_AUDIO grant, both of which make the
        // microphone type reachable again.
        promoteToMicrophoneIfPossible()
        if (intent?.action == ACTION_TRIGGER_RECORD) {
            startListeningForQuery()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // onCreate bails out before or during engine construction when the foreground
        // start is refused or a later step throws; enginesInitialized is only true once
        // every engine below is guaranteed constructed.
        if (!enginesInitialized) return
        mcpServer.stop()
        wakeWordDetector.destroy()
        speechRecognitionEngine.destroy()
        textToSpeechEngine.destroy()
    }

    /**
     * Cleans up whichever engines got constructed before onCreate's startup try block
     * threw. Each lateinit field is guarded individually since a failure can land after
     * some engines were built and before others.
     */
    private fun destroyInitializedEngines() {
        if (::wakeWordDetector.isInitialized) wakeWordDetector.destroy()
        if (::speechRecognitionEngine.isInitialized) speechRecognitionEngine.destroy()
        if (::textToSpeechEngine.isInitialized) textToSpeechEngine.destroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenDroid Agent Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps OpenDroid background agent alive"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenDroid Active")
            .setContentText("Listening for wake word 'OpenDroid'")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
