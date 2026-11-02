package com.opendroid.ai.data.repository

import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `plans` table is unencrypted Room storage, written before the user has
 * approved anything. A Level 4 secret must never reach it, under any field.
 */
class PlanRepositorySensitiveParamsTest {
    private val dao = FakePlanDao()
    private val repository = PlanRepository(dao)

    @Test
    fun `stored steps do not contain the secret`() = runBlocking {
        repository.savePlan(secretPlan())

        val stored = dao.rows.getValue("p1")
        assertFalse(stored.stepsJson, stored.stepsJson.contains(SECRET))
    }

    @Test
    fun `stored goal does not keep the raw user message`() = runBlocking {
        // buildSingleStepPlan uses the raw chat message as the goal.
        repository.savePlan(secretPlan(goal = "Securely remember my locker combination is $SECRET"))

        assertFalse(dao.rows.getValue("p1").goal.contains(SECRET))
    }

    @Test
    fun `step description paraphrasing the secret is not stored`() = runBlocking {
        // A model can reformat the value, so matching the literal alone is not enough.
        repository.savePlan(secretPlan(description = "Save locker combination 45-89"))

        assertFalse(dao.rows.getValue("p1").stepsJson.contains("45-89"))
    }

    @Test
    fun `alias action names are redacted too`() = runBlocking {
        repository.savePlan(secretPlan(action = "SAVE_SECRET"))

        assertFalse(dao.rows.getValue("p1").stepsJson.contains(SECRET))
    }

    @Test
    fun `undeclared params on a sensitive action are redacted`() = runBlocking {
        val plan = secretPlan(params = mapOf("key" to "locker", "value" to SECRET), action = "STORE_SENSITIVE")

        repository.savePlan(plan)

        assertFalse(dao.rows.getValue("p1").stepsJson.contains(SECRET))
    }

    @Test
    fun `secret echoed by another step in the plan is scrubbed`() = runBlocking {
        val plan = secretPlan().let {
            it.copy(
                steps = it.steps + PlanStep(
                    stepId = "s2",
                    order = 2,
                    description = "Text Sam the code $SECRET",
                    action = "SEND_SMS",
                    params = mapOf("contact" to "Sam", "message" to "The code is $SECRET")
                )
            )
        }

        repository.savePlan(plan)

        val stored = dao.rows.getValue("p1")
        assertFalse(stored.stepsJson, stored.stepsJson.contains(SECRET))
        assertTrue(stored.stepsJson, stored.stepsJson.contains("Sam"))
    }

    @Test
    fun `reference params stay readable after reload`() = runBlocking {
        repository.savePlan(secretPlan())

        val reloaded = repository.getPlanById("p1")!!
        val params = reloaded.steps.single().params
        assertEquals("locker", params["key"])
        assertEquals("Locker combination", params["label"])
        assertEquals(PlanStatus.PROPOSED, reloaded.status)
    }

    @Test
    fun `saving a reloaded plan again stays redacted`() = runBlocking {
        repository.savePlan(secretPlan())
        val first = dao.rows.getValue("p1")

        repository.savePlan(repository.getPlanById("p1")!!)

        assertEquals(first, dao.rows.getValue("p1"))
    }

    @Test
    fun `plans without sensitive steps are stored unchanged`() = runBlocking {
        val plan = Plan(
            planId = "p2",
            goal = "Text Sam the code 4589",
            estimatedDuration = "instant",
            estimatedSteps = 1,
            steps = listOf(
                PlanStep(
                    stepId = "s1",
                    order = 1,
                    description = "Text Sam",
                    action = "SEND_SMS",
                    params = mapOf("contact" to "Sam", "message" to "The code is 4589")
                )
            )
        )

        repository.savePlan(plan)

        val reloaded = repository.getPlanById("p2")!!
        assertEquals(plan.goal, reloaded.goal)
        assertEquals(plan.steps, reloaded.steps)
    }

    private fun secretPlan(
        goal: String = "Remember my locker combination",
        description: String = "Save the locker combination",
        action: String = "SAVE_SENSITIVE_INFO",
        params: Map<String, String> = mapOf(
            "key" to "locker",
            "secret" to SECRET,
            "label" to "Locker combination"
        )
    ) = Plan(
        planId = "p1",
        goal = goal,
        estimatedDuration = "instant",
        estimatedSteps = 1,
        status = PlanStatus.PROPOSED,
        steps = listOf(
            PlanStep(
                stepId = "s1",
                order = 1,
                description = description,
                action = action,
                params = params
            )
        )
    )

    private class FakePlanDao : PlanDao {
        val rows = linkedMapOf<String, PlanEntity>()

        override fun getAllPlansFlow(): Flow<List<PlanEntity>> = MutableStateFlow(rows.values.toList())

        override suspend fun getAllPlans(): List<PlanEntity> = rows.values.toList()

        override suspend fun getPlanById(planId: String): PlanEntity? = rows[planId]

        override suspend fun insertOrUpdatePlan(plan: PlanEntity) {
            rows[plan.planId] = plan
        }

        override suspend fun deletePlan(planId: String) {
            rows.remove(planId)
        }
    }

    private companion object {
        const val SECRET = "4589"
    }
}
