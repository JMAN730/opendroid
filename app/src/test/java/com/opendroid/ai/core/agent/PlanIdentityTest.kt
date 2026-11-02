package com.opendroid.ai.core.agent

import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan id is the task's identity in task history, Plan history and the approval
 * gate, so the app mints it and never takes the one the model wrote (#174).
 */
class PlanIdentityTest {

    // The same decoder settings AgentLoop parses model plans with.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private fun parse(raw: String): Plan = normalizePlan(json.decodeFromString<Plan>(raw))

    private fun response(goal: String, action: String) = """
        {
          "goal": "$goal",
          "planId": "generate-a-uuid",
          "estimatedSteps": 1,
          "estimatedDuration": "1 minute",
          "steps": [
            {"stepId": "s1", "order": 1, "description": "$goal", "action": "$action", "params": {}}
          ]
        }
    """.trimIndent()

    @Test
    fun `two plans the model gave the same id get their own ids`() {
        val wifi = parse(response("Turn on Wi-Fi", "TOGGLE_WIFI"))
        val sms = parse(response("Text Priya", "SEND_SMS"))

        assertNotEquals(wifi.planId, sms.planId)
        assertNotEquals("generate-a-uuid", wifi.planId)
        assertNotEquals("generate-a-uuid", sms.planId)
        assertTrue(Plan.isAppMinted(wifi.planId))
        assertTrue(Plan.isAppMinted(sms.planId))
        assertFalse(Plan.isAppMinted("generate-a-uuid"))
    }

    @Test
    fun `a plan written without a planId still parses and gets an id`() {
        val plan = parse(
            """{"goal": "Turn on Wi-Fi", "estimatedSteps": 1, "estimatedDuration": "",
               "steps": [{"stepId": "s1", "order": 1, "description": "Wi-Fi", "action": "TOGGLE_WIFI"}]}"""
        )

        assertTrue(plan.planId.isNotBlank())
        assertEquals("TOGGLE_WIFI", plan.steps.single().action)
    }

    @Test
    fun `saving two separate requests keeps both in plan history`() = runBlocking {
        val repository = PlanRepository(InMemoryPlanDao())

        repository.savePlan(parse(response("Turn on Wi-Fi", "TOGGLE_WIFI")))
        repository.savePlan(parse(response("Text Priya", "SEND_SMS")))

        assertEquals(
            setOf("Turn on Wi-Fi", "Text Priya"),
            repository.allPlans.first().map { it.goal }.toSet()
        )
    }

    private class InMemoryPlanDao : PlanDao {
        private val plans = MutableStateFlow<Map<String, PlanEntity>>(emptyMap())

        override fun getAllPlansFlow(): Flow<List<PlanEntity>> = plans.map { it.values.toList() }
        override suspend fun getAllPlans(): List<PlanEntity> = plans.value.values.toList()
        override suspend fun getPlanById(planId: String): PlanEntity? = plans.value[planId]
        override suspend fun insertOrUpdatePlan(plan: PlanEntity) {
            plans.value = plans.value + (plan.planId to plan)
        }
        override suspend fun deletePlan(planId: String) {
            plans.value = plans.value - planId
        }
    }
}
