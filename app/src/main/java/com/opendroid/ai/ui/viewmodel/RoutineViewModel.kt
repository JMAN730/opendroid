package com.opendroid.ai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.data.db.dao.HabitDao
import com.opendroid.ai.data.models.HabitEvent
import com.opendroid.ai.data.models.HabitRoutine
import com.opendroid.ai.data.models.PlanStep
import com.opendroid.ai.data.models.RoutineStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Both sections of the Routines screen, always taken from the same database read. */
data class RoutinesUiState(
    val suggested: List<HabitRoutine> = emptyList(),
    val active: List<HabitRoutine> = emptyList()
)

/**
 * Splits one snapshot of every routine into the screen's two sections, so a routine can never
 * sit in both while its status changes. Orders match the DAO's suggested and active queries.
 */
internal fun partitionRoutines(all: List<HabitRoutine>): RoutinesUiState = RoutinesUiState(
    suggested = all.filter { it.status == RoutineStatus.SUGGESTED }
        .sortedWith(compareByDescending<HabitRoutine> { it.confidence }.thenByDescending { it.lastDetectedAt }),
    active = all.filter { it.status in ACTIVE_SECTION_STATUSES }
        .sortedByDescending { it.lastDetectedAt }
)

// PAUSED stays listed so a routine the user switched off can be switched back on or deleted
private val ACTIVE_SECTION_STATUSES = setOf(RoutineStatus.APPROVED, RoutineStatus.ACTIVE, RoutineStatus.PAUSED)

@HiltViewModel
class RoutineViewModel @Inject constructor(
    private val habitRoutineEngine: HabitRoutineEngine,
    private val habitDao: HabitDao
) : ViewModel() {

    // One flow for both sections: two separate Room queries can publish an approved routine
    // in the active list before the suggested list drops it, which duplicates its LazyColumn key.
    val routinesUiState: StateFlow<RoutinesUiState> = habitRoutineEngine.allRoutinesFlow
        .map(::partitionRoutines)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RoutinesUiState())

    val allRoutines: StateFlow<List<HabitRoutine>> = habitRoutineEngine.allRoutinesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentEvents: StateFlow<List<HabitEvent>> = habitRoutineEngine.recentEventsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun approveRoutine(routineId: String) {
        viewModelScope.launch {
            habitRoutineEngine.approveRoutine(routineId)
        }
    }

    fun dismissRoutine(routineId: String) {
        viewModelScope.launch {
            habitRoutineEngine.dismissRoutine(routineId)
        }
    }

    fun toggleRoutine(routineId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            habitRoutineEngine.toggleRoutine(routineId, isEnabled)
        }
    }

    fun executeRoutine(routineId: String, context: Context, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        viewModelScope.launch {
            val result = habitRoutineEngine.executeRoutine(routineId, context)
            onResult(result.success, result.data ?: result.error)
        }
    }

    fun triggerDetection() {
        viewModelScope.launch {
            habitRoutineEngine.detectRoutines(lookbackDays = 14, minRepetitions = 2)
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch {
            habitDao.deleteRoutine(routineId)
        }
    }
}
