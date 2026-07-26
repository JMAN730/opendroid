package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.agent.AgentLoop
import com.opendroid.ai.core.agent.PlanManager
import com.opendroid.ai.data.models.Plan
import com.opendroid.ai.data.repository.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlanViewModel @Inject constructor(
    private val planManager: PlanManager,
    private val planRepository: PlanRepository,
    private val agentLoop: AgentLoop
) : ViewModel() {

    val currentPlan: StateFlow<Plan?> = planManager.currentPlan

    val planHistory: StateFlow<List<Plan>> = planRepository.allPlans
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun loadPlan(planId: String) {
        viewModelScope.launch {
            planManager.loadPlan(planId)
        }
    }

    fun deletePlan(planId: String) {
        viewModelScope.launch {
            planRepository.deletePlan(planId)
        }
    }

    /**
     * Cancels whatever the agent is currently doing (a running plan or an
     * in-flight query) without touching conversation history. Backs the
     * Plan tab's stop control - the Composable never talks to AgentLoop
     * directly.
     */
    fun stopTask() {
        agentLoop.cancelCurrentTask()
    }

    /**
     * Edits the description and params of a step that has not started
     * executing yet. Persisted atomically through PlanManager.updateStep,
     * which no-ops if the step is no longer PENDING by the time this runs.
     * Must not be split into separate updateStepDescription/updateStepParams
     * calls - each acquires PlanManager's mutex independently, leaving a
     * window where a concurrent reader (e.g. executePlanLoop's
     * getStepSnapshot) can observe the new description paired with the old
     * params.
     */
    fun editStep(stepId: String, description: String, params: Map<String, String>) {
        viewModelScope.launch {
            planManager.updateStep(stepId, description, params)
        }
    }

    /**
     * Removes a step that has not started executing yet from the current
     * plan. Persisted through PlanManager, which no-ops if the step is no
     * longer PENDING by the time this runs.
     */
    fun deleteStep(stepId: String) {
        viewModelScope.launch {
            planManager.removeStep(stepId)
        }
    }
}
