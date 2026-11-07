package com.opendroid.ai.core.llm.providers

import com.opendroid.ai.core.llm.LLMProvider
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.LLMResponse
import com.opendroid.ai.core.llm.ProviderCatalog
import com.opendroid.ai.core.llm.chatgpt.ChatGptAccountStatus
import com.opendroid.ai.core.llm.chatgpt.ChatGptAuthRepository
import com.opendroid.ai.core.llm.chatgpt.ChatGptBackend
import com.opendroid.ai.core.llm.chatgpt.ChatGptProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat through a ChatGPT subscription, signed in with a device code instead of an API key.
 * The sign-in lives in [ChatGptAuthRepository]; the wire protocol in [ChatGptBackend].
 */
@Singleton
class ChatGptProvider @Inject constructor(
    private val backend: ChatGptBackend,
    private val auth: ChatGptAuthRepository
) : LLMProvider {

    override val name: String = ProviderCatalog.CHATGPT
    override val availableModels: List<String> = ChatGptProtocol.PREFERRED_MODELS

    override suspend fun complete(request: LLMRequest): LLMResponse {
        val model = modelFor(request)
        val startTime = System.currentTimeMillis()
        val completion = withContext(Dispatchers.IO) {
            backend.respond(request, model) { }
        }
        return LLMResponse(
            content = completion.text,
            tokensUsed = completion.tokensUsed,
            model = model,
            provider = name,
            latencyMs = System.currentTimeMillis() - startTime
        )
    }

    /** Emits the reply as the backend streams it, rather than replaying a finished one. */
    override fun streamComplete(request: LLMRequest): Flow<String> = flow {
        backend.respond(request, modelFor(request)) { delta -> emit(delta) }
    }.flowOn(Dispatchers.IO)

    override suspend fun isAvailable(): Boolean =
        auth.reloadStatus() is ChatGptAccountStatus.SignedIn

    private fun modelFor(request: LLMRequest): String =
        request.model?.takeIf(String::isNotBlank) ?: ChatGptProtocol.DEFAULT_MODEL
}
