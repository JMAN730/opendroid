package com.opendroid.ai.data.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.opendroid.ai.core.llm.*
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    private val context: Context,
    private val modelDao: ModelDao,
    private val settingsRepository: SettingsRepository
) : ModelManager {

    private val tag = "ModelRepository"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val workManager = WorkManager.getInstance(context)

    // Coordinate initialization to ensure it runs exactly once
    private val initMutex = Mutex()
    private var isInitialized = false

    init {
        scope.launch {
            initModelsInDatabase()
        }
    }

    val allModelsFlow: Flow<List<ModelEntity>> = modelDao.getAllModels()
        .onStart { initModelsInDatabase() }

    private fun getModelsDirectory(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val modelsDir = File(baseDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return modelsDir
    }

    private fun getModelDir(modelId: String): File =
        ModelStoragePaths.modelDir(getModelsDirectory(), modelId)

    private suspend fun initModelsInDatabase() {
        initMutex.withLock {
            if (isInitialized) return@withLock
            isInitialized = true
        }

        val registeredModels = OnDeviceModelRegistry.liteRTOnly
        registeredModels.forEach { spec ->
            val existing = modelDao.getModelById(spec.id)
            val dir = getModelDir(spec.id)
            val manifestFile = File(dir, "manifest.json")

            // Delete unverified placeholders (no manifest) under the min size gate
            val candidate = ModelStoragePaths.resolveExistingFile(dir, spec)
                ?: ModelStoragePaths.targetFile(dir, spec)
            if (candidate.exists() && !manifestFile.exists() && candidate.length() < ModelStoragePaths.MIN_VERIFIED_BYTES) {
                Log.w(tag, "Deleting unverified placeholder model file: ${candidate.absolutePath} (size: ${candidate.length()} bytes, no manifest)")
                try {
                    candidate.delete()
                } catch (e: Exception) {
                    Log.e(tag, "Failed to delete placeholder model file", e)
                }
            }

            val hasFiles = dir.exists() && ModelStoragePaths.hasVerifiedModel(dir, spec)

            val currentStatus = when {
                hasFiles -> ModelStatus.READY
                existing != null && (existing.status == ModelStatus.DOWNLOADING || existing.status == ModelStatus.PAUSED) -> existing.status
                else -> ModelStatus.NOT_DOWNLOADED
            }

            val currentProgress = when {
                hasFiles -> 100
                existing != null && (existing.status == ModelStatus.DOWNLOADING || existing.status == ModelStatus.PAUSED) -> existing.downloadProgress
                else -> 0
            }
 
            val entity = ModelEntity(
                id = spec.id,
                name = spec.displayName,
                version = spec.version,
                size = spec.expectedSize,
                downloadUrl = getModelDownloadUrl(spec),
                localPath = dir.absolutePath,
                status = currentStatus,
                downloadProgress = currentProgress,
                lastUsed = existing?.lastUsed ?: 0L,
                installedAt = existing?.installedAt ?: (if (hasFiles) System.currentTimeMillis() else 0L),
                downloadedSize = existing?.downloadedSize ?: (if (hasFiles) spec.expectedSize else 0L)
            )
 
            modelDao.insertModel(entity)
        }
    }

    private fun getModelDownloadUrl(spec: OnDeviceModelSpec): String {
        return "https://huggingface.co/${spec.modelPath}/resolve/main/${spec.modelFilename}"
    }

    override suspend fun download(model: OnDeviceModel) {
        startDownload(model, simulate = false)
    }

    suspend fun startDownload(model: OnDeviceModel, simulate: Boolean) {
        val entity = modelDao.getModelById(model.id) ?: return
        
        val inputData = Data.Builder()
            .putString("model_id", model.id)
            .putString("download_url", entity.downloadUrl)
            .putString("target_path", entity.localPath)
            .putLong("size", entity.size)
            .putString("sha256", model.sha256)
            .putBoolean("simulate", simulate)
            .build()

        val downloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag("download_${model.id}")
            .build()

        workManager.enqueueUniqueWork(
            "download_${model.id}",
            ExistingWorkPolicy.REPLACE,
            downloadRequest
        )
    }

    suspend fun importLocalModel(modelId: String, uri: android.net.Uri): ImportLocalModelResult =
        withContext(Dispatchers.IO) {
            val spec = OnDeviceModelRegistry.findById(modelId)
                ?: return@withContext ImportLocalModelResult.Failure("Unknown model id: $modelId")

            try {
                OnDeviceModelRegistry.checkDeviceMemoryCompatibility(context, spec)
            } catch (e: IllegalStateException) {
                val reason = e.localizedMessage ?: "Insufficient device memory."
                Log.e(tag, "RAM check failed for import: ${e.message}")
                modelDao.updateDownloadProgressDetails(
                    modelId,
                    0,
                    0L,
                    "",
                    reason,
                    ModelStatus.FAILED
                )
                return@withContext ImportLocalModelResult.Failure(reason)
            }

            val dir = getModelDir(modelId)
            if (!dir.exists()) dir.mkdirs()
            val targetFile = ModelStoragePaths.targetFile(dir, spec)

            try {
                val input = context.contentResolver.openInputStream(uri)
                if (input == null) {
                    return@withContext ImportLocalModelResult.Failure(
                        "Could not open the selected file. Try another file manager or copy the model to Downloads first."
                    )
                }
                input.use { stream ->
                    targetFile.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                }

                val finalSize = targetFile.length()
                if (finalSize < ModelStoragePaths.MIN_VERIFIED_BYTES) {
                    Log.e(tag, "Imported file size is too small: $finalSize bytes")
                    targetFile.delete()
                    return@withContext ImportLocalModelResult.Failure(
                        "Imported file is too small (${finalSize} bytes). Expected a LiteRT model (.litertlm or .task) larger than 10 MB."
                    )
                }

                Log.i(tag, "Verifying LiteRT compatibility of imported model at ${targetFile.absolutePath}...")
                try {
                    val config = EngineConfig(
                        modelPath = targetFile.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.absolutePath
                    )
                    Engine(config).use { engine ->
                        engine.initialize()
                    }
                    Log.i(tag, "LiteRT compatibility verified successfully.")
                } catch (e: Throwable) {
                    Log.e(tag, "Imported file is not LiteRT compatible: ${e.message}", e)
                    targetFile.delete()
                    val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    return@withContext ImportLocalModelResult.Failure(
                        "LiteRT could not open this file ($detail). Ensure it is a valid .litertlm or .task model."
                    )
                }

                val manifestFile = File(dir, "manifest.json")
                val manifest = org.json.JSONObject().apply {
                    put("model_id", modelId)
                    put("status", "ready")
                    put("format", "litertlm")
                    put("filename", targetFile.name)
                }
                manifestFile.writeText(manifest.toString())

                val refFile = File(context.filesDir, "litert_models/${modelId}.litertlm")
                refFile.parentFile?.mkdirs()
                refFile.writeText(dir.absolutePath)

                modelDao.updateDownloadProgressDetails(
                    modelId,
                    100,
                    finalSize,
                    "",
                    "",
                    ModelStatus.READY
                )

                ImportLocalModelResult.Success
            } catch (e: Exception) {
                Log.e(tag, "Failed to import local model", e)
                if (targetFile.exists()) targetFile.delete()
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                ImportLocalModelResult.Failure("Import failed: $detail")
            }
        }

    suspend fun pauseDownload(model: OnDeviceModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        modelDao.updateModelStatus(model.id, ModelStatus.PAUSED)
    }

    suspend fun cancelDownload(model: OnDeviceModel) {
        workManager.cancelUniqueWork("download_${model.id}")
        val dir = getModelDir(model.id)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val tempFile = File(context.cacheDir, "${model.id}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        
        val refFile = File(context.filesDir, "litert_models/${model.id}.litertlm")
        if (refFile.exists()) {
            refFile.delete()
        }

        modelDao.updateDownloadProgressDetails(
            model.id,
            0,
            0L,
            "",
            "",
            ModelStatus.NOT_DOWNLOADED
        )
    }

    suspend fun resumeDownload(model: OnDeviceModel) {
        val entity = modelDao.getModelById(model.id) ?: return
        startDownload(model, simulate = false)
    }

    override suspend fun delete(model: OnDeviceModel) {
        cancelDownload(model)
        modelDao.updateModelStatus(model.id, ModelStatus.NOT_DOWNLOADED)
    }

    override suspend fun load(model: OnDeviceModel) {
        modelDao.updateModelStatus(model.id, ModelStatus.LOADING)
        
        // Simulate loading process
        kotlinx.coroutines.delay(1000)
        
        modelDao.updateModelStatus(model.id, ModelStatus.READY)
        modelDao.updateLastUsed(model.id, System.currentTimeMillis())
        
        settingsRepository.updateConfig { current ->
            current.copy(activeModel = model.id)
        }
    }

    override suspend fun isDownloaded(model: OnDeviceModel): Boolean {
        val spec = OnDeviceModelRegistry.findById(model.id) ?: return false
        val dir = getModelDir(model.id)
        return dir.exists() && ModelStoragePaths.resolveExistingFile(dir, spec) != null
    }

    override suspend fun currentModel(): OnDeviceModel? {
        val config = settingsRepository.llmConfig.first()
        return OnDeviceModelRegistry.findById(config.activeModel)
    }

    // ── Storage Management ──
    
    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedByAppBytes: Long
    )

    fun getStorageInfoFlow(): Flow<StorageInfo> = flow {
        while (true) {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = totalBlocks * blockSize
            val free = availableBlocks * blockSize
            val usedByApp = getFolderSize(getModelsDirectory())

            emit(StorageInfo(total, free, usedByApp))
            kotlinx.coroutines.delay(5000)
        }
    }.flowOn(Dispatchers.IO)

    private fun getFolderSize(file: File): Long {
        if (file.isDirectory) {
            var size = 0L
            val children = file.listFiles() ?: return 0L
            for (child in children) {
                size += getFolderSize(child)
            }
            return size
        }
        return file.length()
    }

    suspend fun deleteUnusedModels() {
        val config = settingsRepository.llmConfig.first()
        val activeModelId = config.activeModel
        
        OnDeviceModelRegistry.liteRTOnly.forEach { spec ->
            if (spec.id != activeModelId) {
                val dir = getModelDir(spec.id)
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
                
                val refFile = File(context.filesDir, "litert_models/${spec.id}.litertlm")
                if (refFile.exists()) {
                    refFile.delete()
                }

                modelDao.updateDownloadProgressDetails(
                    spec.id,
                    0,
                    0L,
                    "",
                    "",
                    ModelStatus.NOT_DOWNLOADED
                )
            }
        }
    }
}
