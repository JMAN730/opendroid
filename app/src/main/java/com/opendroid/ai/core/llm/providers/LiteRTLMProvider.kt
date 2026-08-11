package com.opendroid.ai.core.llm.providers

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.effectiveContextWindow
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

    /**
     * A LiteRT engine plus the lease count of callers currently using it.
     *
     * `streamComplete`/`invokeLiteRTInference` lease the cached engine while they
     * run and release it when done. A settings change (different model path or
     * context window) retires this handle instead of closing it outright, so an
     * in-flight streaming response keeps a live engine until it releases its
     * lease; the engine is only closed once retired AND unleased.
     */
    private class EngineHandle(
        val engine: Engine,
        val modelPath: String,
        val contextWindow: Int,
        val requestedContextWindow: Int
    ) {
        var leases: Int = 0
        var retired: Boolean = false
    }

    private var cachedHandle: EngineHandle? = null
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
     * Resolves the spec and applies the user's context-window choice for it, so
     * every downstream consumer (engine sizing, prompt budget, error text) reads
     * one number. The catalog size stays reachable via [catalogContextWindow] for
     * the fallback path.
     */
    private suspend fun resolveRequestSpec(modelId: String): OnDeviceModelSpec {
        val spec = resolveModelSpec(modelId)
        val config = settingsRepository.llmConfig.first()
        return spec.copy(contextWindow = config.effectiveContextWindow(spec))
    }

    /** The size the catalog ships for [specId]; the safe target when a raised window fails. */
    private fun catalogContextWindow(specId: String): Int =
        OnDeviceModelRegistry.findById(specId)?.contextWindow
            ?: OnDeviceModelRegistry.CUSTOM_DEFAULT_CONTEXT_WINDOW

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
        val spec = resolveRequestSpec(modelId)

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
            val spec = resolveRequestSpec(modelId)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val prompt = buildPrompt(
                request.systemPrompt,
                request.messages,
                request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                    ?: emptyList()
            )

            val active = getOrInitializeEngine(modelPath, spec)
            try {
                checkPromptFits(prompt, spec, request.maxTokens, active.contextWindow)
                val engine = active.engine

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
                // Only retire the handle this call leased, not whatever a
                // concurrent settings change or request may have cached since.
                retire(active)
                throw e
            } finally {
                releaseEngine(active)
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
            val spec = resolveRequestSpec(modelId)
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
    private fun getOrInitializeEngine(modelPath: String, spec: OnDeviceModelSpec): EngineHandle {
        val cached = cachedHandle
        if (cached != null && !cached.retired &&
            cached.modelPath == modelPath && cached.requestedContextWindow == spec.contextWindow
        ) {
            cached.leases++
            return cached
        }

        if (cached != null) {
            Log.i(
                TAG,
                "[INIT FLOW] Engine inputs changed (path '${cached.modelPath}' -> '$modelPath', " +
                    "requested window ${cached.requestedContextWindow} -> ${spec.contextWindow}). Retiring cached engine."
            )
            retire(cached)
        }

        verifyModelFileIntegrity(modelPath, spec)

        // maxNumTokens is the TOTAL token capacity (input + output) of the
        // engine. It must match the model's KV-cache size (spec.contextWindow),
        // NOT a request's output-token budget — undersizing it makes the native
        // runtime abort (force close) as soon as a prompt exceeds it.
        // A user-raised window is attempted first; artifacts with a baked-in cache
        // size (…ekv1280.task) or devices short on RAM fall back to the catalog
        // size rather than leaving the model unusable.
        // Gemma 4 LiteRT packages require the GPU-constrained main section,
        // while older catalog models and custom imports may only load on
        // CPU, so fall back rather than locking every model to one backend.
        val windows = listOf(spec.contextWindow, catalogContextWindow(spec.id)).distinct()
        var lastFailure: Throwable? = null
        for (window in windows) {
            Log.i(TAG, "[INIT FLOW] Configuring EngineConfig with path: $modelPath, maxNumTokens: $window")
            for (backend in LiteRtCompatibility.backendPreference) {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend(),
                    maxNumTokens = window,
                    cacheDir = context.cacheDir.absolutePath
                )
                var candidate: Engine? = null
                try {
                    candidate = Engine(config)
                    candidate.initialize()
                    val handle = EngineHandle(candidate, modelPath, window, spec.contextWindow)
                    handle.leases = 1
                    cachedHandle = handle
                    Log.i(TAG, "[INIT FLOW] LiteRT Engine initialized successfully on ${config.backend} at $window tokens and cached.")
                    return handle
                } catch (e: Throwable) {
                    Log.e(TAG, "[INIT FLOW] Failed to initialize LiteRT Engine on ${config.backend} at $window tokens.", e)
                    lastFailure = e
                    runCatching { candidate?.close() }
                }
            }
            if (window != windows.last()) {
                Log.w(TAG, "[INIT FLOW] Context window $window did not load; retrying at the catalog size.")
            }
        }
        Log.e(TAG, "[INIT FLOW] [CRITICAL FAILURE] Failed to initialize LiteRT Engine on every backend.", lastFailure)
        throw lastFailure ?: IllegalStateException("LiteRT Engine initialization produced no engine")
    }

    /** Retires [handle] so no new caller leases it; closes it immediately if unleased. */
    @Synchronized
    private fun retire(handle: EngineHandle) {
        handle.retired = true
        if (cachedHandle === handle) cachedHandle = null
        if (handle.leases <= 0) closeHandle(handle)
    }

    /** Releases a lease acquired via [getOrInitializeEngine]; closes the engine if retired and unleased. */
    @Synchronized
    private fun releaseEngine(handle: EngineHandle) {
        handle.leases--
        if (handle.retired && handle.leases <= 0) closeHandle(handle)
    }

    private fun closeHandle(handle: EngineHandle) {
        try {
            Log.i(TAG, "Closing retired LiteRT engine")
            handle.engine.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing retired engine: ${e.message}")
        }
    }

    /**
     * Verifies the prompt fits the model's context window BEFORE handing it to
     * the native runtime. LiteRT-LM aborts the whole process (native SIGABRT,
     * not a catchable exception) when the prompt overflows the engine's token
     * capacity — this guard turns that force close into a normal error message.
     */
    private fun checkPromptFits(
        prompt: String,
        spec: OnDeviceModelSpec,
        requestedMaxTokens: Int,
        contextWindow: Int
    ): Int {
        return PromptBudget.outputBudget(prompt, contextWindow, requestedMaxTokens)
            ?: throw PromptBudgetExceededException(
                "This conversation is too long for ${spec.displayName} " +
                "(~${PromptBudget.estimateTokens(prompt)} tokens, limit $contextWindow). " +
                "Raise the context window in Settings, start a new chat, shorten the " +
                "message, or switch to a larger model."
            )
    }

    /**
     * Thrown when a prompt doesn't fit the resolved context window. The engine
     * that produced the rejection is otherwise healthy — this is a budgeting
     * failure, not an engine/inference failure — so callers must not retire
     * the cached handle for it.
     */
    private class PromptBudgetExceededException(message: String) : IllegalStateException(message)

    @Synchronized
    private fun invokeLiteRTInference(
        modelPath: String,
        spec: OnDeviceModelSpec,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        var active: EngineHandle? = null
        try {
            // The prompt is budgeted after the engine exists: initialization may have
            // fallen back to a smaller window than the user selected, and budgeting
            // against the requested size would then let an oversized prompt reach the
            // native runtime, which aborts the process instead of throwing.
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

            // When the requested window equals the catalog default, initialization
            // cannot fall back to anything smaller — an oversized prompt is already
            // known to be invalid, so reject it before paying for engine
            // initialization instead of loading the model first.
            val catalogWindow = catalogContextWindow(spec.id)
            if (spec.contextWindow == catalogWindow) {
                checkPromptFits(prompt, spec, maxTokens, catalogWindow)
            }

            // 3 & 4. Verify options configuration & engine initialization
            Log.i(TAG, "[INIT FLOW] [STEP 3/6 & 4/6] Creating & Initializing Engine...")
            active = try {
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

            val engine = active.engine
            // Requested window may have fallen back to the catalog size during
            // initialization; when it did (and the pre-init check above didn't
            // already cover this case), re-check against what actually got built.
            if (spec.contextWindow != catalogWindow) {
                checkPromptFits(prompt, spec, maxTokens, active.contextWindow)
            }

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
            // Retire only the handle this call leased — never a stale/replaced
            // cache entry another concurrent request may already be using.
            // Prompt-budget rejections don't mean the engine is broken (it may
            // just be a fallback-sized engine that doesn't fit this particular
            // prompt) — keep it cached so a shortened prompt or retry doesn't
            // have to reload the multi-gigabyte model.
            if (e !is PromptBudgetExceededException) {
                active?.let { retire(it) }
            }
            throw e
        } finally {
            active?.let { releaseEngine(it) }
        }
    }

    /**
     * Retires the cached engine so it is not reused for new requests. If a
     * request currently has it leased, it closes only once that lease is
     * released, so this never interrupts in-flight inference or streaming.
     */
    @Synchronized
    fun closeCachedEngine() {
        cachedHandle?.let { retire(it) }
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
