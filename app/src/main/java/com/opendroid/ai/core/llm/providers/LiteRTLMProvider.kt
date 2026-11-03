package com.opendroid.ai.core.llm.providers

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents

/**
 * LLM provider backed by LiteRT-LM (com.google.ai.edge.litertlm).
 *
 * This provider runs LiteRT models entirely on-device using the LiteRT runtime
 * with GPU/NPU acceleration. It does NOT require Google AI Core / Play Services.
 *
 * Catalog models are defined in [OnDeviceModelRegistry.liteRTOnly]; freestanding
 * custom imports are resolved through [ModelRepository.resolveLiteRTSpec].
 */
@Singleton
class LiteRTLMProvider @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val modelRepository: dagger.Lazy<com.opendroid.ai.data.repository.ModelRepository>
) : LLMProvider {

    private val engineHost = LiteRTEngineHost(::openEngine)
    private val artifactVerifier = ModelArtifactVerifier(
        hashVerifier = CachingFileHashVerifier()
    )

    companion object {
        private const val TAG = "LiteRTLMProvider"
    }

    override val name: String = "LiteRT-LM (On-device)"
    override val availableModels: List<String>
        get() = OnDeviceModelRegistry.liteRTOnly.map { it.id }

    /**
     * Resolves the model spec for the currently selected model.
     * Falls back to the recommended LiteRT model if the selection isn't a LiteRT model.
     */
    private fun resolveModelSpec(modelId: String): OnDeviceModelSpec {
        return modelRepository.get().resolveLiteRTSpec(modelId)
            ?: OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM)
            ?: throw IllegalStateException("No LiteRT-LM models registered in OnDeviceModelRegistry")
    }

    /**
     * Returns the local file path where a model should be stored / loaded from.
     */
    private fun getModelFilePath(spec: OnDeviceModelSpec): String {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelDir = ModelStoragePaths.modelDir(File(baseDir, "models"), spec.id)
        if (!modelDir.exists()) modelDir.mkdirs()

        ModelStoragePaths.resolveExistingFile(modelDir, spec)?.let { existing ->
            return existing.absolutePath
        }

        return ModelStoragePaths.targetFile(modelDir, spec).absolutePath
    }

    /**
     * Checks whether a given model file has been downloaded and is ready.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val spec = modelRepository.get().resolveLiteRTSpec(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        val manifestFile = modelFile.parentFile?.let(ModelStoragePaths::manifestFile) ?: return false
        return artifactVerifier.verifyForStartup(modelFile, manifestFile, spec) ==
            ArtifactVerificationResult.Valid
    }

    /**
     * Returns the download status for all LiteRT-LM catalog models.
     * Map of model ID → downloaded boolean.
     */
    fun getAllModelStatuses(): Map<String, Boolean> {
        return OnDeviceModelRegistry.liteRTOnly.associate { spec ->
            spec.id to isModelDownloaded(spec.id)
        }
    }

    /** Closes the cached engine if it was loaded from [modelDir], once its session ends. */
    suspend fun releaseIfLoadedFrom(modelDir: File) = engineHost.closeIfLoadedFrom(modelDir)

    /** Closes the cached engine, whichever model it holds, once its session ends. */
    suspend fun releaseEngine() = engineHost.close()

    /**
     * Deletes a downloaded model to free storage.
     */
    fun deleteModel(modelId: String): Boolean {
        val spec = modelRepository.get().resolveLiteRTSpec(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        val deleted = if (modelFile.exists()) modelFile.delete() else true
        modelFile.parentFile?.let(ModelStoragePaths::manifestFile)?.delete()
        return deleted
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        val modelId = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)
        val spec = resolveModelSpec(modelId)

        return withContext(Dispatchers.IO) {
            try {
                checkSdkCompatibility(spec)
                val modelPath = getModelFilePath(spec)
                checkModelReady(modelPath, spec)

                val prompt = buildPrompt(
                    request.systemPrompt,
                    request.messages,
                    request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                        ?: emptyList(),
                    spec,
                    request.maxTokens
                )

                val outputText = invokeLiteRTInference(modelPath, spec, prompt, request.maxTokens, request.temperature)

                LLMResponse(
                    content = outputText,
                    tokensUsed = outputText.length / 4,
                    model = spec.id,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw handleThrowable(e, spec)
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        val modelId = request.model?.takeIf { it.isNotBlank() } ?: ProviderCatalog.defaultModel(name)
        try {
            val spec = resolveModelSpec(modelId)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val prompt = buildPrompt(
                request.systemPrompt,
                request.messages,
                request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                    ?: emptyList(),
                spec,
                request.maxTokens
            )

            emitAll(engineHost.stream(modelPath, spec, prompt, request.maxTokens, request.temperature))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val spec = resolveModelSpec(modelId)
            emit("Error (LiteRT-LM): ${handleThrowable(e, spec).localizedMessage}")
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        val modelId = settingsRepository.llmConfig.first().selectedModelFor(name)
        try {
            val spec = resolveModelSpec(modelId)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val systemPrompt = "You are an autonomous AI agent for Android."
            val prompt = buildPrompt(systemPrompt, messages, tools, spec, 512)

            val result = invokeLiteRTInference(modelPath, spec, prompt, 2000, 0.7f)
            emit(StreamChunk.Content(result))

            // Attempt tool call extraction from JSON response
            try {
                val cleaned = result.trim()
                if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
                    val jsonObj = JSONObject(cleaned)
                    if (jsonObj.has("toolCall")) {
                        val toolCallObj = jsonObj.getJSONObject("toolCall")
                        val toolName = toolCallObj.getString("name")
                        val argsObj = toolCallObj.optJSONObject("arguments") ?: JSONObject()
                        emit(StreamChunk.ToolCall(toolName, argsObj.toString()))
                    }
                }
            } catch (_: Throwable) {
                // Not JSON — treat as plain text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val spec = resolveModelSpec(modelId)
            emit(StreamChunk.Content("Error (LiteRT-LM): ${handleThrowable(e, spec).localizedMessage}"))
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // LiteRT-LM requires Android 12+ (API 31) for many catalog models; smaller models
            // (including custom imports) run on the app's minSdk, so no extra SDK gate here.
            // Per-model SDK requirements are enforced by checkSdkCompatibility().
            // Check if at least one catalog or custom model is ready on disk
            OnDeviceModelRegistry.liteRTOnly.any { spec ->
                isModelDownloaded(spec.id)
            } || modelRepository.get().allModelsFlow.first().any { entity ->
                OnDeviceModelRegistry.isCustomId(entity.id) &&
                    entity.status == com.opendroid.ai.data.db.entities.ModelStatus.READY &&
                    isModelDownloaded(entity.id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "isAvailable check failed: ${e.message}")
            false
        }
    }

    /**
     * Checks whether the device SDK level meets the model's requirements.
     */
    private fun checkSdkCompatibility(spec: OnDeviceModelSpec) {
        if (Build.VERSION.SDK_INT < spec.minSdk) {
            throw IllegalStateException(
                "${spec.displayName} requires Android API ${spec.minSdk}+ " +
                "(device is API ${Build.VERSION.SDK_INT})."
            )
        }
    }

    private fun verifyModelFileIntegrity(modelPath: String, spec: OnDeviceModelSpec) {
        val file = File(modelPath)
        val manifestFile = file.parentFile?.let(ModelStoragePaths::manifestFile)
            ?: throw IOException("Model integrity metadata is unavailable. Reinstall the model.")
        when (artifactVerifier.verifyBeforeNativeLoad(file, manifestFile, spec)) {
            ArtifactVerificationResult.Valid -> Unit
            is ArtifactVerificationResult.Invalid -> {
                throw IOException(
                    "Model integrity verification failed. Delete and redownload the model, or import it again."
                )
            }
        }
    }

    /**
     * Verifies the model file exists and is valid.
     */
    private fun checkModelReady(modelPath: String, spec: OnDeviceModelSpec) {
        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(
            context,
            spec,
            alreadyLoaded = engineHost.holds(modelPath)
        )
        verifyModelFileIntegrity(modelPath, spec)
    }

    /** Loads the model into a new native engine, trying each backend the model may run on. */
    private fun openEngine(modelPath: String, spec: OnDeviceModelSpec): LiteRTEngine {
        verifyModelFileIntegrity(modelPath, spec)

        Log.i(TAG, "[INIT FLOW] Configuring EngineConfig with path: $modelPath, maxNumTokens: ${spec.contextWindow}")
        // maxNumTokens is the TOTAL token capacity (input + output) of the
        // engine. It must match the model's KV-cache size (spec.contextWindow),
        // NOT a request's output-token budget — undersizing it makes the native
        // runtime abort (force close) as soon as a prompt exceeds it.
        // Gemma 4 LiteRT packages require the GPU-constrained main section,
        // while older catalog models and custom imports may only load on
        // CPU, so fall back rather than locking every model to one backend.
        Log.i(TAG, "[INIT FLOW] Initializing Engine (loading model)...")
        var lastFailure: Throwable? = null
        val backends = if (spec.modelFilename.contains("q8", ignoreCase = true) || spec.family.equals("Qwen", ignoreCase = true)) {
            listOf<() -> Backend>({ Backend.CPU() }, { Backend.GPU() })
        } else {
            LiteRtCompatibility.backendPreference
        }
        for (backend in backends) {
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend(),
                maxNumTokens = spec.contextWindow,
                cacheDir = context.cacheDir.absolutePath
            )
            var candidate: Engine? = null
            try {
                candidate = Engine(config)
                candidate.initialize()
                Log.i(TAG, "[INIT FLOW] LiteRT Engine initialized successfully on ${config.backend} and cached.")
                return NativeEngine(candidate)
            } catch (e: Throwable) {
                Log.e(TAG, "[INIT FLOW] Failed to initialize LiteRT Engine on ${config.backend}.", e)
                lastFailure = e
                runCatching { candidate?.close() }
            }
        }
        Log.e(TAG, "[INIT FLOW] [CRITICAL FAILURE] Failed to initialize LiteRT Engine on every backend.", lastFailure)
        throw lastFailure ?: IllegalStateException("LiteRT Engine initialization produced no engine")
    }

    private class NativeEngine(private val engine: Engine) : LiteRTEngine {
        override fun openSession(temperature: Double): LiteRTSession {
            val samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = temperature,
                seed = 0
            )
            val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
            return NativeSession(engine.createConversation(conversationConfig))
        }

        override fun close() = engine.close()
    }

    private class NativeSession(private val conversation: Conversation) : LiteRTSession {
        /** LiteRT-LM emits only the new text in each message. */
        override fun textChunks(prompt: String): Flow<String> =
            conversation.sendMessageAsync(prompt)
                .map { msg -> msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
                .filter { it.isNotEmpty() }

        /**
         * The flow from `sendMessageAsync` does not cancel anything when its collector stops,
         * so decoding has to be cancelled here. Cancelling after decoding has finished is a no-op.
         */
        override fun cancelAndClose() {
            try {
                conversation.cancelProcess()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling LiteRT conversation: ${e.message}")
            }
            conversation.close()
        }
    }

    private suspend fun invokeLiteRTInference(
        modelPath: String,
        spec: OnDeviceModelSpec,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        Log.i(TAG, "[INIT FLOW] Verifying model file status at: $modelPath")
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e(TAG, "[INIT FLOW] [FAILURE] Model file does not exist: $modelPath")
            throw java.io.FileNotFoundException("Model file not downloaded / missing at path: $modelPath")
        }
        if (modelFile.isDirectory) {
            Log.e(TAG, "[INIT FLOW] [FAILURE] Model path is a directory: $modelPath")
            throw IllegalArgumentException("Invalid model path: '$modelPath' is a directory, not a file.")
        }
        if (!modelFile.canRead()) {
            Log.e(TAG, "[INIT FLOW] [FAILURE] Model file is not readable: $modelPath")
            throw IOException("Model file at '$modelPath' exists but is not readable. Check storage permissions.")
        }
        Log.i(TAG, "[INIT FLOW] [SUCCESS] Model file verified (size: ${modelFile.length()} bytes).")

        val result = engineHost.complete(modelPath, spec, prompt, maxTokens, temperature)
        Log.i(TAG, "[INIT FLOW] [SUCCESS] Inference succeeded. Received response of length ${result.length}.")
        return result
    }

    // ── Prompt building (shared with GemmaProvider pattern) ─────────────

    private fun buildPrompt(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        spec: OnDeviceModelSpec? = null,
        requestedMaxTokens: Int = 512
    ): String {
        val window = spec?.contextWindow ?: 2048
        val minOutput = minOf(requestedMaxTokens, 512).coerceAtLeast(PromptBudget.MIN_OUTPUT_TOKENS)

        val sbHeader = StringBuilder()
        val trimmedSystemPrompt = PromptBudget.trimSystemPrompt(systemPrompt, window)

        if (trimmedSystemPrompt.isNotEmpty()) {
            sbHeader.append("System Instructions:\n").append(trimmedSystemPrompt).append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sbHeader.append("Available tools you can call:\n")
            tools.forEach { tool ->
                sbHeader.append("- Tool: ").append(tool.name).append("\n")
                sbHeader.append("  Description: ").append(tool.description).append("\n")
                sbHeader.append("  Parameters schema: ").append(tool.parameters).append("\n\n")
            }
            sbHeader.append("If you need to call a tool, respond ONLY with a JSON object conforming exactly to this format:\n")
            sbHeader.append("{\n")
            sbHeader.append("  \"toolCall\": {\n")
            sbHeader.append("    \"name\": \"TOOL_NAME\",\n")
            sbHeader.append("    \"arguments\": { ... }\n")
            sbHeader.append("  }\n")
            sbHeader.append("}\n")
            sbHeader.append("Do not add markdown formatting or backticks around the JSON. Output only the raw JSON. If no tool is needed, respond with standard text.\n\n")
        }

        val headerText = sbHeader.toString()
        val overheadTokens = PromptBudget.estimateTokens(headerText)

        val fittedMessages = PromptBudget.fitMessagesToBudget(
            messages = messages,
            contextWindow = window,
            reservedOutputTokens = minOutput,
            overheadTokens = overheadTokens
        )

        val sb = StringBuilder(headerText)
        sb.append("Conversation History:\n")
        fittedMessages.forEach { msg ->
            val sender = if (msg.sender == ChatMessage.Sender.USER) "User" else "Model"
            sb.append(sender).append(": ").append(msg.text).append("\n")
        }
        sb.append("Model:")
        return sb.toString()
    }

    private fun handleThrowable(e: Throwable, spec: OnDeviceModelSpec): Throwable {
        return when (e) {
            is IllegalStateException -> e
            is IOException -> e
            is ClassNotFoundException -> e
            is IllegalArgumentException -> e
            is UnsatisfiedLinkError -> e
            else -> {
                val msg = e.localizedMessage ?: ""
                when {
                    msg.contains("memory", ignoreCase = true) ||
                    msg.contains("OOM", ignoreCase = true) ->
                        IOException("Not enough memory to run ${spec.displayName}. Try a smaller model.", e)
                    msg.contains("GPU", ignoreCase = true) ||
                    msg.contains("delegate", ignoreCase = true) ->
                        IOException("GPU acceleration unavailable for ${spec.displayName}. Check device compatibility.", e)
                    else ->
                        IOException("LiteRT-LM error (${spec.displayName}): $msg", e)
                }
            }
        }
    }
}
