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

    fun verify(file: File, cacheDir: File) {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            // Gemma 4 LiteRT packages constrain their main section to GPU. A CPU
            // probe rejects valid artifacts before they can be imported.
            backend = Backend.GPU(),
            cacheDir = cacheDir.absolutePath
        )
        try {
            Engine(config).use { engine ->
                engine.initialize()
            }
        } catch (e: Exception) {
            throw LiteRtRuntimeIncompatibilityException(e)
        }
    }
}
