package com.opendroid.ai.core.llm

import java.io.File

/**
 * Lets model storage drop the cached on-device engine before it deletes the files the engine
 * was loaded from, so a deleted model does not stay resident in memory.
 */
fun interface OnDeviceEngineReleaser {
    /** Closes the cached engine if it was loaded from a file in [modelDir]; otherwise does nothing. */
    suspend fun releaseIfLoadedFrom(modelDir: File)
}
