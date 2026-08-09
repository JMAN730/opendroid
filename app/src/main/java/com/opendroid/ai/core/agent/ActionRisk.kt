package com.opendroid.ai.core.agent

/**
 * Risk used when selecting an inference provider. This is intentionally
 * separate from [ActionDefinition.neverAutoApprove]: approval controls whether
 * a known plan may execute, while risk controls whether its planner may be
 * transparently moved to another provider.
 */
enum class ActionRisk(val severity: Int) {
    UNSPECIFIED(0),
    READ_ONLY(1),
    REVERSIBLE(2),
    SENSITIVE(3),
    ADVANCED_CONTROL(4),
    IRREVERSIBLE(5)
}

object ActionRiskPolicy {
    fun forDefinition(definition: ActionDefinition): ActionRisk = when {
        definition.neverAutoApprove -> ActionRisk.IRREVERSIBLE
        definition.risk != ActionRisk.UNSPECIFIED -> definition.risk
        definition.category == ActionCategory.ADVANCED -> ActionRisk.ADVANCED_CONTROL
        definition.category in setOf(
            ActionCategory.COMMUNICATION,
            ActionCategory.FINANCE,
            ActionCategory.SMART_HOME,
            ActionCategory.TRANSPORT
        ) -> ActionRisk.SENSITIVE
        definition.category in setOf(ActionCategory.INFORMATION, ActionCategory.MEDIA) ->
            ActionRisk.READ_ONLY
        else -> ActionRisk.REVERSIBLE
    }

    fun highest(risks: Iterable<ActionRisk>): ActionRisk =
        risks.maxByOrNull { it.severity } ?: ActionRisk.READ_ONLY

    /**
     * A provider switch can change the plan semantics. Keep high-impact plans
     * on the explicitly selected provider unless the user retries deliberately.
     */
    fun allowsAutomaticFallback(risk: ActionRisk): Boolean =
        risk == ActionRisk.READ_ONLY || risk == ActionRisk.REVERSIBLE
}
