package com.opendroid.ai.core.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Retry on an error card raised mid-plan resumes the plan instead of planning the
 * request again, so steps that already ran (a sent SMS) are not run a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanManagerRewriteStepsTestApplication::class)
class PlanManagerResumePausedPlanTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repository = PlanRepository(FakePlanDao())

    private val planManager by lazy {
        PlanManager(
            planRepository = repository,
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            planValidator = Lazy { error("Not used by these tests") }
        )
    }

    // s1 sent the SMS, then the re-evaluation call failed before s2 ran.
    private fun smsThenTimer(planId: String, status: PlanStatus) = Plan(
        planId = planId,
        goal = "Text Mom and set a timer",
        estimatedDuration = "1m",
        estimatedSteps = 2,
        steps = listOf(
            step("s1", 1, "SEND_SMS").copy(status = StepStatus.COMPLETED, result = "sent"),
            step("s2", 2, "SET_TIMER")
        ),
        status = status
    )

    private fun step(id: String, order: Int, action: String) = PlanStep(
        stepId = id,
        order = order,
        description = action,
        action = action,
        params = emptyMap(),
        fallback = ""
    )

    private suspend fun load(plan: Plan) {
        repository.savePlan(plan)
        planManager.loadPlan(plan.planId)
    }

    /** What executePlanLoop would dispatch from here on, one call per step it runs. */
    private suspend fun runRemainingSteps(): List<String> {
        val dispatched = mutableListOf<String>()
        while (true) {
            val next = planManager.getActiveStep() ?: break
            dispatched += next.action
            planManager.updateStepStatus(next.stepId, StepStatus.COMPLETED, result = "ok")
        }
        return dispatched
    }

    @Test
    fun `resuming a paused plan runs only the steps it has not run, so the SMS is not sent again`() = runBlocking {
        load(smsThenTimer("p1", PlanStatus.PAUSED))

        val resumed = planManager.resumePausedPlan("p1")

        assertEquals(PlanStatus.RUNNING, resumed?.status)
        assertEquals(PlanStatus.RUNNING, repository.getPlanById("p1")?.status)
        assertEquals(listOf("SET_TIMER"), runRemainingSteps())
    }

    @Test
    fun `a paused plan another plan has replaced is loaded back and resumed`() = runBlocking {
        repository.savePlan(smsThenTimer("p1", PlanStatus.PAUSED))
        load(smsThenTimer("p2", PlanStatus.COMPLETED))

        val resumed = planManager.resumePausedPlan("p1")

        assertEquals("p1", resumed?.planId)
        assertEquals("p1", planManager.currentPlan.value?.planId)
        assertEquals(listOf("SET_TIMER"), runRemainingSteps())
    }

    @Test
    fun `a plan that is not paused is not resumed`() = runBlocking {
        listOf(PlanStatus.FAILED, PlanStatus.CANCELLED, PlanStatus.COMPLETED, PlanStatus.RUNNING).forEach { status ->
            load(smsThenTimer("p1", status))

            assertNull(planManager.resumePausedPlan("p1"))
            assertEquals(status, planManager.currentPlan.value?.status)
        }
    }

    @Test
    fun `an unknown plan id resumes nothing and leaves the current plan alone`() = runBlocking {
        load(smsThenTimer("p1", PlanStatus.PAUSED))

        assertNull(planManager.resumePausedPlan("missing"))
        assertEquals("p1", planManager.currentPlan.value?.planId)
        assertEquals(PlanStatus.PAUSED, planManager.currentPlan.value?.status)
    }

    @Test
    fun `giving up on a paused plan fails it, so it cannot be resumed later`() = runBlocking {
        load(smsThenTimer("p1", PlanStatus.PAUSED))

        planManager.failPausedPlan("p1")

        assertEquals(PlanStatus.FAILED, planManager.currentPlan.value?.status)
        assertEquals(PlanStatus.FAILED, repository.getPlanById("p1")?.status)
        assertNull(planManager.resumePausedPlan("p1"))
    }

    @Test
    fun `giving up only fails the paused plan it names`() = runBlocking {
        load(smsThenTimer("p1", PlanStatus.RUNNING))
        planManager.failPausedPlan("p1")
        assertEquals(PlanStatus.RUNNING, planManager.currentPlan.value?.status)

        load(smsThenTimer("p2", PlanStatus.PAUSED))
        planManager.failPausedPlan("p1")
        assertEquals(PlanStatus.PAUSED, planManager.currentPlan.value?.status)
    }

    private class FakePlanDao : PlanDao {
        private val plans = mutableMapOf<String, PlanEntity>()

        override fun getAllPlansFlow(): Flow<List<PlanEntity>> = emptyFlow()
        override suspend fun getAllPlans(): List<PlanEntity> = plans.values.toList()
        override suspend fun getPlanById(planId: String): PlanEntity? = plans[planId]
        override suspend fun insertOrUpdatePlan(plan: PlanEntity) {
            plans[plan.planId] = plan
        }
        override suspend fun deletePlan(planId: String) {
            plans.remove(planId)
        }
    }
}
