package com.opendroid.ai.core.voice

enum class VoiceApprovalIntent { APPROVE, REJECT, NONE }

/**
 * Fuzzy intent match for the spoken plan-approval prompt ("Say approve to
 * run, or cancel"). Reject wins over approve. Approve only matches when the
 * whole utterance is a bare affirmative, so a negation in any language ("don't
 * approve", "run mat karo") falls through to NONE instead of running the plan.
 * NONE is the safe answer for anything unclear: a misheard approval runs the
 * plan and a misheard rejection clears it, while NONE leaves it waiting in the
 * modal.
 * Deliberately NOT a grant mechanism — nothing spoken may widen the
 * allowlist (upstream issue 18 spec, voice section).
 */
object VoiceApprovalParser {

    private const val AFFIRMATIVE = """approve|yes|haan|sí|si|sim|run|execute|go ahead|do it"""

    private val rejectPattern = Regex(
        """\b(cancel|stop|reject|no)\b|\b(don['’]?t|do not)\s+(run|do|execute)\b""",
        RegexOption.IGNORE_CASE
    )
    private val separators = Regex("""[^\p{L}\p{N}]+""")
    private val approveUtterance = Regex(
        """(?:(?:okay|ok|please) )*(?:$AFFIRMATIVE)(?: (?:and )?(?:$AFFIRMATIVE))*(?: (?:it|that|this|the plan|please|now))*"""
    )

    fun parse(utterance: String): VoiceApprovalIntent = when {
        rejectPattern.containsMatchIn(utterance) -> VoiceApprovalIntent.REJECT
        approveUtterance.matches(normalize(utterance)) -> VoiceApprovalIntent.APPROVE
        else -> VoiceApprovalIntent.NONE
    }

    private fun normalize(utterance: String): String =
        utterance.lowercase().replace(separators, " ").trim()
}
