package com.opendroid.ai.core.agent

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every status change rewrites the `plans` row, so a secret has to stay out of it
 * after the sensitive step has run too, not only on the first save.
 */
class PlanManagerSensitiveParamsTest {
    private val dao = FakePlanDao()
    private lateinit var manager: PlanManager

    @Before
    fun setUp() = runBlocking {
        val context: Context = ContextWrapper(null)
        manager = PlanManager(
            PlanRepository(dao),
            WorkingMemory(context, DeviceStateProvider(context)),
            Lazy<PlanValidator> { error("loadPlan does not validate") }
        )
        // startNewPlan needs a full ActionDispatcher, so the executable plan comes in through loadPlan.
        dao.rows["p1"] = PlanEntity(
            planId = "p1",
            goal = "Remember my locker combination is $SECRET and text it to Sam",
            estimatedDuration = "instant",
            estimatedSteps = 2,
            stepsJson = Json.encodeToString(steps()),
            status = PlanStatus.RUNNING.name,
            createdAt = 0L
        )
        assertTrue(manager.loadPlan("p1"))
    }

    @Test
    fun `row stays scrubbed after the sensitive step completes`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.COMPLETED, result = "Saved")

        assertRowHasNoSecret()
    }

    @Test
    fun `row stays scrubbed after the sensitive step fails`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.FAILED, error = "Could not save $SECRET")

        assertRowHasNoSecret()
    }

    @Test
    fun `row stays scrubbed while the echoing step runs and finishes`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.COMPLETED)

        manager.updateStepStatus("s2", StepStatus.RUNNING)
        assertRowHasNoSecret()

        manager.updateStepStatus("s2", StepStatus.COMPLETED, result = "Sent: The code is $SECRET")
        assertRowHasNoSecret()
    }

    @Test
    fun `echoing step can still run after the sensitive step`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.COMPLETED)

        val echoing = manager.getStepSnapshot("s2")!!
        assertEquals("The code is $SECRET", echoing.params["message"])
        val saved = manager.getStepSnapshot("s1")!!
        assertFalse(saved.params.values.any { it.contains(SECRET) })
    }

    @Test
    fun `finished plan holds the secret nowhere`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.COMPLETED)
        manager.updateStepStatus("s2", StepStatus.COMPLETED)

        manager.updatePlanStatus(PlanStatus.COMPLETED)

        assertRowHasNoSecret()
        assertFalse(manager.currentPlan.value.toString().contains(SECRET))
    }

    @Test
    fun `failed plan holds the secret nowhere`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.FAILED)

        manager.updatePlanStatus(PlanStatus.FAILED)

        assertRowHasNoSecret()
        assertFalse(manager.currentPlan.value.toString().contains(SECRET))
    }

    @Test
    fun `cancelled plan holds the secret nowhere`() = runBlocking {
        manager.updateStepStatus("s1", StepStatus.COMPLETED)

        manager.cancelPlan()

        assertRowHasNoSecret()
        assertFalse(manager.currentPlan.value.toString().contains(SECRET))
    }

    @Test
    fun `removing the sensitive step keeps its value out of the row`() = runBlocking {
        manager.removeStep("s1")

        assertRowHasNoSecret()
    }

    private fun assertRowHasNoSecret() {
        val row = dao.rows.getValue("p1")
        assertFalse(row.goal, row.goal.contains(SECRET))
        assertFalse(row.stepsJson, row.stepsJson.contains(SECRET))
        assertTrue(row.stepsJson, row.stepsJson.contains("Sam"))
    }

    private fun steps() = listOf(
        PlanStep(
            stepId = "s1",
            order = 1,
            description = "Save combination $SECRET",
            action = "SAVE_SENSITIVE_INFO",
            params = mapOf("key" to "locker", "secret" to SECRET)
        ),
        PlanStep(
            stepId = "s2",
            order = 2,
            description = "Text Sam the code $SECRET",
            action = "SEND_SMS",
            params = mapOf("contact" to "Sam", "message" to "The code is $SECRET")
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
