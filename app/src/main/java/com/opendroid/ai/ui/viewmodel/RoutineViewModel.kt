package com.opendroid.ai.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.data.db.dao.HabitDao
import com.opendroid.ai.data.models.HabitEvent
import com.opendroid.ai.data.models.HabitRoutine
import com.opendroid.ai.data.models.PlanStep
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RoutineViewModel @Inject constructor(
    private val habitRoutineEngine: HabitRoutineEngine,
    private val habitDao: HabitDao
) : ViewModel() {

    val suggestedRoutines: StateFlow<List<HabitRoutine>> = habitRoutineEngine.suggestedRoutinesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeRoutines: StateFlow<List<HabitRoutine>> = habitRoutineEngine.activeRoutinesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
