package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class TextToSpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var onCompletionListener: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke()
                }
            }
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke()
                }
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isInitialized = true
            }
        }
    }

    fun speak(text: String) {
        scope.launch {
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "21m00Tcm4TlvDq8ikWAM" } // default Rachel voice

            if (apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId)
                    if (success) return@launch
                } catch (e: Exception) {
                    // Fallback to local TTS if ElevenLabs fails
                }
            }

            // Local fallback — must run on main thread
            if (isInitialized) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "opendroid_tts")
                }
            }
        }
    }

    private fun playElevenLabsTts(text: String, apiKey: String, voiceId: String): Boolean {
        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        val jsonPayload = """
            {
              "text": "$escapedText",
              "model_id": "eleven_monolingual_v1",
              "voice_settings": {
                "stability": 0.5,
                "similarity_boost": 0.75
              }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return false
            val body = response.body ?: return false
            
            val tempFile = File.createTempFile("elevenlabs_", ".mp3", context.cacheDir)
            tempFile.deleteOnExit()
            
            FileOutputStream(tempFile).use { fos ->
                body.byteStream().copyTo(fos)
            }

            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    mainHandler.post {
                        onCompletionListener?.invoke()
                    }
                }
                start()
            }
            return true
        }
    }

    fun stop() {
        tts?.stop()
        mediaPlayer?.stop()
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
