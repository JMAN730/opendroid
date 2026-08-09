package com.opendroid.ai.core.llm

interface LLMProviderResolver {
    suspend fun getActiveProvider(actionNames: Collection<String> = emptyList()): LLMProvider

    /**
     * Returns only the explicitly configured, distinct, currently authorized
     * and available fallback. A null result is a deliberate safe failure.
     */
    suspend fun getExplicitFallbackProvider(excludedProvider: String): LLMProvider?
}
