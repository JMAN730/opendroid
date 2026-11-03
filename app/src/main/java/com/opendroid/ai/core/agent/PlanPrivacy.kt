package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.ActionAutoMapper
import com.opendroid.ai.core.memory.ExecutionHistoryPrivacy
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStep

/**
 * Keeps [ActionDefinition.sensitiveParams] values out of plans that outlive the
 * step needing them: the unencrypted `plans` table and finished in-memory plans.
 * A plan is only executable before redaction, so nothing may run a redacted copy.
 */
object PlanPrivacy {
    private val autoMapper = ActionAutoMapper()

    /**
     * Returns a copy of [plan] that is safe to persist. Plans with no sensitive
     * step come back unchanged. The goal and the sensitive step's description are
     * replaced outright because they are free text: the goal can be the raw chat
     * message, and a model can reformat a value ("45-89") past a literal match.
     * The copy holds no secrets of its own, so it also serves as a finished plan.
     */
    fun redactPlan(plan: Plan): Plan {
        if (plan.retiredSecrets.isEmpty() && plan.steps.none(::isSensitiveStep)) return plan
        val secrets = plan.steps.flatMap(::secretValues) + plan.retiredSecrets
        return plan.copy(
            goal = ExecutionHistoryPrivacy.SENSITIVE_ACTION_DESCRIPTION,
            retiredSecrets = emptySet(),
            steps = plan.steps.map { step ->
                step.copy(
                    description = if (isSensitiveStep(step)) {
                        ExecutionHistoryPrivacy.SENSITIVE_ACTION_DESCRIPTION
                    } else {
                        scrub(step.description, secrets)
                    },
                    params = redactParams(step.action, step.params)
                        .mapValues { (_, value) -> scrub(value, secrets) },
                    fallback = scrub(step.fallback, secrets),
                    result = step.result?.let { scrub(it, secrets) },
                    error = step.error?.let { scrub(it, secrets) }
                )
            }
        )
    }

    /**
     * Drops the secrets of the step [stepId] once it has run. Later steps keep their
     * params, and the goal stays readable because plan re-evaluation still uses it.
     * The values move to [Plan.retiredSecrets], because those later steps can still
     * echo them and every save until the plan ends has to scrub them.
     */
    fun forgetStepSecrets(plan: Plan, stepId: String): Plan {
        val target = plan.steps.find { it.stepId == stepId } ?: return plan
        if (!isSensitiveStep(target)) return plan
        val secrets = secretValues(target)
        return plan.copy(
            goal = scrub(plan.goal, secrets),
            retiredSecrets = plan.retiredSecrets + secrets,
            steps = plan.steps.map { step ->
                if (step.stepId != stepId) {
                    step
                } else {
                    step.copy(
                        description = ExecutionHistoryPrivacy.SENSITIVE_ACTION_DESCRIPTION,
                        params = redactParams(step.action, step.params)
                    )
                }
            }
        )
    }

    fun redactParams(actionName: String, params: Map<String, String>): Map<String, String> {
        val publicParams = publicParamsOf(actionName)
        return params.mapValues { (name, value) ->
            if (isSecretParam(name, publicParams)) ExecutionHistoryPrivacy.REDACTED else value
        }
    }

    private fun isSensitiveStep(step: PlanStep): Boolean {
        val publicParams = publicParamsOf(step.action)
        return publicParams != null || step.params.keys.any { isSecretParam(it, publicParams) }
    }

    private fun secretValues(step: PlanStep): List<String> {
        val publicParams = publicParamsOf(step.action)
        return step.params
            .filter { (name, value) ->
                isSecretParam(name, publicParams) &&
                    value.isNotBlank() &&
                    value != ExecutionHistoryPrivacy.REDACTED
            }
            .values
            .toList()
    }

    /**
     * On a sensitive action, anything not declared public counts as secret, so a
     * model naming the param "value" fails closed. Elsewhere only declared names do.
     */
    private fun isSecretParam(name: String, publicParams: Set<String>?): Boolean =
        if (publicParams != null) {
            name !in publicParams
        } else {
            name in ActionSchema.sensitiveParamNames
        }

    /** The non-secret params of a sensitive action, or null if the action isn't sensitive. */
    private fun publicParamsOf(actionName: String): Set<String>? {
        val canonical = autoMapper.mapAction(actionName, emptyMap(), emptySet()).mappedAction
        val definition = canonical?.let(ActionSchema::getAction) ?: return null
        if (definition.sensitiveParams.isEmpty()) return null
        return definition.params.map { it.name }.toSet() - definition.sensitiveParams
    }

    /**
     * Placeholders already in [text] are skipped, so a secret that is a substring of the
     * placeholder (such as "ED") can't mangle it into something the guards don't recognise.
     */
    private fun scrub(text: String, secrets: List<String>): String {
        val ordered = secrets.sortedByDescending { it.length }
        return text.split(ExecutionHistoryPrivacy.REDACTED).joinToString(ExecutionHistoryPrivacy.REDACTED) { part ->
            ordered.fold(part) { current, secret -> current.replace(secret, ExecutionHistoryPrivacy.REDACTED) }
        }
    }
}
