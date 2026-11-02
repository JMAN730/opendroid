package com.opendroid.ai.data.repository

import com.opendroid.ai.core.agent.PlanPrivacy
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanRepository @Inject constructor(
    private val planDao: PlanDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    val allPlans: Flow<List<Plan>> = planDao.getAllPlansFlow().map { entities ->
        entities.map { entity -> mapEntityToPlan(entity) }
    }

    suspend fun getPlanById(planId: String): Plan? {
        val entity = planDao.getPlanById(planId) ?: return null
        return mapEntityToPlan(entity)
    }

    /**
     * Writes a redacted copy: this table is unencrypted and is written before
     * the user approves anything. The caller's in-memory plan keeps its values.
     */
    suspend fun savePlan(plan: Plan) {
        val safePlan = PlanPrivacy.redactPlan(plan)
        val stepsJson = json.encodeToString(safePlan.steps)
        planDao.insertOrUpdatePlan(
            PlanEntity(
                planId = safePlan.planId,
                goal = safePlan.goal,
                estimatedDuration = safePlan.estimatedDuration,
                estimatedSteps = safePlan.estimatedSteps,
                stepsJson = stepsJson,
                status = safePlan.status.name,
                createdAt = safePlan.createdAt
            )
        )
    }

    suspend fun deletePlan(planId: String) {
        planDao.deletePlan(planId)
    }

    private fun mapEntityToPlan(entity: PlanEntity): Plan {
        val steps = try {
            json.decodeFromString<List<PlanStep>>(entity.stepsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Plan(
            planId = entity.planId,
            goal = entity.goal,
            estimatedDuration = entity.estimatedDuration,
            estimatedSteps = entity.estimatedSteps,
            steps = steps,
            status = PlanStatus.valueOf(entity.status),
            createdAt = entity.createdAt
        )
    }
}
