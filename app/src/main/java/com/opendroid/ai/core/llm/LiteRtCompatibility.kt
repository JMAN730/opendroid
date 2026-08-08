package com.opendroid.ai.core.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * The selected LiteRT artifact could be read, but the bundled runtime could not
 * initialize it on this device. This is deliberately distinct from a malformed
 * artifact so callers can give users a safe, actionable error.
 */
class LiteRtRuntimeIncompatibilityException(cause: Exception) : Exception(cause)

/**
 * Structural compatibility probe for LiteRT model artifacts: initializing an
 * [Engine] against the file is the only validation the runtime exposes, and it
 * throws when the artifact is not a loadable LiteRT model.
 */
object LiteRtCompatibility {

    /**
     * Backends to try, in order. Gemma 4 LiteRT packages constrain their main
     * section to GPU, while older catalog models and arbitrary custom imports
     * may only load on CPU, and there is no per-model backend metadata to pick
     * from. Trying GPU first and falling back to CPU keeps both loadable.
     */
    val backendPreference: List<() -> Backend> = listOf({ Backend.GPU() }, { Backend.CPU() })

    fun verify(file: File, cacheDir: File) {
        val failures = mutableListOf<Exception>()
        for (backend in backendPreference) {
            val config = EngineConfig(
                modelPath = file.absolutePath,
                backend = backend(),
                cacheDir = cacheDir.absolutePath
            )
            try {
                Engine(config).use { engine ->
                    engine.initialize()
                }
                return
            } catch (e: Exception) {
                failures += e
            }
        }
        // Every backend failed. Only report runtime incompatibility when the
        // failures name a device/backend limitation; ordinary parse/load errors
        // mean a malformed artifact and must stay classified as FORMAT_INVALID.
        val first = failures.first()
        if (failures.all { isBackendIncompatibility(it) }) {
            throw LiteRtRuntimeIncompatibilityException(first)
        }
        throw first
    }

    private val BACKEND_FAILURE_MARKERS =
        listOf("gpu", "opencl", "vulkan", "delegate", "backend", "accelerator")

    /**
     * The runtime does not expose typed failures, so the only available signal
     * that a failure is about the device rather than the file is the message.
     */
    internal fun isBackendIncompatibility(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message?.lowercase()
            if (message != null && BACKEND_FAILURE_MARKERS.any { message.contains(it) }) return true
            current = current.cause
        }
        return false
    }
}
