package com.opendroid.ai.data.repository

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
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
    private val artifactManifestStore = ModelArtifactManifestStore()
    private val artifactVerifier = ModelArtifactVerifier()
    private val artifactInstaller = ModelArtifactInstaller()

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
            val manifestFile = ModelStoragePaths.manifestFile(dir)
            val candidate = ModelStoragePaths.resolveExistingFile(dir, spec)
            val hasFiles = candidate?.let { file ->
                when (artifactVerifier.verifyForStartup(file, manifestFile, spec)) {
                    ArtifactVerificationResult.Valid -> true
                    is ArtifactVerificationResult.Invalid -> {
                        // Pre-manifest catalog installs are repaired only after a full hash check
                        // against registry-owned metadata. Unknown/local legacy files fail closed.
                        val repairedManifest = artifactVerifier
                            .manifestForVerifiedLegacyCatalogFile(file, spec)
                        if (repairedManifest == null) {
                            false
                        } else {
                            runCatching {
                                artifactManifestStore.writeAtomically(manifestFile, repairedManifest)
                            }.isSuccess
                        }
                    }
                }
            } ?: false

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
                downloadedSize = existing?.downloadedSize
                    ?: (if (hasFiles) candidate.length() else 0L)
            )
 
            modelDao.insertModel(entity)
        }
    }

    private fun getModelDownloadUrl(spec: OnDeviceModelSpec): String {
        return spec.managedArtifact
            ?.takeIf { it.isComplete }
            ?.downloadUrl
            .orEmpty()
    }

    override suspend fun download(model: OnDeviceModel) {
        startDownload(model)
    }

    suspend fun startDownload(model: OnDeviceModel) {
        val spec = OnDeviceModelRegistry.findById(model.id) ?: return
        modelDao.getModelById(spec.id) ?: return
        if (!spec.isManagedDownloadAvailable) {
            modelDao.updateDownloadProgressDetails(
                spec.id,
                0,
                0L,
                "",
                "In-app download is unavailable until publisher integrity metadata is recorded.",
                ModelStatus.FAILED
            )
            return
        }
        
        val inputData = Data.Builder()
            // The worker resolves URL, path, byte size, and checksum from the immutable registry.
            // WorkManager input is transport data, not an integrity trust boundary.
            .putString("model_id", spec.id)
            .build()

        val downloadRequest = ModelDownloadWorkRequest.create(inputData, model.id)

        workManager.enqueueUniqueWork(
            "download_${spec.id}",
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
                if (!dir.exists() && !dir.mkdirs()) {
                    return@withContext ImportLocalModelResult.Failure(
                        "Could not prepare private model storage. Free space and try again."
                    )
                }
                val targetFile = ModelStoragePaths.targetFile(dir, spec)
                val manifestFile = ModelStoragePaths.manifestFile(dir)
                val temporaryImport = File.createTempFile(".model-import-", ".tmp", context.cacheDir)

                try {
                    val input = context.contentResolver.openInputStream(uri)
                    if (input == null) {
                    return@withContext ImportLocalModelResult.Failure(
                        "Could not open the selected file. Try another file manager or copy the model to Downloads first."
                    )
                    }
                    input.use { stream ->
                        temporaryImport.outputStream().use { output ->
                            stream.copyTo(output)
                        }
                    }

                val finalSize = temporaryImport.length()
                if (finalSize < ModelStoragePaths.MIN_LOCAL_IMPORT_BYTES) {
                    return@withContext ImportLocalModelResult.Failure(
                        "Imported file is too small (${finalSize} bytes). Expected a LiteRT model (.litertlm or .task) larger than 10 MB."
                    )
                }

                val install = artifactInstaller.installLocalImport(
                    source = temporaryImport,
                    target = targetFile,
                    manifestFile = manifestFile,
                    spec = spec,
                    verifyFormat = ::verifyLiteRtCompatibility
                )
                if (!install.isSuccess) {
                    if (install.failure == ArtifactVerificationFailure.FORMAT_INVALID) {
                        return@withContext ImportLocalModelResult.Failure(
                            "LiteRT could not open this file. Ensure it is a valid .litertlm or .task model."
                        )
                    }
                    return@withContext ImportLocalModelResult.Failure(
                        "Could not securely install the selected model. The existing installed model was left unchanged."
                    )
                }

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
                Log.e(tag, "Failed to import local model")
                ImportLocalModelResult.Failure("Import failed. The existing installed model was left unchanged.")
            } finally {
                temporaryImport.delete()
            }
        }

    /** Runs the only structural validation permitted for explicitly untrusted local imports. */
    private fun verifyLiteRtCompatibility(file: File) {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.absolutePath
        )
        Engine(config).use { engine ->
            engine.initialize()
        }
    }

    suspend fun pauseDownload(model: OnDeviceModel) {
        if (OnDeviceModelRegistry.findById(model.id) == null) return
        modelDao.updateModelStatus(model.id, ModelStatus.PAUSED)
        workManager.cancelUniqueWork("download_${model.id}")
    }

    suspend fun cancelDownload(model: OnDeviceModel) {
        if (OnDeviceModelRegistry.findById(model.id) == null) return
        // Mark cancellation before stopping work so a stopping worker cannot restore PAUSED.
        modelDao.updateDownloadProgressDetails(
            model.id,
            0,
            0L,
            "",
            "",
            ModelStatus.NOT_DOWNLOADED
        )
        workManager.cancelUniqueWork("download_${model.id}")
        val dir = getModelDir(model.id)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        listOf(
            File(context.cacheDir, "${model.id}.download"),
            File(context.cacheDir, "${model.id}.tmp")
        ).forEach { temporaryFile ->
            if (temporaryFile.exists()) {
                temporaryFile.delete()
            }
        }
        
        val refFile = File(context.filesDir, "litert_models/${model.id}.litertlm")
        if (refFile.exists()) {
            refFile.delete()
        }

    }

    suspend fun resumeDownload(model: OnDeviceModel) {
        if (modelDao.getModelById(model.id) == null) return
        startDownload(model)
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
        val file = ModelStoragePaths.resolveExistingFile(dir, spec) ?: return false
        return artifactVerifier.verifyForStartup(
            file,
            ModelStoragePaths.manifestFile(dir),
            spec
        ) == ArtifactVerificationResult.Valid
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
