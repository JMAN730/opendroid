package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.error.LLMErrorMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stands in for a persisted provider name this build does not recognise, for
 * example a provider renamed or removed in a release without a
 * [ProviderCatalog] alias. It never touches the network and never routes to
 * another vendor: every call fails with a typed configuration error so the
 * chat shows the existing configuration card and the user can pick a provider
 * in Settings. See docs/agents/on-device-inference.md: the presence of another
 * vendor's API key is not permission to send prompts to that vendor.
 *
 * It is deliberately not wrapped in [WrappedLLMProvider], whose request
 * resolution would throw an untyped error from [ProviderCatalog.defaultModel]
 * before this provider is reached.
 */
class UnknownLLMProvider(override val name: String) : LLMProvider {
    override val availableModels: List<String> = emptyList()

    override suspend fun complete(request: LLMRequest): LLMResponse = throw failure()

    override fun streamComplete(request: LLMRequest): Flow<String> = flow { throw failure() }

    override suspend fun isAvailable(): Boolean = false

    private fun failure() = LLMErrorMapper.requestInvalid(name, "")
}
