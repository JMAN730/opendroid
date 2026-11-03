package com.opendroid.ai.core.llm.providers

import android.util.Log
import com.opendroid.ai.core.llm.OnDeviceModelSpec
import com.opendroid.ai.core.llm.PromptBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** The part of a loaded LiteRT-LM engine that [LiteRTEngineHost] drives. */
internal interface LiteRTEngine {
    fun openSession(temperature: Double): LiteRTSession
    fun close()
}

/** One conversation on a [LiteRTEngine]. */
internal interface LiteRTSession {
    /** The text deltas of the reply to [prompt]. */
    fun textChunks(prompt: String): Flow<String>

    /** Ends the session whether or not it is still decoding. */
    fun cancelAndClose()
}

/**
 * Owns the one cached LiteRT-LM engine. The engine is not thread-safe, so [mutex] covers its
 * loading, each session's whole lifetime and its closing, and nothing else reaches the engine.
 *
 * The mutex is not reentrant and [stream] holds it while its collector runs, so a collector
 * must not call back into this host.
 */
internal class LiteRTEngineHost(
    private val openEngine: (modelPath: String, spec: OnDeviceModelSpec) -> LiteRTEngine
) {
    private val mutex = Mutex()
    private var cachedEngine: LiteRTEngine? = null
    @Volatile private var cachedModelPath: String? = null

    /**
     * Whether the cached engine holds [modelPath]. Read without [mutex], so it is only a hint,
     * good enough to skip a memory check for a model that is already resident.
     */
    fun holds(modelPath: String): Boolean = cachedModelPath == modelPath

    /**
     * Closes the cached engine if it was loaded from a file directly in [modelDir]. Waits for a
     * running session first, like a model switch does.
     */
    suspend fun closeIfLoadedFrom(modelDir: File) {
        mutex.withLock {
            val loadedFrom = cachedModelPath?.let { File(it).absoluteFile.parentFile } ?: return
            if (loadedFrom == modelDir.absoluteFile) closeEngine()
        }
    }

    /** Closes the cached engine, if any, once a running session ends. */
    suspend fun close() {
        mutex.withLock { closeEngine() }
    }

    /** Decodes [prompt] and emits each text delta, up to the output cap. */
    fun stream(
        modelPath: String,
        spec: OnDeviceModelSpec,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): Flow<String> = flow {
        val maxOutputTokens = checkPromptFits(prompt, spec, maxTokens)
        mutex.withLock {
            inSession(engineFor(modelPath, spec), temperature) { session ->
                PromptBudget.collectCapped(session.textChunks(prompt), maxOutputTokens) { emit(it) }
            }
        }
    }

    /** Decodes [prompt] and returns the whole reply, up to the output cap. */
    suspend fun complete(
        modelPath: String,
        spec: OnDeviceModelSpec,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val maxOutputTokens = checkPromptFits(prompt, spec, maxTokens)
        return mutex.withLock {
            val engine = try {
                engineFor(modelPath, spec)
            } catch (e: LinkageError) {
                Log.e(TAG, "[INIT FLOW] [FAILURE] JNI native library failed to load (liblitertlm_jni.so)", e)
                throw UnsatisfiedLinkError("LiteRT JNI native library failed to load (liblitertlm_jni.so). Error: ${e.localizedMessage}")
            } catch (e: Exception) {
                val cause = e.cause
                if (cause is UnsatisfiedLinkError) {
                    Log.e(TAG, "[INIT FLOW] [FAILURE] JNI native library failed to load (wrapped)", cause)
                    throw UnsatisfiedLinkError("LiteRT JNI native library failed to load (liblitertlm_jni.so). Error: ${cause.localizedMessage}")
                }
                Log.e(TAG, "[INIT FLOW] [FAILURE] Engine initialization failed", e)
                throw IOException("LiteRT Engine initialization failed: ${cause?.localizedMessage ?: e.localizedMessage}", cause ?: e)
            }

            try {
                inSession(engine, temperature) { session ->
                    PromptBudget.collectCapped(session.textChunks(prompt), maxOutputTokens) {}
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cause = e.cause
                Log.e(TAG, "[INIT FLOW] [FAILURE] Inference execution failed", e)
                throw IOException("LiteRT Inference execution failed: ${cause?.localizedMessage ?: e.localizedMessage}", cause ?: e)
            }
        }
    }

    /**
     * Verifies the prompt fits the model's context window BEFORE handing it to
     * the native runtime. LiteRT-LM aborts the whole process (native SIGABRT,
     * not a catchable exception) when the prompt overflows the engine's token
     * capacity — this guard turns that force close into a normal error message.
     */
    private fun checkPromptFits(prompt: String, spec: OnDeviceModelSpec, requestedMaxTokens: Int): Int {
        return PromptBudget.outputBudget(prompt, spec.contextWindow, requestedMaxTokens)
            ?: throw IllegalStateException(
                "This conversation is too long for ${spec.displayName} " +
                "(~${PromptBudget.budgetedPromptTokens(prompt)} tokens, limit ${spec.contextWindow}). " +
                "Start a new chat, shorten the message, or switch to a larger model."
            )
    }

    /**
     * Runs [decode] in a new session. A failing session says nothing about the engine, which stays
     * cached for the next one. A [LinkageError] does: the native side is unusable, so the engine
     * goes too, after its session.
     */
    private inline fun <T> inSession(
        engine: LiteRTEngine,
        temperature: Float,
        decode: (LiteRTSession) -> T
    ): T {
        try {
            val session = engine.openSession(temperature.toDouble())
            try {
                return decode(session)
            } finally {
                session.cancelAndClose()
            }
        } catch (e: LinkageError) {
            closeEngine()
            throw e
        }
    }

    /** Call with [mutex] held. */
    private fun engineFor(modelPath: String, spec: OnDeviceModelSpec): LiteRTEngine {
        if (cachedModelPath != modelPath) {
            Log.i(TAG, "[INIT FLOW] Active model path changed from '$cachedModelPath' to '$modelPath'. Resetting cached engine.")
            closeEngine()
        }
        return cachedEngine ?: openEngine(modelPath, spec).also {
            cachedEngine = it
            cachedModelPath = modelPath
        }
    }

    /** Call with [mutex] held. */
    private fun closeEngine() {
        cachedEngine?.let { engine ->
            try {
                Log.i(TAG, "Closing cached LiteRT engine")
                engine.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing cached engine: ${e.message}")
            }
        }
        cachedEngine = null
        cachedModelPath = null
    }

    private companion object {
        const val TAG = "LiteRTEngineHost"
    }
}
