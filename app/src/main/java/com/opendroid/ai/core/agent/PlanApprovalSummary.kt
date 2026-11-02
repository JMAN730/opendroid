package com.opendroid.ai.core.agent

import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep

/**
 * What the approval card and the spoken approval prompt tell the user before a plan
 * runs. Built from the steps themselves: a plan's goal, step descriptions and
 * estimatedSteps are text the model wrote about its own plan, and a plan that says
 * "check the weather, 1 step" can still send an SMS.
 */
object PlanApprovalSummary {

    private val ACRONYMS = setOf("SMS", "UPI", "URL", "PDF", "QR", "GPS", "NFC", "VPN")
    private val RECIPIENT_KEYS = listOf("contact", "to", "destination")
    private val PATH_KEYS = listOf("filePath", "path")
    private const val MAX_VALUE_LENGTH = 40

    /** One line per step that will run, in order: the action and who or what it acts on. */
    fun stepLines(plan: Plan): List<String> = plan.steps.map { describe(it, plan.steps) }

    fun stepCount(plan: Plan): String = if (plan.steps.size == 1) "1 step" else "${plan.steps.size} steps"

    fun spokenPrompt(plan: Plan): String =
        "I've planned ${stepCount(plan)}: ${stepLines(plan).joinToString(", ")}. Say approve to run, or cancel."

    private fun describe(step: PlanStep, steps: List<PlanStep>): String {
        val recipient = RECIPIENT_KEYS.firstNotNullOfOrNull { step.params[it]?.takeIf(String::isNotBlank) }
        val path = PATH_KEYS.firstNotNullOfOrNull { step.params[it]?.takeIf(String::isNotBlank) }
        val amount = step.params["amount"]?.takeIf(String::isNotBlank)
        return buildString {
            append(actionLabel(step.action))
            if (recipient != null) append(" to ").append(value(recipient, steps))
            if (path != null) append(" ").append(value(path, steps))
            if (amount != null) append(" (amount ").append(value(amount, steps)).append(")")
        }
    }

    private fun actionLabel(action: String): String {
        val words = action.split('_').filter(String::isNotBlank)
        if (words.isEmpty()) return "Unknown action"
        return words.mapIndexed { i, word ->
            val upper = word.uppercase()
            when {
                upper in ACRONYMS -> upper
                i == 0 -> upper.lowercase().replaceFirstChar(Char::uppercaseChar)
                else -> upper.lowercase()
            }
        }.joinToString(" ")
    }

    // "$stepId" is filled in from that step's result when the plan runs.
    private fun value(raw: String, steps: List<PlanStep>): String {
        if (raw.startsWith("$")) {
            steps.firstOrNull { it.stepId == raw.removePrefix("$") }?.let { return "the answer to step ${it.order}" }
        }
        return if (raw.length > MAX_VALUE_LENGTH) raw.take(MAX_VALUE_LENGTH - 1) + "…" else raw
    }
}
