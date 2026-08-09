package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionSchema

/**
 * The risk level required to plan an action safely. This is deliberately
 * independent from [com.opendroid.ai.core.agent.AutoApprovalPolicy]: approval
 * controls whether a user must confirm execution, while this policy controls
 * which model is trusted to produce the plan.
 */
object ProviderRoutingPolicy {
    private val localProviderNames = setOf(
        ProviderCatalog.ON_DEVICE,
        "LiteRT-LM (On-device)"
    )

    fun isOnDevice(providerName: String): Boolean =
        ProviderCatalog.canonicalName(providerName) in localProviderNames

    fun requiredRisk(actionNames: Collection<String>): ActionRisk =
        actionNames
            .map { ActionSchema.riskFor(it) }
            .maxOrNull()
            ?: ActionRisk.LOW

    /**
     * Unknown actions are classified as CRITICAL by [ActionSchema.riskFor],
     * so a caller that has not validated a plan fails closed.
     */
    fun requiresRemote(actionNames: Collection<String>): Boolean =
        requiredRisk(actionNames) >= ActionRisk.HIGH

    fun chooseProvider(
        activeProvider: String,
        configuredFallback: String?,
        actionNames: Collection<String>,
        fallbackAvailable: Boolean
    ): ProviderRoute {
        val active = ProviderCatalog.canonicalName(activeProvider)
        val fallback = configuredFallback
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(ProviderCatalog::canonicalName)

        if (!isOnDevice(active) || !requiresRemote(actionNames)) {
            return ProviderRoute.Active(active)
        }

        if (fallback == null || fallback == active || !fallbackAvailable) {
            return ProviderRoute.Blocked(
                activeProvider = active,
                requiredRisk = requiredRisk(actionNames)
            )
        }

        return ProviderRoute.ExplicitFallback(
            activeProvider = active,
            fallbackProvider = fallback,
            requiredRisk = requiredRisk(actionNames)
        )
    }
}
sealed interface ProviderRoute {
    data class Active(val provider: String) : ProviderRoute
    data class ExplicitFallback(
        val activeProvider: String,
        val fallbackProvider: String,
        val requiredRisk: ActionRisk
    ) : ProviderRoute
    data class Blocked(
        val activeProvider: String,
        val requiredRisk: ActionRisk
    ) : ProviderRoute
}
