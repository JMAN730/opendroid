package com.opendroid.ai.core.voice

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class TextToSpeechEngine(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val client: OkHttpClient = OkHttpClient(),
    private val elevenLabsBaseUrl: String = "https://api.elevenlabs.io",
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Bumped by stop(). A speak() started under an older generation must not play
    // anything, including the local fallback, once its fetch or playback unwinds.
    private val generation = AtomicLong()
    @Volatile private var inFlightCall: Call? = null

    // The player and the cached clip it plays are replaced and released together.
    private val playerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var currentClip: File? = null

    // deleteOnExit never runs on Android, so clips from earlier processes are still here.
    // speak() waits for this so it cannot delete a clip that is about to play.
    private val staleClipCleanup = scope.launch { deleteCachedClips() }

    /** Called on the main thread with the id speak() returned, when that utterance ends. */
    var onCompletionListener: ((utteranceId: String) -> Unit)? = null

    init {
        tts = TextToSpeech(context, this)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke(utteranceId.orEmpty())
                }
            }
            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    onCompletionListener?.invoke(utteranceId.orEmpty())
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

    /**
     * Speaks [text] and returns [utteranceId], which [onCompletionListener] receives when
     * this utterance, and not some other one, finishes.
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): String {
        val speechGeneration = generation.get()
        scope.launch {
            staleClipCleanup.join()
            val config = settingsRepository.llmConfig.first()
            val apiKey = config.elevenLabsApiKey
            val voiceId = config.elevenLabsVoiceId.ifEmpty { "21m00Tcm4TlvDq8ikWAM" } // default Rachel voice

            if (apiKey.isNotEmpty()) {
                try {
                    val success = playElevenLabsTts(text, apiKey, voiceId, utteranceId, speechGeneration)
                    if (success) return@launch
                } catch (e: Exception) {
                    // Fallback to local TTS if ElevenLabs fails
                }
            }

            // Local fallback — must run on main thread. Not after stop(): a cancelled
            // ElevenLabs fetch fails, and must not turn into local speech.
            if (isInitialized && generation.get() == speechGeneration) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            }
        }
        return utteranceId
    }

    private fun playElevenLabsTts(
        text: String,
        apiKey: String,
        voiceId: String,
        utteranceId: String,
        speechGeneration: Long
    ): Boolean {
        val url = "$elevenLabsBaseUrl/v1/text-to-speech/$voiceId"
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

        if (generation.get() != speechGeneration) return true
        val call = client.newCall(request)
        inFlightCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return false

                val clip = File.createTempFile(CLIP_PREFIX, CLIP_SUFFIX, context.cacheDir)
                try {
                    FileOutputStream(clip).use { fos ->
                        response.body.byteStream().copyTo(fos)
                    }
                } catch (e: Exception) {
                    clip.delete()
                    throw e
                }

                synchronized(playerLock) {
                    if (generation.get() != speechGeneration) {
                        clip.delete()
                        return true
                    }
                    // Replacing a clip mid-play releases it, which fires no completion.
                    releasePlayerLocked()
                    val player = MediaPlayer()
                    mediaPlayer = player
                    currentClip = clip
                    try {
                        player.setDataSource(clip.absolutePath)
                        player.prepare()
                        player.setOnCompletionListener { finishClip(player, utteranceId) }
                        player.setOnErrorListener { _, _, _ ->
                            finishClip(player, utteranceId)
                            true
                        }
                        player.start()
                    } catch (e: Exception) {
                        releasePlayerLocked()
                        throw e
                    }
                }
                return true
            }
        } finally {
            if (inFlightCall === call) inFlightCall = null
        }
    }

    private fun finishClip(player: MediaPlayer, utteranceId: String) {
        synchronized(playerLock) {
            if (mediaPlayer === player) releasePlayerLocked()
        }
        mainHandler.post {
            onCompletionListener?.invoke(utteranceId)
        }
    }

    private fun releasePlayerLocked() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentClip?.delete()
        currentClip = null
    }

    private fun deleteCachedClips() {
        context.cacheDir.listFiles { file ->
            file.name.startsWith(CLIP_PREFIX) && file.name.endsWith(CLIP_SUFFIX)
        }?.forEach { it.delete() }
    }

    /** Silences speech in progress and drops any ElevenLabs clip still being fetched. */
    fun stop() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        scope.coroutineContext.cancelChildren()
        tts?.stop()
        synchronized(playerLock) { releasePlayerLocked() }
    }

    fun destroy() {
        generation.incrementAndGet()
        inFlightCall?.cancel()
        scope.cancel()
        tts?.shutdown()
        tts = null
        synchronized(playerLock) { releasePlayerLocked() }
    }

    private companion object {
        const val CLIP_PREFIX = "elevenlabs_"
        const val CLIP_SUFFIX = ".mp3"
    }
}
