package com.opendroid.ai.core.agent

/**
 * What the plan loop does with a re-evaluation decision. The decision is free-form LLM
 * output, so only an exact CONTINUE lets the next step run: "STOP", or the prompt's
 * "CONTINUE | MODIFY | ABANDON" echoed back, must not read as permission.
 */
enum class PlanContinuation {
    Continue, Modify, Abandon;

    companion object {
        fun afterReEvaluation(decision: String): PlanContinuation = when (decision.trim().uppercase()) {
            "CONTINUE" -> Continue
            "MODIFY" -> Modify
            else -> Abandon
        }
    }
}
