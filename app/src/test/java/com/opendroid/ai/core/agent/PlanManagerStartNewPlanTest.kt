package com.opendroid.ai.core.agent

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.db.dao.PlanDao
import com.opendroid.ai.data.db.dao.UnknownActionDao
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AgentLoop proposes and auto-approves the plan [PlanManager.startNewPlan] hands back,
 * so that plan must be the validated one that will run, not the model's raw plan (#162).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanValidatorContactsTestApplication::class)
class PlanManagerStartNewPlanTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val planManager by lazy {
        PlanManager(
            planRepository = PlanRepository(FakePlanDao()),
            workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
            planValidator = Lazy {
                PlanValidator(
                    isRegistered = { true },
                    unknownActionDao = Lazy<UnknownActionDao> { error("Not used by these tests") },
                    contactResolver = Lazy { ContactResolver(context) }
                )
            }
        )
    }

    @Before
    fun setUp() {
        FakeContactsProvider.rows = emptyList()
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun `returns the validated plan, with the step the validator added and the real count`() = runBlocking {
        val raw = Plan(
            planId = "p1",
            goal = "Check today's weather",
            estimatedDuration = "",
            estimatedSteps = 1,
            steps = listOf(
                PlanStep(stepId = "s1", order = 1, description = "Check the forecast", action = "GET_WEATHER", params = emptyMap()),
                PlanStep(
                    stepId = "s2", order = 2, description = "Check the forecast", action = "SEND_SMS",
                    params = mapOf("contact" to "Bob", "message" to "hi")
                )
            )
        )

        val proposed = planManager.startNewPlan(raw, context, PlanStatus.PROPOSED)

        assertEquals(listOf("GET_WEATHER", "ASK_USER", "SEND_SMS"), proposed.steps.map { it.action })
        assertEquals(3, proposed.estimatedSteps)
        assertEquals(PlanStatus.PROPOSED, proposed.status)
        assertEquals(planManager.currentPlan.value, proposed)
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
