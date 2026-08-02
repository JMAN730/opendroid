package com.opendroid.ai.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.LLMConfig
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
    fun `unavailable credential storage never uses persisted DataStore secrets as a fallback`() = runBlocking {
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

        repository.updateConfig { it }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(plaintextSecret))
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

        repository.updateConfig { it }

        val persistedJson = dataStore.data.first()[LLM_CONFIG_KEY].orEmpty()
        assertFalse(persistedJson.contains(plaintextSecret))
        assertTrue(credentials.values.isEmpty())
        assertTrue(repository.llmConfig.first().apiKeys.isEmpty())
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
        recoveryRequired: Boolean = false
    ) : ProviderCredentialStore {
        val values = mutableMapOf<ProviderCredentialId, String>()
        private val mutableRecoveryState = MutableStateFlow<ProviderCredentialRecoveryState>(
            if (recoveryRequired) {
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
            } else {
                ProviderCredentialRecoveryState.Ready
            }
        )

        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> = mutableRecoveryState

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            unavailableResult() ?: CredentialStoreResult.Success(values[credential])

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            unavailableResult() ?: CredentialStoreResult.Success(
                values.filterKeys { it is ProviderCredentialId.ApiKey }
                    .mapKeys { (credential, _) -> (credential as ProviderCredentialId.ApiKey).providerName }
            )

        override fun write(
            credential: ProviderCredentialId,
            value: String
        ): CredentialStoreResult<Unit> {
            unavailableResult<Unit>()?.let { return it }
            if (value.isBlank()) {
                values.remove(credential)
            } else {
                values[credential] = value
            }
            return CredentialStoreResult.Success(Unit)
        }

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> {
            unavailableResult<Unit>()?.let { return it }
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
    }

    private companion object {
        val LLM_CONFIG_KEY = stringPreferencesKey("llm_config")
    }
}
