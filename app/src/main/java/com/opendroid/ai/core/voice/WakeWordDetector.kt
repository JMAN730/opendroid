package com.opendroid.ai.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class WakeWordDetector(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var intent: Intent? = null
    private var onWakeWordDetectedCallback: (() -> Unit)? = null
    private var isListening = false

    /**
     * Consecutive failures that are not part of normal listening (see [isTransientListeningError]).
     * They are what a recognizer that simply cannot run produces - a missing microphone
     * foreground-service eligibility, for instance - and left at a flat 1s retry they would spin
     * the recognizer once a second forever, so the restart delay backs off with this count.
     */
    private var consecutiveFailures = 0

    private val handler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable {
        startSpeechListening()
    }

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    fun startListening(onWakeWordDetected: () -> Unit) {
        if (isListening) return
        this.onWakeWordDetectedCallback = onWakeWordDetected
        isListening = true
        consecutiveFailures = 0
        startSpeechListening()
    }

    private fun startSpeechListening() {
        if (!isListening) return

        // Clean up previous instance before creating a new one
        cleanupRecognizer()

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            } catch (e: Exception) {
                consecutiveFailures++
                scheduleRestart()
                return
            }
        } else {
            consecutiveFailures++
            scheduleRestart()
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            
            override fun onError(error: Int) {
                // Restart listening loop on error or timeout after a delay
                if (isTransientListeningError(error)) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }
                scheduleRestart()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        if (match.contains("opendroid", ignoreCase = true) || match.contains("open droid", ignoreCase = true)) {
                            triggerWakeWord()
                            break
                        }
                    }
                }
                consecutiveFailures = 0
                scheduleRestart()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null) {
                    for (match in matches) {
                        if (match.contains("opendroid", ignoreCase = true) || match.contains("open droid", ignoreCase = true)) {
                            triggerWakeWord()
                            break
                        }
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            consecutiveFailures++
            scheduleRestart()
        }
    }

    private fun triggerWakeWord() {
        onWakeWordDetectedCallback?.invoke()
    }

    private fun scheduleRestart() {
        handler.removeCallbacks(restartRunnable)
        if (isListening) {
            handler.postDelayed(restartRunnable, restartDelayMillis(consecutiveFailures))
        }
    }

    private fun cleanupRecognizer() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.d(TAG, "stopListening during cleanup failed", e)
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.d(TAG, "destroy during cleanup failed", e)
        }
        speechRecognizer = null
    }

    fun stopListening() {
        isListening = false
        handler.removeCallbacks(restartRunnable)
        cleanupRecognizer()
        onWakeWordDetectedCallback = null
    }

    fun destroy() {
        stopListening()
    }

    companion object {
        private const val TAG = "WakeWordDetector"

        /** Delay used while the recognizer is healthy - long enough to stop audio flickering. */
        const val BASE_RESTART_DELAY_MS = 1000L

        /** Ceiling for the backoff, so a permanently unusable recognizer costs ~2 wakeups a minute. */
        const val MAX_RESTART_DELAY_MS = 30_000L

        /**
         * Errors that happen during healthy operation: the user simply said nothing this round.
         * These must not back the loop off, or the wake word would stop being responsive.
         */
        fun isTransientListeningError(error: Int): Boolean =
            error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

        /**
         * Exponential backoff on [consecutiveFailures], capped at [MAX_RESTART_DELAY_MS]:
         * 1s, 2s, 4s ... 30s. Zero failures keeps the normal [BASE_RESTART_DELAY_MS] cadence.
         */
        fun restartDelayMillis(consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return BASE_RESTART_DELAY_MS
            val exponent = consecutiveFailures.coerceAtMost(16)
            val delay = BASE_RESTART_DELAY_MS shl exponent
            return if (delay <= 0L) MAX_RESTART_DELAY_MS else delay.coerceAtMost(MAX_RESTART_DELAY_MS)
        }
    }
}
