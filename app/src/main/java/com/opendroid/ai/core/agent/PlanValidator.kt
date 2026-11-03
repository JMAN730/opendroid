package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.actions.ActionDispatcher
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanValidator internal constructor(
    private val isRegistered: (String) -> Boolean,
    private val unknownActionDao: dagger.Lazy<UnknownActionDao>,
    private val contactResolver: dagger.Lazy<ContactResolver>
) {

    @Inject constructor(
        actionDispatcher: dagger.Lazy<ActionDispatcher>,
        unknownActionDao: dagger.Lazy<UnknownActionDao>,
        contactResolver: dagger.Lazy<ContactResolver>
    ) : this({ actionDispatcher.get().isRegistered(it) }, unknownActionDao, contactResolver)

    companion object {
        internal val DATA_PRODUCING_ACTIONS = setOf(
            "GET_DIRECTIONS", "GET_WEATHER", "GET_NEWS", "CALCULATE",
            "CURRENCY_CONVERT", "TRANSLATE", "WEB_SEARCH", "SUMMARIZE_URL",
            "CHECK_STOCK", "DEFINE_WORD", "CONVERT_UNITS", "FACT_CHECK",
            "GET_SYSTEM_INFO", "CHECK_TRAFFIC", "CHECK_FLIGHT", "TRACK_DELIVERY",
            "CHECK_BALANCE", "LIST_CALENDAR_TODAY", "LIST_CALENDAR_WEEK",
            "READ_MESSAGES", "READ_EMAILS", "READ_NOTES", "READ_FILE",
            "LIST_FILES", "GET_SCREEN_TEXT", "LIST_INSTALLED_APPS",
            "ASK_USER", "SPLIT_BILL", "ANALYZE_SCREENSHOT",
            "READ_AND_REMEMBER_SCREEN", "RECALL_MEMORY", "QUERY_KNOWLEDGE_GRAPH"
        )

        /** A step after one of these depends on its answer, even though it produces no data. */
        private val CONFIRMATION_ACTIONS = setOf(
            "CONFIRM_ACTION", "ASK_CONFIRMATION", "REQUEST_CONFIRMATION", "CONFIRM_USER", "CONFIRM"
        )

        /**
         * A "$stepId" contact is a reference to an earlier step's result, not a name.
         * Plans are re-validated when re-evaluation modifies them, so treating one as
         * a name would queue a second ask step for a contact already being asked for.
         */
        internal fun needsContactResolution(contact: String): Boolean =
            contact.isNotEmpty() && !isPhoneNumber(contact) && !contact.startsWith("@") && !contact.startsWith("$")

        private val ASK_STEP_ID = Regex("^(.+)_ask\\d*$")

        /**
         * Drops pending ask steps this validator queued for a step whose params no
         * longer read the answer, as when re-evaluation points that step at another
         * contact. Left in place, the user is asked for a number nothing uses, and its
         * id collides with the ask queued for the new contact.
         */
        internal fun withoutOrphanedAsks(steps: List<PlanStep>): List<PlanStep> {
            val ids = steps.map { it.stepId }.toSet()
            return steps.filterNot { step ->
                val owner = ASK_STEP_ID.matchEntire(step.stepId)?.groupValues?.get(1)
                step.status == StepStatus.PENDING &&
                    step.action.equals("ASK_USER", ignoreCase = true) &&
                    owner != null && owner in ids &&
                    steps.none { other -> other.params.values.any { readsResultOf(it, step.stepId) } }
            }
        }

        /** The id for an ask step queued for [ownerId], unique among [takenIds]. */
        internal fun askStepIdFor(ownerId: String, takenIds: Set<String>): String {
            val base = "${ownerId}_ask"
            if (base !in takenIds) return base
            return generateSequence(2) { it + 1 }.map { "$base$it" }.first { it !in takenIds }
        }

        // Mirrors ActionSequenceExecutor.resolveParameters, which substitutes "$id"
        // anywhere in a value; the lookahead keeps "$s2_ask2" from counting as "$s2_ask".
        private fun readsResultOf(value: String, stepId: String): Boolean =
            Regex("\\$" + Regex.escape(stepId) + "(?![A-Za-z0-9_])").containsMatchIn(value)

        internal fun removeBadDependencies(steps: List<PlanStep>): List<PlanStep> {
            return steps.map { step ->
                if (step.dependsOn.isEmpty()) return@map step
                val trueDeps = step.dependsOn.filter { depId ->
                    val depStep = steps.find { it.stepId == depId }
                    val action = depStep?.action?.uppercase()
                    action != null && (action in DATA_PRODUCING_ACTIONS || action in CONFIRMATION_ACTIONS)
                }
                step.copy(dependsOn = trueDeps)
            }
        }

        private fun isPhoneNumber(contact: String): Boolean {
            val cleaned = contact.replace(" ", "").replace("-", "")
            return cleaned.startsWith("+") || (cleaned.isNotEmpty() && cleaned.all { it.isDigit() })
        }
    }

    fun validatePlan(plan: Plan): List<String> {
        val errors = mutableListOf<String>()
        for (step in plan.steps) {
            val err = validateStep(step)
            if (err != null) errors.add(err)
        }
        return errors
    }

    fun validateStep(step: PlanStep): String? {
        if (!isRegistered(step.action)) {
            return "Action '${step.action}' is not registered."
        }
        return null
    }

    suspend fun validateAndFix(plan: Plan, context: Context): Plan {
        val finalSteps = mutableListOf<PlanStep>()
        var currentOrder = 1
        val steps = withoutOrphanedAsks(plan.steps)
        val takenIds = steps.mapTo(mutableSetOf()) { it.stepId }

        for (step in steps) {
            // Re-plans re-validate the steps that already ran. Fixing or re-resolving
            // one would queue a fresh ask for a number the user already gave.
            if (step.status != StepStatus.PENDING) {
                finalSteps.add(step.copy(order = currentOrder++))
                continue
            }

            var updatedStep = step
            val isReg = isRegistered(step.action)

            if (!isReg) {
                when (step.action.uppercase()) {
                    "VERIFY_APP", "SECURITY_CHECK" -> {
                        updatedStep = step.copy(action = "GET_SYSTEM_INFO")
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    "LAUNCH_APP", "OPEN_APP_OR_WEBSITE" -> {
                        val isWebsite = step.action == "OPEN_APP_OR_WEBSITE" && (
                            step.params.containsKey("url") ||
                            step.params.containsKey("website") ||
                            step.params.containsKey("link") ||
                            step.params.values.any { it.startsWith("http") }
                        )
                        if (isWebsite) {
                            val urlValue = step.params["url"]
                                ?: step.params["website"]
                                ?: step.params["link"]
                                ?: step.params.values.firstOrNull { it.startsWith("http") }
                                ?: ""
                            updatedStep = step.copy(action = "SUMMARIZE_URL", params = mapOf("url" to urlValue))
                        } else {
                            val appNameValue = step.params["appName"]
                                ?: step.params["app"]
                                ?: step.params["packageName"]
                                ?: step.params["package"]
                                ?: ""
                            updatedStep = step.copy(action = "OPEN_APP", params = mapOf("appName" to appNameValue))
                        }
                        logUnknownAction(step.action, plan.goal, "AUTO_FIXED")
                    }
                    else -> {
                        logUnknownAction(step.action, plan.goal, "FAILED")
                    }
                }
            }

            val commActions = listOf("SEND_WHATSAPP", "SEND_TELEGRAM", "MAKE_CALL", "SEND_SMS", "MAKE_VIDEO_CALL")
            if (commActions.contains(updatedStep.action.uppercase()) && updatedStep.params.containsKey("contact")) {
                val contactName = updatedStep.params["contact"] ?: ""
                if (needsContactResolution(contactName)) {
                    val resolution = contactResolver.get().resolveWithDisambiguation(contactName)
                    if (resolution is ContactResolution.Found) {
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", resolution.contact.phoneNumber) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams)
                        finalSteps.add(updatedStep)
                    } else if (resolution is ContactResolution.Ambiguous) {
                        // Stays a name: a number here would stop the action showing the picker.
                        finalSteps.add(updatedStep.copy(order = currentOrder++))
                    } else if (updatedStep.action.uppercase() == "SEND_TELEGRAM") {
                        // SendTelegramAction resolves the name itself and asks for a number or @username if it can't
                        finalSteps.add(updatedStep.copy(order = currentOrder++))
                    } else {
                        val question = "I couldn't find a contact named '$contactName'. What is their phone number?"
                        val baseAskId = "${updatedStep.stepId}_ask"
                        // A second step under the same id would be asked twice and crash the Plan tab,
                        // so an existing ask is reused, but only if it asked about this contact:
                        // otherwise its answer is someone else's number.
                        val reusable = steps.any {
                            it.stepId == baseAskId && it.action == "ASK_USER" && it.params["question"] == question
                        }
                        val askStepId = if (reusable) baseAskId
                            else askStepIdFor(updatedStep.stepId, takenIds).also { takenIds += it }
                        if (!reusable) {
                            val askStep = PlanStep(
                                stepId = askStepId, order = currentOrder++,
                                description = "Ask user for contact number of '$contactName'",
                                action = "ASK_USER",
                                params = mapOf("question" to question),
                                fallback = ""
                            )
                            finalSteps.add(askStep)
                        }
                        val updatedParams = updatedStep.params.toMutableMap().apply { put("contact", "$$askStepId") }
                        val updatedDependsOn = updatedStep.dependsOn.toMutableList().apply { if (!contains(askStepId)) add(askStepId) }
                        updatedStep = updatedStep.copy(order = currentOrder++, params = updatedParams, dependsOn = updatedDependsOn)
                        finalSteps.add(updatedStep)
                    }
                } else {
                    updatedStep = updatedStep.copy(order = currentOrder++)
                    finalSteps.add(updatedStep)
                }
            } else {
                updatedStep = updatedStep.copy(order = currentOrder++)
                finalSteps.add(updatedStep)
            }
        }

        val cleanedSteps = removeBadDependencies(finalSteps)
        return plan.copy(steps = cleanedSteps, estimatedSteps = cleanedSteps.size)
    }

    private suspend fun logUnknownAction(attemptedAction: String, goal: String, fixStatus: String) {
        try {
            unknownActionDao.get().insertUnknownAction(
                UnknownActionEntity(attemptedAction = attemptedAction, goal = goal, fixStatus = fixStatus)
            )
        } catch (e: Exception) { }
    }
}
