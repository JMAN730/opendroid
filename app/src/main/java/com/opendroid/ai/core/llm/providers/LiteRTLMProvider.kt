package com.opendroid.ai.core.llm.providers

import android.content.Context
import android.os.Build
import android.util.Log
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM provider backed by LiteRT-LM (com.google.ai.edge.litertlm).
 *
 * This provider runs Gemma models entirely on-device using the LiteRT runtime
 * with GPU/NPU acceleration. It does NOT require Google AI Core / Play Services.
 *
 * Supported models are defined in [OnDeviceModelRegistry.liteRTOnly].
 */
@Singleton
class LiteRTLMProvider @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) : LLMProvider {

    companion object {
        private const val TAG = "LiteRTLMProvider"
        private const val MODELS_DIR = "litert_models"
    }

    override val name: String = "LiteRT-LM (On-device)"
    override val availableModels: List<String> =
        OnDeviceModelRegistry.liteRTOnly.map { it.id }

    /**
     * Resolves the model spec for the currently selected model.
     * Falls back to the recommended LiteRT model if the selection isn't a LiteRT model.
     */
    private fun resolveModelSpec(modelId: String): OnDeviceModelSpec {
        return OnDeviceModelRegistry.findById(modelId)
            ?.takeIf { it.backend == OnDeviceBackend.LITERT_LM }
            ?: OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM)
            ?: throw IllegalStateException("No LiteRT-LM models registered in OnDeviceModelRegistry")
    }

    /**
     * Returns the local file path where a model should be stored.
     */
    private fun getModelFilePath(spec: OnDeviceModelSpec): String {
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return File(modelsDir, "${spec.id}.litertlm").absolutePath
    }

    /**
     * Checks whether a given model file has been downloaded and is ready.
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val spec = OnDeviceModelRegistry.findById(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Returns the download status for all LiteRT-LM models.
     * Map of model ID → downloaded boolean.
     */
    fun getAllModelStatuses(): Map<String, Boolean> {
        return OnDeviceModelRegistry.liteRTOnly.associate { spec ->
            spec.id to isModelDownloaded(spec.id)
        }
    }

    /**
     * Initiates a model download. In production this would download the .litertlm file
     * from Hugging Face or a CDN. For now, this creates a placeholder that signals
     * the model location for the LiteRT-LM runtime.
     *
     * Returns a Flow that emits download progress (0..100) and completes.
     */
    fun downloadModel(modelId: String): Flow<Int> = flow {
        val spec = resolveModelSpec(modelId)
        val modelFile = File(getModelFilePath(spec))

        Log.i(TAG, "Starting download for model: ${spec.displayName} from ${spec.modelPath}")
        emit(0)

        withContext(Dispatchers.IO) {
            // In a production implementation this would use WorkManager + OkHttp
            // to download the .litertlm file from:
            // https://huggingface.co/${spec.modelPath}/resolve/main/model.litertlm
            //
            // For now we create a manifest file that the LiteRT-LM runtime
            // can use to locate the model once side-loaded or downloaded externally.
            val manifest = JSONObject().apply {
                put("model_id", spec.id)
                put("model_path", spec.modelPath)
                put("family", spec.family)
                put("size", spec.sizeLabel)
                put("format", "litertlm")
                put("status", "pending_download")
            }
            modelFile.parentFile?.mkdirs()
            modelFile.writeText(manifest.toString())
        }

        // Simulate progress milestones
        emit(50)
        emit(100)
        Log.i(TAG, "Model manifest created for: ${spec.displayName}")
    }.flowOn(Dispatchers.IO)

    /**
     * Deletes a downloaded model to free storage.
     */
    fun deleteModel(modelId: String): Boolean {
        val spec = OnDeviceModelRegistry.findById(modelId) ?: return false
        val modelFile = File(getModelFilePath(spec))
        return if (modelFile.exists()) modelFile.delete() else true
    }

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val startTime = System.currentTimeMillis()
        val config = settingsRepository.llmConfig.first()
        val spec = resolveModelSpec(config.activeModel)

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

                // ── LiteRT-LM inference call ────────────────────────────────
                // Uses the LiteRT-LM SDK: LlmInference.createFromOptions(...)
                // The actual runtime integration requires the litertlm-android
                // dependency. Below is the structured call pattern:
                //
                //   val options = LlmInferenceOptions.builder()
                //       .setModelPath(modelPath)
                //       .setMaxTokens(request.maxTokens)
                //       .setTemperature(request.temperature)
                //       .build()
                //   val engine = LlmInference.createFromOptions(context, options)
                //   val result = engine.generateResponse(prompt)
                //   engine.close()
                //
                // Until the runtime is available on the build host, we wrap
                // the call in a reflection-safe invoker so the rest of the
                // architecture compiles and the fallback chain works correctly.
                val outputText = invokeLiteRTInference(modelPath, prompt, request.maxTokens, request.temperature)

                LLMResponse(
                    content = outputText,
                    tokensUsed = outputText.length / 4,
                    model = spec.id,
                    provider = name,
                    latencyMs = System.currentTimeMillis() - startTime
                )
            } catch (e: Exception) {
                throw handleException(e, spec)
            }
        }
    }

    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val prompt = buildPrompt(
                request.systemPrompt,
                request.messages,
                request.tools?.map { ToolDefinition(it.name, it.description, it.parameters) }
                    ?: emptyList()
            )

            // ── LiteRT-LM streaming inference ──────────────────────────
            // In production:
            //   val options = LlmInferenceOptions.builder()
            //       .setModelPath(modelPath)
            //       .setMaxTokens(request.maxTokens)
            //       .setTemperature(request.temperature)
            //       .build()
            //   val engine = LlmInference.createFromOptions(context, options)
            //   engine.generateResponseAsync(prompt) { partial, done ->
            //       // emit partial tokens
            //   }
            //
            // For now, fall back to a single complete() call emitted as one chunk.
            val result = invokeLiteRTInference(modelPath, prompt, request.maxTokens, request.temperature)
            emit(result)
        } catch (e: Exception) {
            emit("Error (LiteRT-LM): ${e.localizedMessage}")
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        try {
            val config = settingsRepository.llmConfig.first()
            val spec = resolveModelSpec(config.activeModel)
            checkSdkCompatibility(spec)
            val modelPath = getModelFilePath(spec)
            checkModelReady(modelPath, spec)

            val systemPrompt = "You are an autonomous AI agent for Android."
            val prompt = buildPrompt(systemPrompt, messages, tools)

            val result = invokeLiteRTInference(modelPath, prompt, 2000, 0.7f)
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
            } catch (_: Exception) {
                // Not JSON — treat as plain text
            }
        } catch (e: Exception) {
            emit(StreamChunk.Content("Error (LiteRT-LM): ${e.localizedMessage}"))
        }
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // LiteRT-LM requires Android 12+ (API 31)
            if (Build.VERSION.SDK_INT < 31) return false
            // Check if at least one model is downloaded
            OnDeviceModelRegistry.liteRTOnly.any { spec ->
                isModelDownloaded(spec.id)
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

    /**
     * Verifies the model file exists and is valid.
     */
    private fun checkModelReady(modelPath: String, spec: OnDeviceModelSpec) {
        val file = File(modelPath)
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException(
                "Model \"${spec.displayName}\" has not been downloaded yet. " +
                "Please download it from Settings → On-Device AI."
            )
        }
    }

    /**
     * Attempts to invoke LiteRT-LM inference.
     *
     * This uses reflection to call the LiteRT-LM SDK so the project compiles
     * even if the SDK is not yet on the classpath. When the gradle dependency
     * `com.google.ai.edge.litertlm:litertlm-android` is available, this will
     * call through to the real engine.
     */
    private fun invokeLiteRTInference(
        modelPath: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        return try {
            // Try direct SDK call via reflection
            val optionsBuilderClass = Class.forName("com.google.ai.edge.litertlm.LlmInferenceOptions\$Builder")
            val builder = optionsBuilderClass.getDeclaredConstructor().newInstance()

            val setModelPath = optionsBuilderClass.getMethod("setModelPath", String::class.java)
            val setMaxTokens = optionsBuilderClass.getMethod("setMaxTokens", Int::class.javaPrimitiveType)
            val setTemperature = optionsBuilderClass.getMethod("setTemperature", Float::class.javaPrimitiveType)
            val buildMethod = optionsBuilderClass.getMethod("build")

            setModelPath.invoke(builder, modelPath)
            setMaxTokens.invoke(builder, maxTokens)
            setTemperature.invoke(builder, temperature)
            val options = buildMethod.invoke(builder)

            val inferenceClass = Class.forName("com.google.ai.edge.litertlm.LlmInference")
            val createMethod = inferenceClass.getMethod(
                "createFromOptions",
                Context::class.java,
                options!!::class.java.interfaces.firstOrNull() ?: options::class.java
            )
            val engine = createMethod.invoke(null, context, options)

            val generateMethod = inferenceClass.getMethod("generateResponse", String::class.java)
            val result = generateMethod.invoke(engine, prompt) as String

            val closeMethod = inferenceClass.getMethod("close")
            closeMethod.invoke(engine)

            result
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "LiteRT-LM SDK not found on classpath, returning error guidance")
            throw IOException(
                "LiteRT-LM runtime is not available. Please ensure the " +
                "litertlm-android dependency is included in your build.", e
            )
        } catch (e: Exception) {
            Log.e(TAG, "LiteRT-LM inference failed: ${e.message}", e)
            throw IOException("LiteRT-LM inference failed: ${e.localizedMessage}", e)
        }
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

    private fun handleException(e: Exception, spec: OnDeviceModelSpec): Exception {
        return when (e) {
            is IllegalStateException -> e
            is IOException -> e
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
