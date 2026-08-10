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
    private val SUPPORTED_IMPORT_EXTENSIONS = setOf("litertlm", "task")

    fun folderName(modelId: String): String = when {
        OnDeviceModelRegistry.isCustomId(modelId) -> modelId
        modelId == "gemma-4-e2b-it-litert" -> "Gemma4-E2B"
        modelId == "gemma-4-e4b-it-litert" -> "Gemma4-E4B"
        modelId == "gemma-3n-e2b-it-litert" -> "Gemma3n-E2B"
        modelId == "gemma-3n-e4b-it-litert" -> "Gemma3n-E4B"
        else -> modelId.replace("-", "").replace("litert", "").replace("it", "")
    }

    /**
     * Turns a user-facing filename into a safe single path component for sandboxed storage.
     * Only `.litertlm` and `.task` are accepted. Returns null for unsupported formats
     * (e.g. GGUF) so callers can surface a clear error.
     */
    fun sanitizeImportFilename(rawName: String): String? {
        val leaf = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
            .ifBlank { return null }
        val extension = leaf.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
        if (extension !in SUPPORTED_IMPORT_EXTENSIONS) return null
        val baseRaw = leaf.substringBeforeLast('.')
        val base = baseRaw
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.', '-')
            .take(64)
            .ifBlank { "model" }
        val candidate = if (SAFE_PATH_COMPONENT.matches(base)) {
            "$base.$extension"
        } else {
            "model.$extension"
        }
        return candidate.takeIf { SAFE_PATH_COMPONENT.matches(it) }
    }

    fun isSupportedImportFilename(name: String): Boolean =
        sanitizeImportFilename(name) != null

    fun isLikelyUnsupportedGguf(name: String): Boolean =
        name.substringAfterLast('.', missingDelimiterValue = "")
            .equals("gguf", ignoreCase = true)

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
