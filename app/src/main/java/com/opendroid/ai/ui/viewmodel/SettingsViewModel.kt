package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import dagger.Lazy

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    val notificationDao: com.opendroid.ai.data.db.dao.NotificationDao,
    private val llmProviderFactory: Lazy<com.opendroid.ai.core.llm.LLMProviderFactory>
) : ViewModel() {

    private val _llmConfig = MutableStateFlow(LLMConfig())
    val llmConfig: StateFlow<LLMConfig> = _llmConfig

    init {
        viewModelScope.launch {
            _llmConfig.value = settingsRepository.llmConfig.first()
        }
    }

    fun updateActiveProvider(provider: String) {
        _llmConfig.value = _llmConfig.value.copy(activeProvider = provider)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(activeProvider = provider)
            }
        }
    }

    fun updateActiveModel(model: String) {
        _llmConfig.value = _llmConfig.value.copy(activeModel = model)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(activeModel = model)
            }
        }
    }

    fun updateApiKey(providerName: String, key: String) {
        val keys = _llmConfig.value.apiKeys.toMutableMap()
        keys[providerName] = key
        _llmConfig.value = _llmConfig.value.copy(apiKeys = keys)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                val currentKeys = current.apiKeys.toMutableMap()
                currentKeys[providerName] = key
                current.copy(apiKeys = currentKeys)
            }
        }
    }

    fun updateElevenLabsApiKey(key: String) {
        _llmConfig.value = _llmConfig.value.copy(elevenLabsApiKey = key)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(elevenLabsApiKey = key)
            }
        }
    }

    fun updateElevenLabsVoiceId(voiceId: String) {
        _llmConfig.value = _llmConfig.value.copy(elevenLabsVoiceId = voiceId)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(elevenLabsVoiceId = voiceId)
            }
        }
    }

    fun updateOllamaUrl(url: String) {
        _llmConfig.value = _llmConfig.value.copy(ollamaUrl = url)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(ollamaUrl = url)
            }
        }
    }

    fun updateCopilotUrl(url: String) {
        _llmConfig.value = _llmConfig.value.copy(copilotUrl = url)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(copilotUrl = url)
            }
        }
    }

    fun testProviderLatency(providerName: String) {
        viewModelScope.launch {
            try {
                val factory = llmProviderFactory.get()
                val provider = factory.getProviderByName(providerName)
                if (provider.isAvailable()) {
                    val request = LLMRequest(
                        systemPrompt = "You are a speed test server. Respond with 'pong'.",
                        messages = listOf(ChatMessage(id = "1", text = "ping", sender = ChatMessage.Sender.USER)),
                        responseFormat = ResponseFormat.TEXT
                    )
                    val response = provider.complete(request)
                    val updatedBenchmarks = _llmConfig.value.latencyBenchmarks.toMutableMap()
                    updatedBenchmarks[providerName] = response.latencyMs
                    _llmConfig.value = _llmConfig.value.copy(latencyBenchmarks = updatedBenchmarks)
                    settingsRepository.updateConfig { current ->
                        val currentBenchmarks = current.latencyBenchmarks.toMutableMap()
                        currentBenchmarks[providerName] = response.latencyMs
                        current.copy(latencyBenchmarks = currentBenchmarks)
                    }
                }
            } catch (e: Exception) {
                // Keep the record but fail with high number
                val updatedBenchmarks = _llmConfig.value.latencyBenchmarks.toMutableMap()
                updatedBenchmarks[providerName] = 9999L
                _llmConfig.value = _llmConfig.value.copy(latencyBenchmarks = updatedBenchmarks)
                settingsRepository.updateConfig { current ->
                    val currentBenchmarks = current.latencyBenchmarks.toMutableMap()
                    currentBenchmarks[providerName] = 9999L
                    current.copy(latencyBenchmarks = currentBenchmarks)
                }
            }
        }
    }

    fun updateAutoConfirmPlans(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(autoConfirmPlans = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(autoConfirmPlans = enabled)
            }
        }
    }

    fun updateMultiAgentMode(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(multiAgentModeEnabled = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(multiAgentModeEnabled = enabled)
            }
        }
    }

    fun updateShowFloatingButton(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(showFloatingButton = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(showFloatingButton = enabled)
            }
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        _llmConfig.value = _llmConfig.value.copy(isDarkMode = enabled)
        viewModelScope.launch {
            settingsRepository.updateConfig { current ->
                current.copy(isDarkMode = enabled)
            }
        }
    }
}
