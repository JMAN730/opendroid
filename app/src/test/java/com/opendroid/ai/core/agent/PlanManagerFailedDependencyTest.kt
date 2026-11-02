package com.opendroid.ai.core.agent

import android.app.Application
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

class PlanManagerFailedDependencyTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanManagerFailedDependencyTestApplication::class)
class PlanManagerFailedDependencyTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun step(id: String, order: Int, status: StepStatus = StepStatus.PENDING, dependsOn: List<String> = emptyList()) =
        PlanStep(
            stepId = id,
            order = order,
            description = "step $id",
            action = "PAY_UPI",
            params = emptyMap(),
            fallback = "",
            dependsOn = dependsOn,
            status = status
        )

    private fun managerWith(vararg steps: PlanStep): PlanManager = runBlocking {
        val repository = PlanRepository(FakePlanDao())
        val manager = PlanManager(
            planRepository = repository,
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            planValidator = Lazy { PlanValidator(unusedLazy(), unusedLazy(), unusedLazy()) }
        )
        repository.savePlan(
            Plan(
                planId = "p1",
                goal = "test goal",
                estimatedDuration = "1m",
                estimatedSteps = steps.size,
                steps = steps.toList(),
                status = PlanStatus.RUNNING
            )
        )
        manager.loadPlan("p1")
        manager
    }

    private fun PlanManager.statusOf(id: String) =
        currentPlan.value?.steps?.single { it.stepId == id }?.status

    @Test
    fun `a step depending on a failed step is not active`() {
        val manager = managerWith(step("s1", 1, StepStatus.FAILED), step("s2", 2, dependsOn = listOf("s1")))

        assertNull(manager.getActiveStep())
    }

    @Test
    fun `a step depending on a completed step is active`() {
        val manager = managerWith(step("s1", 1, StepStatus.COMPLETED), step("s2", 2, dependsOn = listOf("s1")))

        assertEquals("s2", manager.getActiveStep()?.stepId)
    }

    @Test
    fun `an unrelated step still runs after a failure`() {
        val manager = managerWith(
            step("s1", 1, StepStatus.FAILED),
            step("s2", 2, dependsOn = listOf("s1")),
            step("s3", 3)
        )

        assertEquals("s3", manager.getActiveStep()?.stepId)
    }

    @Test
    fun `failing blocked steps follows the whole dependency chain and spares the rest`() = runBlocking {
        val manager = managerWith(
            step("s1", 1, StepStatus.FAILED),
            step("s2", 2, dependsOn = listOf("s1")),
            step("s3", 3, dependsOn = listOf("s2")),
            step("s4", 4)
        )

        manager.failBlockedSteps()

        assertEquals(StepStatus.FAILED, manager.statusOf("s2"))
        assertEquals(StepStatus.FAILED, manager.statusOf("s3"))
        assertEquals(StepStatus.PENDING, manager.statusOf("s4"))
        assertEquals(
            "Skipped: depends on failed step s2",
            manager.currentPlan.value?.steps?.single { it.stepId == "s3" }?.error
        )
    }

    private fun <T : Any> unusedLazy(): Lazy<T> = Lazy { error("Not used by these tests") }

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
