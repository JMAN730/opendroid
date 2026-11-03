package com.opendroid.ai.core.agent

import android.content.Context
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.models.PlanStatus
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.StepStatus
import com.opendroid.ai.data.repository.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanManager @Inject constructor(
    private val planRepository: PlanRepository,
    private val workingMemory: WorkingMemory,
    private val planValidator: dagger.Lazy<PlanValidator>
) {
    private val _currentPlan = MutableStateFlow<Plan?>(null)
    val currentPlan: StateFlow<Plan?> = _currentPlan.asStateFlow()

    /**
     * Guards every read-modify-write of [_currentPlan]. There are now two
     * independent writers - AgentLoop's executePlanLoop coroutine driving
     * execution, and the Plan tab editor mutating pending steps from the UI
     * - and every mutator below reads `_currentPlan.value`, derives a new
     * Plan from it, and writes the result back. Without a lock held across
     * that whole read+write, two concurrent mutators can both read the same
     * stale snapshot and the later write silently discards the other (e.g.
     * a step that just finished reverting to RUNNING because a concurrent
     * param edit was computed from the pre-completion snapshot).
     */
    private val mutex = Mutex()

    /**
     * Used only by [clearPlan]. That function must stay non-suspend because
     * it is invoked synchronously from AgentLoop.rejectProposedPlan() (not a
     * suspend function), so it can't take [mutex] with `withLock` directly
     * on the caller's thread. clearPlan() has nothing to compute from the
     * previous value - it unconditionally nulls the plan - so it hops onto
     * this scope to acquire the mutex before writing, which keeps it
     * serialized with every other mutator instead of racing them with an
     * unguarded assignment.
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Validates [plan], makes it the current plan and returns the stored copy. Show and
     * approve that copy, not [plan]: validation can add steps and change the count.
     */
    suspend fun startNewPlan(
        plan: Plan,
        context: Context,
        initialStatus: PlanStatus = PlanStatus.RUNNING
    ): Plan {
        val validatedPlan = planValidator.get().validateAndFix(plan, context)
        mutex.withLock {
            val initialPlan = validatedPlan.copy(status = initialStatus)
            _currentPlan.value = initialPlan
            workingMemory.activePlan = initialPlan
            saveCurrentPlanLocked()
            return initialPlan
        }
    }

    data class StepRewrite(val before: Plan, val after: Plan)

    /**
     * Replaces the live plan's steps with what [transform] derives from them, then
     * re-validates. Unlike [startNewPlan], validation runs inside [mutex], so a Plan-tab
     * edit cannot land between the read and the write and be discarded. PlanValidator
     * never calls back into this class, so holding the lock across it cannot deadlock.
     *
     * Returns null and changes nothing when the current plan is no longer
     * [expectedPlanId] or has reached a terminal status.
     */
    suspend fun rewriteSteps(
        expectedPlanId: String,
        status: PlanStatus,
        context: Context,
        transform: (List<PlanStep>) -> List<PlanStep>
    ): StepRewrite? {
        mutex.withLock {
            val before = _currentPlan.value ?: return null
            if (before.planId != expectedPlanId || before.status.isTerminal()) return null
            val validatedPlan = planValidator.get().validateAndFix(before.copy(steps = transform(before.steps)), context)
            val after = validatedPlan.copy(status = status)
            _currentPlan.value = after
            workingMemory.activePlan = after
            saveCurrentPlanLocked()
            return StepRewrite(before, after)
        }
    }

    suspend fun updateStepStatus(stepId: String, status: StepStatus, result: String? = null, error: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(status = status, result = result, error = error)
                } else {
                    step
                }
            }
            // A step that has run never needs its secret again, so it leaves the step.
            val updatedPlan = plan.copy(steps = updatedSteps).let {
                if (status == StepStatus.COMPLETED || status == StepStatus.FAILED) {
                    PlanPrivacy.forgetStepSecrets(it, stepId)
                } else {
                    it
                }
            }
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    suspend fun updatePlanStatus(status: PlanStatus) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            // RUNNING would make a finished plan's pending steps runnable again.
            if (status == PlanStatus.RUNNING && plan.status.isTerminal()) return@withLock
            val updatedPlan = plan.copy(status = status).let {
                if (status.isTerminal()) PlanPrivacy.redactPlan(it) else it
            }
            _currentPlan.value = updatedPlan
            if (status == PlanStatus.COMPLETED || status == PlanStatus.FAILED) {
                workingMemory.activePlan = null
            } else {
                workingMemory.activePlan = updatedPlan
            }
            saveCurrentPlanLocked()
        }
    }

    suspend fun loadPlan(planId: String): Boolean {
        val plan = planRepository.getPlanById(planId) ?: return false
        mutex.withLock {
            _currentPlan.value = plan
            workingMemory.activePlan = plan
        }
        return true
    }

    /**
     * Makes [planId] current again and moves it from PAUSED back to RUNNING, so
     * [getActiveStep] hands out only the steps it has not run yet. Loads the plan from
     * the database when another plan has replaced it here. Returns null and changes
     * nothing unless that plan is PAUSED: only a mid-plan model failure pauses one, and
     * a plan in any other status is either still going or finished for good.
     */
    suspend fun resumePausedPlan(planId: String): Plan? {
        val stored = if (_currentPlan.value?.planId == planId) null else planRepository.getPlanById(planId)
        mutex.withLock {
            val plan = _currentPlan.value?.takeIf { it.planId == planId } ?: stored ?: return null
            if (plan.status != PlanStatus.PAUSED) return null
            val resumed = plan.copy(status = PlanStatus.RUNNING)
            _currentPlan.value = resumed
            workingMemory.activePlan = resumed
            saveCurrentPlanLocked()
            return resumed
        }
    }

    /**
     * Fails [planId] if it is the current plan and still PAUSED, for when the user
     * gives up on resuming it. Anything else is left alone.
     */
    suspend fun failPausedPlan(planId: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            if (plan.planId != planId || plan.status != PlanStatus.PAUSED) return@withLock
            _currentPlan.value = plan.copy(status = PlanStatus.FAILED)
            workingMemory.activePlan = null
            saveCurrentPlanLocked()
        }
    }

    suspend fun saveCurrentPlan() {
        mutex.withLock {
            saveCurrentPlanLocked()
        }
    }

    /**
     * Persists whatever is currently in [_currentPlan]. Must only be called
     * while holding [mutex] so the value it reads can never be a
     * half-applied write from a concurrent mutator.
     */
    private suspend fun saveCurrentPlanLocked() {
        val plan = _currentPlan.value ?: return
        planRepository.savePlan(plan)
    }

    fun clearPlan() {
        scope.launch {
            mutex.withLock {
                _currentPlan.value = null
                workingMemory.activePlan = null
            }
        }
    }

    /**
     * Marks the current plan (if any) as cancelled and clears it from working
     * memory. Safe to call when there is no active plan, and a no-op when the
     * plan sitting in [_currentPlan] has already reached a terminal status
     * (COMPLETED, FAILED, or CANCELLED).
     *
     * That terminal-status guard is load-bearing, not defensive dressing: unlike
     * every other mutator here, callers of cancelPlan() in AgentLoop (see
     * cancelCurrentTask, resolveStaleProposedPlan, abandonWaitingTask) don't
     * necessarily know whether a task is genuinely still running - e.g.
     * cancelCurrentTask() is documented as "safe to call when nothing is
     * running", and when nothing was running, whatever plan is still parked in
     * [_currentPlan] is the *last finished* plan (PlanScreen intentionally keeps
     * showing it there after completion - see updatePlanStatus). Without this
     * guard, a call with nothing actually in flight would relabel that finished
     * plan CANCELLED and persist that lie over its real COMPLETED/FAILED row.
     * Once a plan has reached a terminal status, it is done - nothing may ever
     * move it to a different status again.
     *
     * [expectedPlanId], when non-null, adds a second, independent guard: this
     * only applies CANCELLED when the plan currently held here is STILL the one
     * identified by that id. AgentLoop's cancellation paths (cancelCurrentTask,
     * abandonWaitingTask, resolveStaleProposedPlan) fire their join-then-cancel
     * follow-up on a separate coroutine that can run arbitrarily later than the
     * moment cancellation was actually requested - by then a brand-new,
     * completely unrelated task may already have replaced [_currentPlan] via
     * [startNewPlan]. Without this check, cancelPlan() would happily relabel
     * that new plan CANCELLED (the terminal-status guard above does NOT catch
     * this - a fresh plan starts RUNNING, not terminal) purely because it
     * happened to be whatever was "current" when this coroutine got around to
     * running. Passing null keeps the old "cancel whatever is current"
     * behaviour for any caller that genuinely means that.
     */
    suspend fun cancelPlan(expectedPlanId: String? = null) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            if (plan.status.isTerminal()) return@withLock
            if (expectedPlanId != null && plan.planId != expectedPlanId) return@withLock
            val updatedPlan = PlanPrivacy.redactPlan(plan.copy(status = PlanStatus.CANCELLED))
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = null
            saveCurrentPlanLocked()
        }
    }

    /**
     * Updates the description of a step that has not started executing yet.
     * No-op if the step cannot be found or is no longer PENDING, so an
     * in-flight or already-finished step can never be mutated out from
     * under the executor.
     */
    suspend fun updateStepDescription(stepId: String, description: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Replaces the params map of a step that has not started executing yet.
     * No-op if the step cannot be found or is no longer PENDING. Executing
     * this while the plan is RUNNING lets executePlanLoop pick up the new
     * params the next time it reads this step via getActiveStep().
     */
    suspend fun updateStepParams(stepId: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Atomically updates both the description and params of a step that has not started
     * executing yet, in a single mutex-guarded read-modify-write. Prefer this over calling
     * [updateStepDescription] then [updateStepParams] back to back: those are two
     * independent suspend calls, so a concurrent reader (executePlanLoop's
     * [getStepSnapshot], or anything else observing [currentPlan]) can land between them
     * and see the new description paired with the still-old params - a half-applied edit
     * that then gets persisted and executed as if it were what the user asked for. No-op
     * if the step cannot be found or is no longer PENDING.
     */
    suspend fun updateStep(stepId: String, description: String, params: Map<String, String>) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            val updatedSteps = plan.steps.map { step ->
                if (step.stepId == stepId) step.copy(description = description, params = params) else step
            }
            val updatedPlan = plan.copy(steps = updatedSteps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Removes a step that has not started executing yet from the current
     * plan. No-op if the step cannot be found or is no longer PENDING.
     */
    suspend fun removeStep(stepId: String) {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            val targetStep = plan.steps.find { it.stepId == stepId } ?: return@withLock
            if (targetStep.status != StepStatus.PENDING) return@withLock
            // Other steps can echo a removed sensitive step's value, and the goal can hold it.
            val updatedPlan = PlanPrivacy.forgetStepSecrets(plan, stepId).let { forgotten ->
                forgotten.copy(steps = forgotten.steps.filterNot { it.stepId == stepId })
            }
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * Re-reads a single step's current description/params/action directly from the
     * source of truth. Meant to be called by executePlanLoop immediately before it
     * dispatches a step, to close the window between its earlier (unguarded)
     * [getActiveStep] snapshot and the actual dispatch - the Plan-tab step editor
     * (updateStepDescription then updateStepParams) can land in that window, and
     * without this re-read its edit would be persisted to the DB yet silently ignored
     * by the execution already in flight.
     *
     * Mutex-guarded like the mutators above, so it always observes either a fully
     * applied edit or none of it, never a half-applied intermediate write. Must NOT be
     * called from within a block that already holds [mutex] - see its doc comment -
     * that would deadlock.
     */
    suspend fun getStepSnapshot(stepId: String): PlanStep? {
        mutex.withLock {
            return _currentPlan.value?.steps?.find { it.stepId == stepId }
        }
    }

    /**
     * Not mutex-guarded: this only takes a single snapshot of the
     * StateFlow's current value and reads from that local, immutable [Plan]
     * (steps is a List<PlanStep> of immutable data classes), so there is no
     * read-modify-write here to race - just one atomic read of a reference
     * that can never be torn.
     */
    fun getActiveStep(): PlanStep? {
        val plan = _currentPlan.value ?: return null
        if (plan.status != PlanStatus.RUNNING) return null
        // Return the first pending step that doesn't have pending/running dependencies
        return plan.steps.firstOrNull { step ->
            step.status == StepStatus.PENDING && hasDependenciesMet(step, plan.steps)
        }
    }

    /**
     * Fails every pending step that can no longer run because something it depends on
     * failed, following the chain, so a blocked step ends FAILED instead of sitting
     * PENDING in a finished plan. Unrelated steps stay runnable.
     */
    suspend fun failBlockedSteps() {
        mutex.withLock {
            val plan = _currentPlan.value ?: return@withLock
            if (plan.status != PlanStatus.RUNNING) return@withLock
            var steps = plan.steps
            while (true) {
                val blocked = steps.firstNotNullOfOrNull { step ->
                    if (step.status != StepStatus.PENDING) return@firstNotNullOfOrNull null
                    step.dependsOn
                        .firstOrNull { depId -> steps.any { it.stepId == depId && it.status == StepStatus.FAILED } }
                        ?.let { failedDepId -> step.stepId to failedDepId }
                } ?: break
                steps = steps.map { step ->
                    if (step.stepId == blocked.first) {
                        step.copy(status = StepStatus.FAILED, error = "Skipped: depends on failed step ${blocked.second}")
                    } else {
                        step
                    }
                }
            }
            if (steps == plan.steps) return@withLock
            val updatedPlan = plan.copy(steps = steps)
            _currentPlan.value = updatedPlan
            workingMemory.activePlan = updatedPlan
            saveCurrentPlanLocked()
        }
    }

    /**
     * A plan in one of these statuses is done: nothing may ever relabel it into a
     * different status again. See [cancelPlan].
     */
    private fun PlanStatus.isTerminal(): Boolean =
        this == PlanStatus.COMPLETED || this == PlanStatus.FAILED || this == PlanStatus.CANCELLED

    private fun hasDependenciesMet(step: PlanStep, allSteps: List<PlanStep>): Boolean {
        if (step.dependsOn.isEmpty()) return true
        // A FAILED dependency is not met: the step may need its result, or its consent
        // when the dependency was a confirmation. See failBlockedSteps.
        return step.dependsOn.all { depId ->
            val depStep = allSteps.find { it.stepId == depId }
            depStep != null && depStep.status == StepStatus.COMPLETED
        }
    }
}
