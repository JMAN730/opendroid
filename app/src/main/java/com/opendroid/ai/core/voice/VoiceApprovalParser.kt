package com.opendroid.ai.core.voice

enum class VoiceApprovalIntent { APPROVE, REJECT, NONE }

/**
 * Fuzzy intent match for the spoken plan-approval prompt ("Say approve to
 * run, or cancel"). Reject wins over approve: a misheard approval runs a
 * plan, a misheard rejection merely leaves it waiting in the modal.
 * Deliberately NOT a grant mechanism — nothing spoken may widen the
 * allowlist (upstream issue 18 spec, voice section).
 */
object VoiceApprovalParser {

    private val rejectPattern = Regex(
        """\b(cancel|stop|reject|no)\b|\b(don't|do not)\s+(run|do|execute)\b""",
        RegexOption.IGNORE_CASE
    )
    private val approvePattern = Regex(
        """\b(approve|yes|run|go ahead|do it|execute)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(utterance: String): VoiceApprovalIntent = when {
        rejectPattern.containsMatchIn(utterance) -> VoiceApprovalIntent.REJECT
        approvePattern.containsMatchIn(utterance) -> VoiceApprovalIntent.APPROVE
        else -> VoiceApprovalIntent.NONE
    }
}
