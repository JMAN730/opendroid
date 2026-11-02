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
import com.opendroid.ai.data.repository.PlanRepository
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class PlanManagerRewriteStepsTestApplication : Application()

/**
 * The transforms here return no steps, because PlanValidator needs the real
 * ActionDispatcher for any step it is given. What matters is what they are handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanManagerRewriteStepsTestApplication::class)
class PlanManagerRewriteStepsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repository = PlanRepository(FakePlanDao())
    private lateinit var planManager: PlanManager

    private fun step(id: String, order: Int) = PlanStep(
        stepId = id,
        order = order,
        description = "step $id",
        action = "SEND_SMS",
        params = mapOf("contact" to "+1555010$order"),
        fallback = ""
    )

    private val plan = Plan(
        planId = "p1",
        goal = "test goal",
        estimatedDuration = "1m",
        estimatedSteps = 2,
        steps = listOf(step("s1", 1), step("s2", 2)),
        status = PlanStatus.RUNNING
    )

    @Before
    fun setUp() = runBlocking {
        planManager = PlanManager(
            planRepository = repository,
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            planValidator = Lazy { PlanValidator(unusedLazy(), unusedLazy(), unusedLazy()) }
        )
        repository.savePlan(plan)
        planManager.loadPlan("p1")
        Unit
    }

    @Test
    fun `transform and before see a deletion and an edit made after the snapshot`() = runBlocking {
        planManager.removeStep("s1")
        planManager.updateStep("s2", "edited", mapOf("contact" to "+15550199"))
        var handed = emptyList<PlanStep>()

        val rewrite = planManager.rewriteSteps("p1", PlanStatus.PROPOSED, context) { live ->
            handed = live
            emptyList()
        }!!

        assertEquals(listOf("s2"), handed.map { it.stepId })
        assertEquals(mapOf("contact" to "+15550199"), handed.single().params)
        assertEquals(handed, rewrite.before.steps)
    }

    @Test
    fun `result is stored and returned with the requested status`() = runBlocking {
        val rewrite = planManager.rewriteSteps("p1", PlanStatus.PROPOSED, context) { emptyList() }!!

        assertEquals(PlanStatus.PROPOSED, rewrite.after.status)
        assertEquals(rewrite.after, planManager.currentPlan.value)
        assertEquals(PlanStatus.PROPOSED, repository.getPlanById("p1")?.status)
    }

    @Test
    fun `a plan that was replaced is left alone`() = runBlocking {
        assertNull(planManager.rewriteSteps("other", PlanStatus.PROPOSED, context) { emptyList() })
        assertEquals(plan.steps, planManager.currentPlan.value?.steps)
    }

    @Test
    fun `a plan that was cancelled stays cancelled`() = runBlocking {
        planManager.cancelPlan()

        assertNull(planManager.rewriteSteps("p1", PlanStatus.PROPOSED, context) { emptyList() })
        assertEquals(PlanStatus.CANCELLED, planManager.currentPlan.value?.status)
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
