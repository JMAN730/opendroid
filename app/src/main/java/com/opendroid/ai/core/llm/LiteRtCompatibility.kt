package com.opendroid.ai.core.llm

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * Structural compatibility probe for LiteRT model artifacts: initializing an
 * [Engine] against the file is the only validation the runtime exposes, and it
 * throws when the artifact is not a loadable LiteRT model.
 */
object LiteRtCompatibility {

    fun verify(file: File, cacheDir: File) {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = cacheDir.absolutePath
        )
        Engine(config).use { engine ->
            engine.initialize()
        }
    }
}
