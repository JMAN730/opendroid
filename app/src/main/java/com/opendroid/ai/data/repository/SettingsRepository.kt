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
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.models.LLMConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val llmConfigKey = stringPreferencesKey("llm_config")

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

    val llmConfig: Flow<LLMConfig> = context.dataStore.data.map { preferences ->
        val configStr = preferences[llmConfigKey]
        if (configStr != null) {
            try {
                json.decodeFromString<LLMConfig>(configStr)
            } catch (e: Exception) {
                LLMConfig()
            }
        } else {
            LLMConfig()
        }
    }

    val autoReplyConfig: Flow<AutoReplyConfig> = context.dataStore.data.map { preferences ->
        AutoReplyConfig(
            globalEnabled = preferences[autoReplyGlobalKey] ?: true,
            whatsappEnabled = preferences[autoReplyWhatsAppKey] ?: true,
            smsEnabled = preferences[autoReplySmsKey] ?: true,
            emailEnabled = preferences[autoReplyEmailKey] ?: true,
            replyDelayMinutes = preferences[autoReplyDelayKey] ?: 15,
            blacklistedContacts = preferences[autoReplyBlacklistKey] ?: emptySet(),
            whitelistedContacts = preferences[autoReplyWhitelistKey] ?: emptySet(),
            customPrompt = preferences[autoReplyCustomPromptKey],
            maxRepliesPerContactPerHour = preferences[autoReplyMaxPerHourKey] ?: 3
        )
    }

    suspend fun updateConfig(update: (LLMConfig) -> LLMConfig) {
        context.dataStore.edit { preferences ->
            val currentStr = preferences[llmConfigKey]
            val currentConfig = if (currentStr != null) {
                try {
                    json.decodeFromString<LLMConfig>(currentStr)
                } catch (e: Exception) {
                    LLMConfig()
                }
            } else {
                LLMConfig()
            }
            val newConfig = update(currentConfig)
            preferences[llmConfigKey] = json.encodeToString(newConfig)
        }
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
        context.dataStore.edit { preferences ->
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
