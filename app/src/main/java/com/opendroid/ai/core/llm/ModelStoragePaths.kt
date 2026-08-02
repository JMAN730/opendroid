package com.opendroid.ai.core.llm

import java.io.File

/**
 * Shared on-disk layout for LiteRT-LM models under `models/<folder>/`.
 *
 * New imports/downloads use [OnDeviceModelSpec.modelFilename] (e.g. `.litertlm`).
 * Existing installs that stored `model.task` remain readable via [resolveExistingFile].
 */
object ModelStoragePaths {
    const val LEGACY_TASK_FILENAME = "model.task"
    /** A lightweight import sanity check; it never establishes artifact integrity. */
    const val MIN_LOCAL_IMPORT_BYTES = 10L * 1024 * 1024
    private val SAFE_PATH_COMPONENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun folderName(modelId: String): String = when (modelId) {
        "gemma-4-e2b-it-litert" -> "Gemma4-E2B"
        "gemma-4-e4b-it-litert" -> "Gemma4-E4B"
        "gemma-3n-e2b-it-litert" -> "Gemma3n-E2B"
        "gemma-3n-e4b-it-litert" -> "Gemma3n-E4B"
        else -> modelId.replace("-", "").replace("litert", "").replace("it", "")
    }

    fun modelDir(modelsRoot: File, modelId: String): File =
        containedChild(modelsRoot, folderName(modelId))

    /** Destination file for a new download or local import. */
    fun targetFile(modelDir: File, spec: OnDeviceModelSpec): File {
        val name = spec.modelFilename.ifBlank { LEGACY_TASK_FILENAME }
        return containedChild(modelDir, name)
    }

    fun manifestFile(modelDir: File): File = containedChild(modelDir, "manifest.json")

    /**
     * Resolves an existing model binary: prefer [OnDeviceModelSpec.modelFilename],
     * then legacy `model.task`.
     */
    fun resolveExistingFile(modelDir: File, spec: OnDeviceModelSpec): File? {
        val primaryName = spec.modelFilename.ifBlank { LEGACY_TASK_FILENAME }
        val primary = File(modelDir, primaryName)
        if (primary.exists() && primary.length() > 0L) return primary

        if (primaryName != LEGACY_TASK_FILENAME) {
            val legacy = File(modelDir, LEGACY_TASK_FILENAME)
            if (legacy.exists() && legacy.length() > 0L) return legacy
        }
        return null
    }

    /**
     * Model identifiers and registry filenames become filesystem paths. Reject separators,
     * traversal components, and symlink escapes before performing any destructive operation.
     */
    private fun containedChild(directory: File, childName: String): File {
        require(SAFE_PATH_COMPONENT.matches(childName) && childName != "." && childName != "..") {
            "Invalid model storage path component"
        }
        val canonicalDirectory = directory.canonicalFile
        val canonicalChild = File(canonicalDirectory, childName).canonicalFile
        require(canonicalChild.parentFile == canonicalDirectory) {
            "Model storage path escapes its directory"
        }
        return canonicalChild
    }

}

/** Result of [com.opendroid.ai.data.repository.ModelRepository.importLocalModel]. */
sealed class ImportLocalModelResult {
    data object Success : ImportLocalModelResult()
    data class Failure(val reason: String) : ImportLocalModelResult()
}
