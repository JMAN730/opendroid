package com.opendroid.ai.core.agent

import com.opendroid.ai.core.llm.error.LLMError
import com.opendroid.ai.core.llm.error.LLMException
import com.opendroid.ai.core.llm.error.RedactedDetail

/**
 * Inline recovery card state. Never a ChatMessage / conversation entity /
 * memory item / TTS or prompt input.
 */
data class ChatErrorUiState(
    val requestId: String,
    val runId: String,
    val category: LLMError,
    val provider: String,
    val model: String,
    val httpStatus: Int? = null,
    val retryable: Boolean = false,
    val retryAfterMillis: Long? = null,
    val redactedDetail: RedactedDetail? = null,
    val phase: Phase = Phase.Final,
    val partialMessageId: String? = null
) {
    sealed class Phase {
        data object Final : Phase()
        data object Retrying : Phase()
        data class WaitingUntil(val epochMillis: Long) : Phase()
    }

    companion object {
        fun fromException(
            requestId: String,
            runId: String,
            failure: LLMException,
            partialMessageId: String? = null,
            nowMillis: Long = System.currentTimeMillis()
        ): ChatErrorUiState {
            val waiting = failure.retryAfterMillis?.takeIf { it > 0 }?.let { nowMillis + it }
            return ChatErrorUiState(
                requestId = requestId,
                runId = runId,
                category = failure.error,
                provider = failure.provider,
                model = failure.model,
                httpStatus = failure.status,
                retryable = failure.retryable,
                retryAfterMillis = failure.retryAfterMillis,
                redactedDetail = failure.detail,
                phase = if (waiting != null) Phase.WaitingUntil(waiting) else Phase.Final,
                partialMessageId = partialMessageId
            )
        }
    }
}

enum class ChatErrorPrimaryAction {
    OPEN_SETTINGS,
    CHOOSE_PROVIDER,
    CHOOSE_MODEL,
    EDIT_MESSAGE,
    RETRY,
    NONE
}

fun ChatErrorUiState.primaryAction(): ChatErrorPrimaryAction = when (category) {
    LLMError.AuthMissing, LLMError.AuthInvalid -> ChatErrorPrimaryAction.OPEN_SETTINGS
    LLMError.QuotaExhausted -> ChatErrorPrimaryAction.CHOOSE_PROVIDER
    LLMError.ModelUnavailable -> ChatErrorPrimaryAction.CHOOSE_MODEL
    LLMError.RequestInvalid -> ChatErrorPrimaryAction.EDIT_MESSAGE
    LLMError.RateLimited,
    LLMError.Network,
    LLMError.ServerError,
    LLMError.MalformedResponse,
    LLMError.Unknown ->
        if (retryable) ChatErrorPrimaryAction.RETRY else ChatErrorPrimaryAction.NONE
}

fun ChatErrorUiState.title(): String = when (category) {
    LLMError.AuthMissing -> "Set up $provider to continue"
    LLMError.AuthInvalid -> "$provider rejected the API key"
    LLMError.QuotaExhausted -> "$provider has no credits available"
    LLMError.RateLimited -> "$provider rate limited the request"
    LLMError.ModelUnavailable -> "Model unavailable on $provider"
    LLMError.RequestInvalid -> "Request rejected by $provider"
    LLMError.Network -> "Can't reach $provider"
    LLMError.ServerError -> "$provider had a server error"
    LLMError.MalformedResponse -> "$provider returned an unreadable response"
    LLMError.Unknown -> "$provider request failed"
}

fun ChatErrorUiState.guidance(): String = when (category) {
    LLMError.AuthMissing -> "Add an API key in Settings."
    LLMError.AuthInvalid -> "Check or replace the key in Settings."
    LLMError.QuotaExhausted -> "Add credits with $provider, or choose another provider."
    LLMError.RateLimited -> "Wait a moment, then retry."
    LLMError.ModelUnavailable -> "Choose an available model, then retry."
    LLMError.RequestInvalid -> "Edit the message and try again."
    LLMError.Network -> "Check your connection and try again."
    LLMError.ServerError -> "Try again in a moment."
    LLMError.MalformedResponse -> "Try again, or check technical details."
    LLMError.Unknown -> "Try again, or check technical details."
}
