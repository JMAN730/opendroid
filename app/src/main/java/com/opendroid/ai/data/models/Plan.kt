package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

@Serializable
data class Plan(
    // Left out of model output: normalizePlan mints the id for every parsed plan.
    val planId: String = "",
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    /** Values of sensitive steps that have run; see [com.opendroid.ai.core.agent.PlanPrivacy]. */
    @Transient val retiredSecrets: Set<String> = emptySet()
) {
    companion object {
        // Marks ids the app minted, so task history can tell them from the model-written
        // ids logged before #174, which several tasks could share.
        private const val APP_ID_PREFIX = "app-"

        /** A fresh plan id, unique to one execution of one plan. */
        fun newId(): String = APP_ID_PREFIX + UUID.randomUUID()

        /** Whether [planId] came from [newId], not from a model. */
        fun isAppMinted(planId: String): Boolean = planId.startsWith(APP_ID_PREFIX)
    }
}

enum class PlanStatus {
    PROPOSED, PENDING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLED
}
