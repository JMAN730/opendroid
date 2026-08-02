package com.opendroid.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val providerCredentialStore: ProviderCredentialStore,
    private val runStartupMigration: Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        providerCredentialStore: ProviderCredentialStore
    ) : this(context.dataStore, providerCredentialStore, runStartupMigration = true)

    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")

    /** A UI-safe recovery signal; it never contains credential or ciphertext data. */
    val providerCredentialRecoveryState = providerCredentialStore.recoveryState

    init {
        if (runStartupMigration) {
            // Legacy EncryptedSharedPreferences credentials are imported before DataStore
            // secrets are stripped. If either store is unavailable, updateConfig still strips
            // plaintext rather than using it as a recovery fallback.
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    providerCredentialStore.migrateLegacyCredentials()
                    updateConfig { it }
                } catch (_: Exception) {
                    // The credential store has no plaintext fallback; a later update retries.
                }
            }
        }
    }

    /**
     * Reads only authenticated direct-store values. Persisted JSON credentials are migration
     * input, never a runtime credential fallback.
     */
    private fun mergeSecretsForRead(persisted: LLMConfig): LLMConfig {
        val snapshot = readCredentialSnapshot()
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey.orEmpty()
        )
    }

    /**
     * Supplies legacy DataStore values to a write only when direct credential reads are healthy.
     * This gives the one-time DataStore migration a source without exposing it to callers.
     */
    private fun mergeSecretsForUpdate(persisted: LLMConfig): LLMConfig {
        val snapshot = readCredentialSnapshot()
            ?: return persisted.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        return persisted.copy(
            apiKeys = persisted.apiKeys + snapshot.providerApiKeys,
            elevenLabsApiKey = snapshot.elevenLabsApiKey ?: persisted.elevenLabsApiKey
        )
    }

    /** Write direct credentials first and always return a plaintext-free DataStore configuration. */
    private fun storeSecretsAndStrip(config: LLMConfig): LLMConfig {
        if (providerCredentialStore.recoveryState.value ==
            ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return config.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
        }
        val storedProviderNames = when (val result = providerCredentialStore.readProviderApiKeys()) {
            is CredentialStoreResult.Success -> result.value.keys
            CredentialStoreResult.CredentialsMustBeReentered,
            CredentialStoreResult.StorageUnavailable -> emptySet()
        }
        storedProviderNames
            .filterNot(config.apiKeys::containsKey)
            .forEach { providerCredentialStore.remove(ProviderCredentialId.ApiKey(it)) }
        config.apiKeys.forEach { (provider, key) ->
            // A corrupt legacy JSON key must not prevent the JSON from being stripped.
            val credential = runCatching { ProviderCredentialId.ApiKey(provider) }.getOrNull()
                ?: return@forEach
            if (key.isBlank()) {
                providerCredentialStore.remove(credential)
            } else {
                providerCredentialStore.write(credential, key)
            }
        }
        if (config.elevenLabsApiKey.isBlank()) {
            providerCredentialStore.remove(ProviderCredentialId.ElevenLabsApiKey)
        } else {
            providerCredentialStore.write(
                ProviderCredentialId.ElevenLabsApiKey,
                config.elevenLabsApiKey
            )
        }
        return config.copy(apiKeys = emptyMap(), elevenLabsApiKey = "")
    }

    private fun readCredentialSnapshot(): CredentialSnapshot? {
        if (providerCredentialStore.recoveryState.value ==
            ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return null
        }
        val providerApiKeys = providerCredentialStore.readProviderApiKeys()
        val elevenLabsApiKey = providerCredentialStore.read(ProviderCredentialId.ElevenLabsApiKey)
        if (providerApiKeys !is CredentialStoreResult.Success ||
            elevenLabsApiKey !is CredentialStoreResult.Success ||
            providerCredentialStore.recoveryState.value ==
                ProviderCredentialRecoveryState.CredentialsMustBeReentered
        ) {
            return null
        }
        return CredentialSnapshot(providerApiKeys.value, elevenLabsApiKey.value)
    }

    fun resetProviderCredentialsForReentry(): CredentialStoreResult<Unit> =
        providerCredentialStore.resetForReentry()

    private data class CredentialSnapshot(
        val providerApiKeys: Map<String, String>,
        val elevenLabsApiKey: String?
    )

    // Auto-reply preference keys
    private val autoReplyGlobalKey = booleanPreferencesKey("auto_reply_global")
    private val autoReplyWhatsAppKey = booleanPreferencesKey("auto_reply_whatsapp")
    private val autoReplySmsKey = booleanPreferencesKey("auto_reply_sms")
    private val autoReplyEmailKey = booleanPreferencesKey("auto_reply_email")
    private val autoReplyDelayKey = intPreferencesKey("auto_reply_delay_minutes")
    private val autoReplyBlacklistKey = stringSetPreferencesKey("auto_reply_blacklist")
    private val autoReplyWhitelistKey = stringSetPreferencesKey("auto_reply_whitelist")
    private val autoReplyCustomPromptKey = stringPreferencesKey("auto_reply_custom_prompt")
    private val autoReplyMaxPerHourKey = intPreferencesKey("auto_reply_max_per_hour")

    val llmConfig: Flow<LLMConfig> = dataStore.data.map { preferences ->
        mergeSecretsForRead(decodeConfig(preferences[llmConfigKey]))
    }

    val autoReplyConfig: Flow<AutoReplyConfig> = dataStore.data.map { preferences ->
        AutoReplyConfig(
            // Auto-reply is opt-in (see AutoReplyConfig): default OFF until the
            // user explicitly enables each channel.
            globalEnabled = preferences[autoReplyGlobalKey] ?: false,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: false,
            smsEnabled = preferences[autoReplySmsKey] ?: false,
            emailEnabled = preferences[autoReplyEmailKey] ?: false,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    suspend fun updateConfig(update: (LLMConfig) -> LLMConfig) {
        dataStore.edit { preferences ->
            val currentConfig = decodeConfig(preferences[llmConfigKey])
            val newConfig = update(mergeSecretsForUpdate(currentConfig))
            preferences[llmConfigKey] = json.encodeToString(storeSecretsAndStrip(newConfig))
        }
    }

    private fun decodeConfig(configStr: String?): LLMConfig = if (configStr != null) {
        try {
            json.decodeFromString<LLMConfig>(configStr)
        } catch (_: Exception) {
            LLMConfig()
        }
    } else {
        LLMConfig()
    }

    suspend fun saveModelCache(provider: String, models: List<com.opendroid.ai.core.llm.AIModel>) {
        updateConfig { current ->
            val cache = current.modelCache.toMutableMap()
            cache[provider] = models
            val fetchMap = current.lastModelFetch.toMutableMap()
            fetchMap[provider] = System.currentTimeMillis()
            current.copy(modelCache = cache, lastModelFetch = fetchMap)
        }
    }

    suspend fun updateAutoReplyConfig(config: AutoReplyConfig) {
        dataStore.edit { preferences ->
            preferences[autoReplyGlobalKey] = config.globalEnabled
            preferences[autoReplyWhatsAppKey] = config.whatsappEnabled
            preferences[autoReplySmsKey] = config.smsEnabled
            preferences[autoReplyEmailKey] = config.emailEnabled
            preferences[autoReplyDelayKey] = config.replyDelayMinutes
            preferences[autoReplyBlacklistKey] = config.blacklistedContacts
            preferences[autoReplyWhitelistKey] = config.whitelistedContacts
            if (config.customPrompt != null) {
                preferences[autoReplyCustomPromptKey] = config.customPrompt
            } else {
                preferences.remove(autoReplyCustomPromptKey)
            }
            preferences[autoReplyMaxPerHourKey] = config.maxRepliesPerContactPerHour
        }
    }
}
