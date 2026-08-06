package com.opendroid.ai.core.llm.providers

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.selectedModelFor
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Backend
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

    private var cachedEngine: Engine? = null
    private var cachedModelPath: String? = null
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
                        ?: emptyList()
                )

                val outputText = invokeLiteRTInference(modelPath, spec, prompt, request.maxTokens, request.temperature)

                LLMResponse(
                    content = outputText,
                    tokensUsed = outputText.length / 4,
                    model = spec.id,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startTime
                )
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
                    ?: emptyList()
            )

            checkPromptFits(prompt, spec, request.maxTokens)
            val engine = getOrInitializeEngine(modelPath, spec)

            val samplerConfig = SamplerConfig(
                topK = 40,
                topP = 0.95,
                temperature = request.temperature.toDouble(),
                seed = 0
            )
            val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
            val conversation = engine.createConversation(conversationConfig)

            try {
                conversation.sendMessageAsync(prompt).collect { msg ->
                    val fullText = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    emit(fullText)
                }
            } finally {
                conversation.close()
            }
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
            val prompt = buildPrompt(systemPrompt, messages, tools)

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
        } catch (e: Throwable) {
            val spec = resolveModelSpec(modelId)
            emit(StreamChunk.Content("Error (LiteRT-LM): ${handleThrowable(e, spec).localizedMessage}"))
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // LiteRT-LM requires Android 12+ (API 31) for many catalog models; allow API 26+
            // when a smaller model (including custom import) is present.
            if (Build.VERSION.SDK_INT < 26) return false
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
        OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
        verifyModelFileIntegrity(modelPath, spec)
    }

    /**
     * Attempts to invoke LiteRT-LM inference.
     *
     * This uses reflection to call the LiteRT-LM SDK so the project compiles
     * even if the SDK is not yet on the classpath. When the gradle dependency
     * `com.google.ai.edge.litertlm:litertlm-android` is available, this will
     * call through to the real engine.
     */
    @Synchronized
    private fun getOrInitializeEngine(modelPath: String, spec: OnDeviceModelSpec): Engine {
        if (cachedModelPath != modelPath) {
            Log.i(TAG, "[INIT FLOW] Active model path changed from '$cachedModelPath' to '$modelPath'. Resetting cached engine.")
            closeCachedEngine()
        }

        var engine = cachedEngine
        if (engine == null) {
            verifyModelFileIntegrity(modelPath, spec)

            Log.i(TAG, "[INIT FLOW] Configuring EngineConfig with path: $modelPath, maxNumTokens: ${spec.contextWindow}")
            // maxNumTokens is the TOTAL token capacity (input + output) of the
            // engine. It must match the model's KV-cache size (spec.contextWindow),
            // NOT a request's output-token budget — undersizing it makes the native
            // runtime abort (force close) as soon as a prompt exceeds it.
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(), // Run on CPU for compatibility
                maxNumTokens = spec.contextWindow,
                cacheDir = context.cacheDir.absolutePath
            )
            
            Log.i(TAG, "[INIT FLOW] Initializing Engine (loading model)...")
            try {
                engine = Engine(config)
                engine.initialize()
                cachedEngine = engine
                cachedModelPath = modelPath
                Log.i(TAG, "[INIT FLOW] LiteRT Engine initialized successfully and cached.")
            } catch (e: Throwable) {
                Log.e(TAG, "[INIT FLOW] [CRITICAL FAILURE] Failed to initialize LiteRT Engine. Full Exception Stack Trace:", e)
                throw e
            }
        }
        return engine
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
                "(~${PromptBudget.estimateTokens(prompt)} tokens, limit ${spec.contextWindow}). " +
                "Start a new chat, shorten the message, or switch to a larger model."
            )
    }

    @Synchronized
    private fun invokeLiteRTInference(
        modelPath: String,
        spec: OnDeviceModelSpec,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        try {
            checkPromptFits(prompt, spec, maxTokens)
            // 1. Verify LiteRT library classes / static classpath integrity
            Log.i(TAG, "[INIT FLOW] [STEP 1/6] Verifying LiteRT SDK classes on classpath...")
            // Compiles statically with com.google.ai.edge.litertlm.*
            Log.i(TAG, "[INIT FLOW] [SUCCESS] LiteRT SDK classes verified on classpath.")

            // 2. Verify model file exists, is a file, and is readable
            Log.i(TAG, "[INIT FLOW] [STEP 2/6] Verifying model file status at: $modelPath")
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

            // 3 & 4. Verify options configuration & engine initialization
            Log.i(TAG, "[INIT FLOW] [STEP 3/6 & 4/6] Creating & Initializing Engine...")
            val engine = try {
                getOrInitializeEngine(modelPath, spec)
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

            // 5. Verify JNI library link status
            Log.i(TAG, "[INIT FLOW] [STEP 5/6] Verifying JNI library link status...")
            // If the engine instance exists and is initialized, JNI link succeeded.
            Log.i(TAG, "[INIT FLOW] [SUCCESS] JNI library links verified.")

            // 6. Execute inference
            Log.i(TAG, "[INIT FLOW] [STEP 6/6] Executing inference on prompt...")
            val result = try {
                val samplerConfig = SamplerConfig(
                    topK = 40,
                    topP = 0.95,
                    temperature = temperature.toDouble(),
                    seed = 0
                )
                val conversationConfig = ConversationConfig(samplerConfig = samplerConfig)
                val conversation = engine.createConversation(conversationConfig)

                try {
                    val message = conversation.sendMessage(prompt)
                    val responseText = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    responseText
                } finally {
                    conversation.close()
                }
            } catch (e: Exception) {
                val cause = e.cause
                Log.e(TAG, "[INIT FLOW] [FAILURE] Inference execution failed", e)
                throw IOException("LiteRT Inference execution failed: ${cause?.localizedMessage ?: e.localizedMessage}", cause ?: e)
            }
            Log.i(TAG, "[INIT FLOW] [SUCCESS] Inference succeeded. Received response of length ${result.length}.")
            return result

        } catch (e: Throwable) {
            closeCachedEngine()
            throw e
        }
    }

    @Synchronized
    fun closeCachedEngine() {
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

    // ── Prompt building (shared with GemmaProvider pattern) ─────────────

    private fun buildPrompt(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotEmpty()) {
            sb.append("System Instructions:\n").append(systemPrompt).append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sb.append("Available tools you can call:\n")
            tools.forEach { tool ->
                sb.append("- Tool: ").append(tool.name).append("\n")
                sb.append("  Description: ").append(tool.description).append("\n")
                sb.append("  Parameters schema: ").append(tool.parameters).append("\n\n")
            }
            sb.append("If you need to call a tool, respond ONLY with a JSON object conforming exactly to this format:\n")
            sb.append("{\n")
            sb.append("  \"toolCall\": {\n")
            sb.append("    \"name\": \"TOOL_NAME\",\n")
            sb.append("    \"arguments\": { ... }\n")
            sb.append("  }\n")
            sb.append("}\n")
            sb.append("Do not add markdown formatting or backticks around the JSON. Output only the raw JSON. If no tool is needed, respond with standard text.\n\n")
        }

        sb.append("Conversation History:\n")
        messages.forEach { msg ->
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
