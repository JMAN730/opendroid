package com.opendroid.ai.actions.base

import kotlinx.serialization.Serializable

@Serializable
sealed class ActionResult {
    abstract val success: Boolean
    abstract val data: String?
    abstract val error: String?

    @Serializable
    data class Success(val dataMap: Map<String, String> = emptyMap()) : ActionResult() {
        override val success: Boolean get() = true
        override val data: String? get() = dataMap["message"] ?: if (dataMap.isNotEmpty()) dataMap.toString() else null
        override val error: String? get() = null
    }

    @Serializable
    data class Failure(
        val errorMsg: String,
        val fallback: String = ""
    ) : ActionResult() {
        override val success: Boolean get() = false
        override val data: String? get() = null
        override val error: String? get() = errorMsg
    }

    @Serializable
    data class UnknownAction(
        val attemptedAction: String,
        val availableActions: List<String>
    ) : ActionResult() {
        override val success: Boolean get() = false
        override val data: String? get() = null
        override val error: String? get() = "Action '$attemptedAction' is not registered in ActionDispatcher"
    }

    @Serializable
    data class NeedsInput(
        val question: String,
        val options: List<String> = emptyList(),
        val metadata: Map<String, String> = emptyMap()
    ) : ActionResult() {
        override val success: Boolean get() = false
        override val data: String? get() = null
        override val error: String? get() = "Needs user input: $question"
    }

    companion object {
        operator fun invoke(
            success: Boolean,
            data: String? = null,
            error: String? = null,
            fallbackExecuted: Boolean = false
        ): ActionResult {
            return if (success) {
                val dataMap = if (data != null) mapOf("message" to data) else emptyMap()
                Success(dataMap)
            } else {
                Failure(
                    errorMsg = error ?: data ?: "Unknown error",
                    fallback = if (fallbackExecuted) data ?: "" else ""
                )
            }
        }
    }
}
