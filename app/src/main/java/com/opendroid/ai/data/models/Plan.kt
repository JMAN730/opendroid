package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

@Serializable
data class Plan(
    val planId: String,
    val goal: String,
    val estimatedDuration: String,
    val estimatedSteps: Int,
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

enum class PlanStatus {
    PROPOSED, PENDING, RUNNING, COMPLETED, FAILED, PAUSED, CANCELLED
}
