package com.opendroid.ai.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechRecognitionEngine(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null

    // Identifies the currently active recognition session. Every startListening() call mints a
    // new token and each RecognitionListener callback closure captures the token it was created
    // with, comparing it against [activeSessionId] before delivering anything. This guards
    // against a stale onResults/onPartialResults/onError callback arriving from a session that
    // was already cancelled (isCancelled-style guard) *and* from a session that was superseded by
    // a newer startListening() call - either case bumps [activeSessionId] so the old callback's
    // captured token no longer matches and the delivery is dropped.
    private var activeSessionId = 0

    init {
        initializeRecognizer()
    }

    private fun initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Prolong listening limits to avoid early cut-offs
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            }
        }
    }

    fun startListening(
        onResult: (String) -> Unit,
        onPartialResult: (String) -> Unit = {},
        onError: (String) -> Unit
    ) {
        if (speechRecognizer == null) {
            onError("Speech recognition not available on this device")
            return
        }

        // Mint a new session token and let this specific listener closure capture it. Any
        // callback delivered to a listener whose captured token no longer matches
        // [activeSessionId] belongs to a cancelled or superseded session and is dropped.
        activeSessionId += 1
        val sessionId = activeSessionId

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (sessionId != activeSessionId) return
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition engine busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech input timeout"
                    else -> "Unknown error ($error)"
                }
                onError(message)
            }

            override fun onResults(results: Bundle?) {
                if (sessionId != activeSessionId) return
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onResult(matches[0])
                } else {
                    onError("No transcription results found")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (sessionId != activeSessionId) return
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    onPartialResult(matches[0])
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(recognizerIntent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Cancels the in-progress recognition session outright. Unlike [stopListening], the
     * recognizer is guaranteed not to deliver a subsequent onResults/onPartialResults callback
     * for this session - any such callback still in flight is dropped because the session token
     * it captured no longer matches [activeSessionId] once it has been bumped here.
     */
    fun cancel() {
        activeSessionId += 1
        speechRecognizer?.cancel()
    }

    fun destroy() {
        // Invalidate the active session so a callback that was already in flight cannot fire
        // (e.g. deliver a result) after this engine - and the Composable that owns it - has
        // been disposed.
        activeSessionId += 1
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
