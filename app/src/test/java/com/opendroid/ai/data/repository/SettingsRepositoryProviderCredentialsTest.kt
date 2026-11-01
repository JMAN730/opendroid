package com.opendroid.ai.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.resolvedAutoMode
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryProviderCredentialsTest {

    @Test
    fun `legacy LLMConfig credentials are migrated then stripped from the DataStore JSON`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore()
        val legacyProviderSecret = "sk-legacy-provider-secret"
        val legacyElevenLabsSecret = "elevenlabs-legacy-secret"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(
                    apiKeys = mapOf("OpenAI" to legacyProviderSecret),
                    elevenLabsApiKey = legacyElevenLabsSecret,
                    elevenLabsVoiceId = "voice-id"
                )
            )
        }
        val repository = SettingsRepository(
            dataStore = dataStore,
            providerCredentialStore = credentials,
            runStartupMigration = false
        )

        repository.updateConfig { it }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(legacyProviderSecret))
        assertFalse(persistedJson.contains(legacyElevenLabsSecret))
        val persisted = Json.decodeFromString<LLMConfig>(persistedJson)
        assertTrue(persisted.apiKeys.isEmpty())
        assertEquals("", persisted.elevenLabsApiKey)
        assertEquals("voice-id", persisted.elevenLabsVoiceId)

        assertEquals(legacyProviderSecret, credentials.values[ProviderCredentialId.ApiKey("OpenAI")])
        assertEquals(legacyElevenLabsSecret, credentials.values[ProviderCredentialId.ElevenLabsApiKey])

        val hydrated = repository.llmConfig.first()
        assertEquals(legacyProviderSecret, hydrated.apiKeys["OpenAI"])
        assertEquals(legacyElevenLabsSecret, hydrated.elevenLabsApiKey)
    }

    @Test
    fun `unavailable credential storage retains prior DataStore source but never exposes it as a fallback`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(unavailable = true)
        val plaintextSecret = "sk-must-not-survive-keystore-failure"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(
                    apiKeys = mapOf("OpenAI" to plaintextSecret),
                    elevenLabsApiKey = "elevenlabs-must-not-survive"
                )
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        // An update that changes no credential is saved (here as a no-op) even while
        // the direct store is unreadable; only the credential migration is deferred.
        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.updateConfig { it }
        )

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertTrue(persistedJson.contains(plaintextSecret))
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
        assertEquals("", repository.llmConfig.first().elevenLabsApiKey)
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            repository.providerCredentialRecoveryState.value
        )
    }

    @Test
    fun `reentry state blocks plaintext DataStore migration even when another direct credential is readable`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(recoveryRequired = true)
        val plaintextSecret = "sk-not-a-recovery-source"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(apiKeys = mapOf("OpenAI" to plaintextSecret))
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        // An update that changes no credential is saved (here as a no-op) even while
        // the direct store is unreadable; only the credential migration is deferred.
        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.updateConfig { it }
        )

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertTrue(persistedJson.contains(plaintextSecret))
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
    }

    @Test
    fun `storage unavailable keeps the prior DataStore credential source unchanged`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(failMutationAt = 1)
        val previousSecret = "sk-existing-source-must-not-be-lost"
        val previousJson = Json.encodeToString(
            LLMConfig(apiKeys = mapOf("OpenAI" to previousSecret), elevenLabsVoiceId = "voice-id")
        )
        dataStore.edit { preferences -> preferences[LLM_CONFIG_KEY] = previousJson }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        // The failed write belongs to the implicit legacy migration, not to this update, so the
        // update itself is reported as saved and the plaintext source stays for the next retry.
        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.updateConfig { it }
        )

        assertEquals(previousJson, dataStore.data.first()[LLM_CONFIG_KEY])
        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.providerCredentialPersistenceState.value
        )
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
    }

    @Test
    fun `partial direct-store failure rolls back prior mutations and leaves DataStore unchanged`() = runBlocking {
        val dataStore = newDataStore()
        val openAi = ProviderCredentialId.ApiKey("OpenAI")
        val credentials = InMemoryProviderCredentialStore(
            initialValues = mapOf(openAi to "old-openai-secret"),
            failMutationAt = 2
        )
        val previousJson = Json.encodeToString(LLMConfig(elevenLabsVoiceId = "voice-id"))
        dataStore.edit { preferences -> preferences[LLM_CONFIG_KEY] = previousJson }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.StorageUnavailable,
            repository.updateConfig {
                it.copy(
                    apiKeys = mapOf("OpenAI" to "new-openai-secret"),
                    elevenLabsApiKey = "new-elevenlabs-secret"
                )
            }
        )

        assertEquals(previousJson, dataStore.data.first()[LLM_CONFIG_KEY])
        assertEquals("old-openai-secret", credentials.values[openAi])
        assertFalse(credentials.values.containsKey(ProviderCredentialId.ElevenLabsApiKey))
        assertTrue(repository.llmConfig.first().apiKeys["OpenAI"] == "old-openai-secret")
    }

    @Test
    fun `non-credential settings are saved while credentials must be re-entered`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(recoveryRequired = true)
        val plaintextSecret = "sk-migration-input-must-survive"
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(
                LLMConfig(
                    apiKeys = mapOf("OpenAI" to plaintextSecret),
                    autoMode = AutoMode.YOLO,
                    autoConfirmPlans = true
                )
            )
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.updateConfig { it.copy(autoMode = AutoMode.OFF, autoConfirmPlans = false) }
        )

        val persisted = Json.decodeFromString<LLMConfig>(dataStore.data.first()[LLM_CONFIG_KEY].orEmpty())
        assertEquals(AutoMode.OFF, persisted.autoMode)
        assertFalse(persisted.autoConfirmPlans)
        assertEquals(AutoMode.OFF, repository.llmConfig.first().resolvedAutoMode())
        // The legacy plaintext stays as migration input, and is still never exposed or migrated.
        assertEquals(plaintextSecret, persisted.apiKeys["OpenAI"])
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
        assertTrue(credentials.values.isEmpty())
    }

    @Test
    fun `credential changes are still aborted while credentials must be re-entered`() = runBlocking {
        val dataStore = newDataStore()
        val credentials = InMemoryProviderCredentialStore(recoveryRequired = true)
        val previousJson = Json.encodeToString(LLMConfig(isDarkMode = true))
        dataStore.edit { preferences -> preferences[LLM_CONFIG_KEY] = previousJson }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.CredentialsMustBeReentered,
            repository.updateConfig {
                it.copy(apiKeys = mapOf("OpenAI" to "sk-new-secret"), isDarkMode = false)
            }
        )

        assertEquals(previousJson, dataStore.data.first()[LLM_CONFIG_KEY])
        assertTrue(credentials.values.isEmpty())
        assertEquals(
            ProviderCredentialPersistenceState.CredentialsMustBeReentered,
            repository.providerCredentialPersistenceState.value
        )
    }

    @Test
    fun `transient read failure before the merge does not tombstone stored keys on a non-credential update`() = runBlocking {
        val dataStore = newDataStore()
        val openAi = ProviderCredentialId.ApiKey("OpenAI")
        val credentials = InMemoryProviderCredentialStore(
            initialValues = mapOf(
                openAi to "stored-openai-secret",
                ProviderCredentialId.ElevenLabsApiKey to "stored-elevenlabs-secret"
            ),
            storageUnavailableReads = 1
        )
        dataStore.edit { preferences ->
            preferences[LLM_CONFIG_KEY] = Json.encodeToString(LLMConfig(isDarkMode = true))
        }
        val repository = SettingsRepository(dataStore, credentials, runStartupMigration = false)

        assertEquals(
            ProviderCredentialPersistenceState.Ready,
            repository.updateConfig { it.copy(isDarkMode = false) }
        )

        assertEquals("stored-openai-secret", credentials.values[openAi])
        assertEquals("stored-elevenlabs-secret", credentials.values[ProviderCredentialId.ElevenLabsApiKey])
        val persisted = Json.decodeFromString<LLMConfig>(dataStore.data.first()[LLM_CONFIG_KEY].orEmpty())
        assertFalse(persisted.isDarkMode)
        assertTrue(persisted.apiKeys.isEmpty())
        assertEquals("", persisted.elevenLabsApiKey)
        val hydrated = repository.llmConfig.first()
        assertEquals("stored-openai-secret", hydrated.apiKeys["OpenAI"])
        assertEquals("stored-elevenlabs-secret", hydrated.elevenLabsApiKey)
    }

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        produceFile = {
            Files.createTempDirectory("opendroid-settings-test")
                .resolve("settings.preferences_pb")
                .toFile()
        }
    )

    private class InMemoryProviderCredentialStore(
        private val unavailable: Boolean = false,
        recoveryRequired: Boolean = false,
        initialValues: Map<ProviderCredentialId, String> = emptyMap(),
        private val failMutationAt: Int? = null,
        /** The first N reads fail with [CredentialStoreResult.StorageUnavailable], then recover. */
        private val storageUnavailableReads: Int = 0
    ) : ProviderCredentialStore {
        val values = initialValues.toMutableMap()
        private var mutationCount = 0
        private var readCount = 0
        private val mutableRecoveryState = MutableStateFlow<ProviderCredentialRecoveryState>(
            if (recoveryRequired) {
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
            } else {
                ProviderCredentialRecoveryState.Ready
            }
        )

        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> = mutableRecoveryState

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            readFailureResult() ?: CredentialStoreResult.Success(values[credential])

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            readFailureResult() ?: CredentialStoreResult.Success(
                values.filterKeys { it is ProviderCredentialId.ApiKey }
                    .mapKeys { (credential, _) -> (credential as ProviderCredentialId.ApiKey).providerName }
            )

        override fun write(
            credential: ProviderCredentialId,
            value: String
        ): CredentialStoreResult<Unit> {
            mutationFailureResult()?.let { return it }
            if (value.isBlank()) {
                values.remove(credential)
            } else {
                values[credential] = value
            }
            return CredentialStoreResult.Success(Unit)
        }

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> {
            mutationFailureResult()?.let { return it }
            values.remove(credential)
            return CredentialStoreResult.Success(Unit)
        }

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            unavailableResult<Unit>() ?: CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> {
            values.clear()
            return CredentialStoreResult.Success(Unit)
        }

        private fun <T> unavailableResult(): CredentialStoreResult<T>? {
            if (!unavailable) return null
            mutableRecoveryState.value = ProviderCredentialRecoveryState.CredentialsMustBeReentered
            return CredentialStoreResult.CredentialsMustBeReentered
        }

        private fun <T> readFailureResult(): CredentialStoreResult<T>? {
            unavailableResult<T>()?.let { return it }
            readCount += 1
            if (readCount <= storageUnavailableReads) return CredentialStoreResult.StorageUnavailable
            return null
        }

        private fun mutationFailureResult(): CredentialStoreResult<Unit>? {
            unavailableResult<Unit>()?.let { return it }
            mutationCount += 1
            if (mutationCount == failMutationAt) return CredentialStoreResult.StorageUnavailable
            return null
        }
    }

    private companion object {
        val LLM_CONFIG_KEY = stringPreferencesKey("llm_config")
    }
}
