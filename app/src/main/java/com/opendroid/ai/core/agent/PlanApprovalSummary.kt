package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.ActionAutoMapper
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
    // Every key a send or call handler reads a recipient from (CommunicationActions,
    // ActionAutoMapper.RECIPIENT_PARAMS). Handlers differ in which key wins, so all of
    // a step's recipients are shown, not just the first.
    private val RECIPIENT_KEYS = listOf(
        "contact", "to", "recipient", "number", "phone", "phoneNumber", "username", "channel", "destination"
    )
    private val PATH_KEYS = listOf("filePath", "path")
    private const val MAX_VALUE_LENGTH = 40
    // The same resolver ActionDispatcher runs, so an alias is labelled as what it will do.
    private val autoMapper = ActionAutoMapper()

    /** One line per step that will run, in order: the action and who or what it acts on. */
    fun stepLines(plan: Plan): List<String> = plan.steps.map { describe(it, plan.steps) }

    fun stepCount(plan: Plan): String = if (plan.steps.size == 1) "1 step" else "${plan.steps.size} steps"

    fun spokenPrompt(plan: Plan): String =
        "I've planned ${stepCount(plan)}: ${stepLines(plan).joinToString(", ")}. Say approve to run, or cancel."

    private fun describe(step: PlanStep, steps: List<PlanStep>): String {
        val mapping = autoMapper.mapAction(step.action, step.params, emptySet())
        // A SKIP resolves to no action: the step is described as written.
        val action = mapping.mappedAction ?: step.action
        val params = if (mapping.mappedAction != null) mapping.mappedParams else step.params
        val recipients = RECIPIENT_KEYS.mapNotNull { params[it]?.takeIf(String::isNotBlank) }.distinct()
        val path = PATH_KEYS.firstNotNullOfOrNull { params[it]?.takeIf(String::isNotBlank) }
        // MOVE_FILE, COPY_FILE and ZIP_FILES read sourcePath and destPath/zipFilePath; UNZIP_FILE
        // reads zipFilePath and destDirPath. "from" and "to" keep the two ends apart.
        val zip = params["zipFilePath"]?.takeIf(String::isNotBlank)
        val source = params["sourcePath"]?.takeIf(String::isNotBlank)
            ?: zip?.takeIf { !params["destDirPath"].isNullOrBlank() }
        val destination = params["destPath"]?.takeIf(String::isNotBlank)
            ?: params["destDirPath"]?.takeIf(String::isNotBlank)
            ?: zip?.takeIf { !params["sourcePath"].isNullOrBlank() }
        val amount = params["amount"]?.takeIf(String::isNotBlank)
        return buildString {
            append(actionLabel(action))
            if (recipients.isNotEmpty()) append(" to ").append(recipients.joinToString(" or ") { value(it, steps) })
            if (path != null) append(" ").append(value(path, steps))
            if (source != null) append(" from ").append(value(source, steps))
            if (destination != null) append(" to ").append(value(destination, steps))
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
