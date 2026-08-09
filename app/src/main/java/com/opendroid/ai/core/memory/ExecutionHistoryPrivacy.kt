package com.opendroid.ai.core.memory

import com.opendroid.ai.core.crash.CrashLogRedactor

/**
 * Redaction rules for the local execution-history sink and macro recorder.
 * Parameters are sorted here as well, so history-derived macro JSON is
 * deterministic across runs.
 */
object ExecutionHistoryPrivacy {
    const val REDACTED = "[REDACTED]"

    private val sensitiveKey = Regex(
        "(?:api[_-]?key|access[_-]?token|auth[_-]?token|token|secret|password|credential|authorization|bearer|private[_-]?key|client[_-]?secret)",
        RegexOption.IGNORE_CASE
    )

    fun sanitizeParams(
        actionName: String,
        params: Map<String, String>
    ): Map<String, String> = params.toSortedMap().mapValues { (key, value) ->
        if (actionName.equals("SEND_EMAIL", ignoreCase = true) || sensitiveKey.containsMatchIn(key)) {
            REDACTED
        } else {
            val redacted = CrashLogRedactor.redact(value)
            if (redacted != value) REDACTED else value
        }
    }

    fun sanitizeDescription(actionName: String, description: String): String =
        if (actionName.equals("SEND_EMAIL", ignoreCase = true)) {
            "Email action"
        } else {
            CrashLogRedactor.redact(description)
        }
}
