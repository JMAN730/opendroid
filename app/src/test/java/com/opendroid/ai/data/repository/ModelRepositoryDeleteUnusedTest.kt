package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.opendroid.ai.core.llm.ModelDownloadTestApplication
import com.opendroid.ai.core.llm.ModelStoragePaths
import com.opendroid.ai.core.llm.OnDeviceEngineReleaser
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.ModelEntity
import com.opendroid.ai.data.db.entities.ModelStatus
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.withSelectedModel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModelDownloadTestApplication::class)
class ModelRepositoryDeleteUnusedTest {

    private lateinit var context: Context
    private lateinit var database: OpenDroidDatabase
    private lateinit var settings: SettingsRepository
    private val releasedDirs = mutableListOf<File>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (!WorkManager.isInitialized()) {
            // One serial task thread keeps a status query behind the cancel issued before it.
            WorkManager.initialize(
                context,
                Configuration.Builder().setTaskExecutor(Executors.newSingleThreadExecutor()).build()
            )
        }
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    Files.createTempDirectory("opendroid-model-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = EmptyProviderCredentialStore(),
            runStartupMigration = false
        )
    }

    @After
    fun tearDown() {
        database.close()
        modelsRoot().deleteRecursively()
    }

    @Test
    fun `the selected on-device model survives while a cloud provider is active`() = runBlocking {
        selectOnDeviceModelWithGeminiActive(QWEN)
        val qwenFile = installedModelFile(QWEN)
        val unusedFile = installedModelFile(UNUSED)

        repository().deleteUnusedModels()

        assertTrue("selected on-device model was deleted", qwenFile.exists())
        assertFalse("unselected model was kept", unusedFile.exists())
    }

    @Test
    fun `a selected custom import survives while a cloud provider is active`() = runBlocking {
        val custom = "custom-my-model-1a2b3c4d"
        val other = "custom-other-model-5e6f7a8b"
        selectOnDeviceModelWithGeminiActive(custom)
        listOf(custom, other).forEach { database.modelDao().insertModel(customEntity(it)) }
        val customFile = installedModelFile(custom)
        val otherFile = installedModelFile(other)

        repository().deleteUnusedModels()

        assertTrue("selected custom import was deleted", customFile.exists())
        assertFalse("unselected custom import was kept", otherFile.exists())
        assertEquals(null, database.modelDao().getModelById(other))
    }

    @Test
    fun `deleting unused models cancels their downloads`() = runBlocking {
        selectOnDeviceModelWithGeminiActive(QWEN)
        val workManager = WorkManager.getInstance(context)
        val pending = OneTimeWorkRequestBuilder<IdleWorker>()
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniqueWork("download_$UNUSED", ExistingWorkPolicy.REPLACE, pending).result.get()

        repository().deleteUnusedModels()

        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfoById(pending.id).get()?.state)
    }

    @Test
    fun `deleting a model closes an engine loaded from it and leaves a kept one alone`() = runBlocking {
        selectOnDeviceModelWithGeminiActive(QWEN)

        repository().deleteUnusedModels()

        assertTrue(releasedDirs.contains(modelDir(UNUSED)))
        assertFalse(releasedDirs.contains(modelDir(QWEN)))
    }

    private suspend fun selectOnDeviceModelWithGeminiActive(modelId: String) {
        settings.updateConfig {
            LLMConfig(activeProvider = "Google Gemini")
                .withSelectedModel(ProviderCatalog.ON_DEVICE, modelId)
        }
    }

    private fun repository() = ModelRepository(
        context,
        database.modelDao(),
        settings,
        OnDeviceEngineReleaser { dir -> synchronized(releasedDirs) { releasedDirs += dir } }
    )

    private fun modelsRoot() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

    private fun modelDir(modelId: String) = ModelStoragePaths.modelDir(modelsRoot(), modelId)

    private fun installedModelFile(modelId: String): File =
        File(modelDir(modelId).apply { mkdirs() }, "model.litertlm").apply { writeText("weights") }

    private fun customEntity(id: String) = ModelEntity(
        id = id,
        name = id,
        version = "1",
        size = 7L,
        downloadUrl = "",
        localPath = modelDir(id).absolutePath,
        status = ModelStatus.READY,
        downloadProgress = 100,
        lastUsed = 0L,
        installedAt = 1L,
        downloadedSize = 7L
    )

    class IdleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    private class EmptyProviderCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }

    private companion object {
        const val QWEN = "qwen-2.5-0.5b-it-litert"
        const val UNUSED = "gemma-4-e2b-it-litert"
    }
}
