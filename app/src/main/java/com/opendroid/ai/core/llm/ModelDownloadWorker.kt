package com.opendroid.ai.core.llm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.db.dao.ModelDao
import com.opendroid.ai.data.db.entities.ModelStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads only registry-owned catalog artifacts. URL, destination, size, and checksum are
 * deliberately resolved here rather than trusted from WorkManager input data.
 */
class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val verifier = ModelArtifactVerifier()
    private val installer = ModelArtifactInstaller()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun modelDao(): ModelDao
        fun okHttpClient(): OkHttpClient
        fun providerCredentialStore(): ProviderCredentialStore
    }

    private val entryPoint = EntryPointAccessors.fromApplication(
        applicationContext,
        WorkerEntryPoint::class.java
    )

    private val modelDao = entryPoint.modelDao()
    private val okHttpClient = entryPoint.okHttpClient()
    private val providerCredentialStore = entryPoint.providerCredentialStore()

    override suspend fun doWork(): Result {
        val modelId = inputData.getString("model_id") ?: return Result.failure()
        val spec = OnDeviceModelRegistry.findById(modelId)
            ?: return fail(modelId, "Unknown model.")
        if (!spec.isManagedDownloadAvailable) {
            return fail(
                modelId,
                "In-app download is unavailable until publisher integrity metadata is recorded."
            )
        }

        try {
            OnDeviceModelRegistry.checkDeviceMemoryCompatibility(applicationContext, spec)
        } catch (error: IllegalStateException) {
            return fail(modelId, error.localizedMessage ?: "Insufficient device memory.")
        }

        return try {
            performDownload(modelId, spec)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            fail(modelId, "Could not download and verify this model.")
        }
    }

    private suspend fun performDownload(modelId: String, spec: OnDeviceModelSpec): Result {
        val artifact = spec.managedArtifact
            ?.takeIf { it.isComplete }
            ?: return fail(
                modelId,
                "In-app download is unavailable until publisher integrity metadata is recorded."
            )
        val expectedSize = artifact.expectedSize!!
        val downloadUrl = artifact.downloadUrl!!
        val temporaryFile = File(applicationContext.cacheDir, "$modelId.download")
        var retainTemporaryFileForResume = false

        try {
            if (temporaryFile.exists() && temporaryFile.length() > expectedSize) {
                temporaryFile.delete()
            }

            if (temporaryFile.length() < expectedSize) {
                val startBytes = temporaryFile.takeIf(File::exists)?.length() ?: 0L
                val response = executeRequest(downloadUrl, startBytes)
                    ?: return fail(modelId, "Internet connection unavailable.")

                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful && httpResponse.code != 206) {
                        return fail(modelId, httpFailureMessage(httpResponse.code))
                    }

                    val append = startBytes > 0L && httpResponse.code == 206
                    if (append && httpResponse.header("Content-Range")
                            ?.startsWith("bytes $startBytes-") != true
                    ) {
                        return fail(modelId, "Server returned an invalid download range.")
                    }

                    val body = httpResponse.body
                        ?: return fail(modelId, "Download response was empty.")
                    body.byteStream().use { input ->
                        FileOutputStream(temporaryFile, append).use { output ->
                            copyResponse(
                                input = input,
                                output = output,
                                initialBytes = if (append) startBytes else 0L,
                                expectedSize = expectedSize,
                                modelId = modelId
                            )
                            output.fd.sync()
                        }
                    }
                }
            }

            if (isStopped) {
                retainTemporaryFileForResume = true
                return Result.retry()
            }

            val downloadedPayload = verifier.verifyManagedPayload(temporaryFile, spec)
            if (downloadedPayload is ArtifactVerificationResult.Invalid) {
                return fail(modelId, verificationFailureMessage(downloadedPayload.failure))
            }

            val targetDir = modelDirectory(spec)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return fail(modelId, "Could not prepare model storage.")
            }
            val install = installer.installManagedDownload(
                source = temporaryFile,
                target = ModelStoragePaths.targetFile(targetDir, spec),
                manifestFile = ModelStoragePaths.manifestFile(targetDir),
                spec = spec,
                verifyFormat = ::verifyLiteRtCompatibility
            )
            if (!install.isSuccess) {
                return fail(modelId, verificationFailureMessage(install.failure))
            }

            val refFile = File(applicationContext.filesDir, "litert_models/$modelId.litertlm")
            val refDirectory = requireNotNull(refFile.parentFile)
            if (!refDirectory.exists() && !refDirectory.mkdirs()) {
                return fail(modelId, "Could not finalize model installation.")
            }
            refFile.writeText(targetDir.absolutePath)

            modelDao.updateDownloadProgressDetails(
                modelId,
                100,
                expectedSize,
                "",
                "",
                ModelStatus.READY
            )
            return Result.success()
        } finally {
            if (!retainTemporaryFileForResume) {
                temporaryFile.delete()
            }
        }
    }

    private fun executeRequest(downloadUrl: String, startBytes: Long) = try {
        val request = Request.Builder()
            .url(downloadUrl)
            .apply {
                val token = huggingFaceToken()
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
                if (startBytes > 0L) {
                    header("Range", "bytes=$startBytes-")
                }
            }
            .build()
        okHttpClient.newCall(request).execute()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun huggingFaceToken(): String? {
        providerCredentialStore.migrateLegacyCredentials()
        return when (
            val result = providerCredentialStore.read(ProviderCredentialId.HuggingFaceToken)
        ) {
            is CredentialStoreResult.Success -> result.value
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> null
        }
    }

    private suspend fun copyResponse(
        input: java.io.InputStream,
        output: FileOutputStream,
        initialBytes: Long,
        expectedSize: Long,
        modelId: String
    ) {
        modelDao.updateModelStatus(modelId, ModelStatus.DOWNLOADING)
        val buffer = ByteArray(64 * 1024)
        var totalRead = initialBytes
        var bytesSinceLastUpdate = 0L
        var lastUpdateAt = System.currentTimeMillis()

        while (true) {
            if (isStopped) return
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (totalRead + bytesRead > expectedSize) {
                throw IllegalStateException("Downloaded payload exceeded its published size")
            }

            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            bytesSinceLastUpdate += bytesRead

            val now = System.currentTimeMillis()
            if (now - lastUpdateAt >= 1_000L) {
                val speedBytesPerSecond = bytesSinceLastUpdate * 1_000L / (now - lastUpdateAt)
                val progress = ((totalRead * 100L) / expectedSize).toInt()
                val etaSeconds = if (speedBytesPerSecond > 0L) {
                    (expectedSize - totalRead) / speedBytesPerSecond
                } else {
                    0L
                }
                modelDao.updateDownloadProgressDetails(
                    modelId,
                    progress,
                    totalRead,
                    formatSpeed(speedBytesPerSecond),
                    formatEta(etaSeconds),
                    ModelStatus.DOWNLOADING
                )
                lastUpdateAt = now
                bytesSinceLastUpdate = 0L
            }
        }
    }

    private fun verifyLiteRtCompatibility(file: File) {
        val config = EngineConfig(
            modelPath = file.absolutePath,
            backend = Backend.CPU(),
            cacheDir = applicationContext.cacheDir.absolutePath
        )
        Engine(config).use { engine ->
            engine.initialize()
        }
    }

    private fun modelDirectory(spec: OnDeviceModelSpec): File {
        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        return ModelStoragePaths.modelDir(File(baseDir, "models"), spec.id)
    }

    private suspend fun fail(modelId: String, message: String): Result {
        modelDao.updateDownloadProgressDetails(
            modelId,
            0,
            0L,
            "",
            message,
            ModelStatus.FAILED
        )
        return Result.failure()
    }

    private fun httpFailureMessage(code: Int): String = when (code) {
        401 -> "The Hugging Face token is invalid."
        403 -> "You do not have permission to access this model. Accept its license on Hugging Face first."
        404 -> "Model artifact not found."
        else -> "Download request failed (HTTP $code)."
    }

    private fun verificationFailureMessage(failure: ArtifactVerificationFailure?): String = when (failure) {
        ArtifactVerificationFailure.METADATA_UNAVAILABLE ->
            "In-app download is unavailable until publisher integrity metadata is recorded."
        ArtifactVerificationFailure.SIZE_MISMATCH ->
            "Downloaded model size does not match the published artifact."
        ArtifactVerificationFailure.HASH_MISMATCH -> "Downloaded model failed its integrity check."
        ArtifactVerificationFailure.FORMAT_INVALID -> "Downloaded model is not compatible with LiteRT."
        else -> "Could not securely install the downloaded model."
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L ->
            String.format("%.1f MB/s", bytesPerSecond.toDouble() / (1024L * 1024L))
        bytesPerSecond >= 1024L -> String.format("%.1f KB/s", bytesPerSecond.toDouble() / 1024L)
        else -> "$bytesPerSecond B/s"
    }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3_600L -> String.format("%dh %dm", seconds / 3_600L, (seconds % 3_600L) / 60L)
        seconds >= 60L -> String.format("%dm %ds", seconds / 60L, seconds % 60L)
        else -> "${seconds}s"
    }
}
