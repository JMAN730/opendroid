package com.opendroid.ai.core.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.entities.PlanEntity
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanManagerRewriteStepsTestApplication::class)
class PlanManagerTerminalStatusTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repository = PlanRepository(FakePlanDao())

    private fun loaded(status: PlanStatus): PlanManager = runBlocking {
        val planManager = PlanManager(
            planRepository = repository,
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            planValidator = Lazy { error("Not used by these tests") }
        )
        repository.savePlan(
            Plan(
                planId = "p1",
                goal = "test goal",
                estimatedDuration = "1m",
                estimatedSteps = 1,
                steps = listOf(
                    PlanStep(
                        stepId = "s1",
                        order = 1,
                        description = "step",
                        action = "SEND_SMS",
                        params = mapOf("contact" to "+15550100"),
                        fallback = ""
                    )
                ),
                status = status
            )
        )
        planManager.loadPlan("p1")
        planManager
    }

    @Test
    fun `a cancelled plan cannot be moved back to running, so its steps stay unrunnable`() = runBlocking {
        val planManager = loaded(PlanStatus.CANCELLED)

        planManager.updatePlanStatus(PlanStatus.RUNNING)

        assertEquals(PlanStatus.CANCELLED, planManager.currentPlan.value?.status)
        assertEquals(PlanStatus.CANCELLED, repository.getPlanById("p1")?.status)
        assertNull(planManager.getActiveStep())
    }

    @Test
    fun `completed and failed plans cannot be moved back to running either`() = runBlocking {
        listOf(PlanStatus.COMPLETED, PlanStatus.FAILED).forEach { terminal ->
            val planManager = loaded(terminal)

            planManager.updatePlanStatus(PlanStatus.RUNNING)

            assertEquals(terminal, planManager.currentPlan.value?.status)
        }
    }

    @Test
    fun `a proposed plan still moves to running`() = runBlocking {
        val planManager = loaded(PlanStatus.PROPOSED)

        planManager.updatePlanStatus(PlanStatus.RUNNING)

        assertEquals(PlanStatus.RUNNING, planManager.currentPlan.value?.status)
        assertEquals("s1", planManager.getActiveStep()?.stepId)
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
