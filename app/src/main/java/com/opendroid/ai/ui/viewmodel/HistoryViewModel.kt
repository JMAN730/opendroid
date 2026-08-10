package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.core.memory.MacroRecording
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskHistoryDao: TaskHistoryDao,
    private val unknownActionDao: UnknownActionDao,
    private val macroDao: MacroDao
) : ViewModel() {

    val taskHistory: StateFlow<List<TaskHistoryEntity>> = taskHistoryDao.getTaskHistoryFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val unknownActions: StateFlow<List<UnknownActionEntity>> = unknownActionDao.getAllUnknownActions()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearTaskHistory() {
        viewModelScope.launch {
            taskHistoryDao.clearAll()
        }
    }

    fun clearUnknownActions() {
        viewModelScope.launch {
            unknownActionDao.clearAll()
        }
    }

    /** Saves only a fully successful, explicitly selected task as a manual macro. */
    fun saveCompletedTaskAsMacro(
        planId: String,
        name: String,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val trimmedName = name.trim()
                if (trimmedName.isEmpty()) {
                    onResult("Macro name cannot be empty.")
                    return@launch
                }

                val history = taskHistoryDao.getTaskHistoryForPlan(planId)
                val stepsJson = MacroRecording.serializeCompletedTask(history)
                if (stepsJson == null) {
                    onResult("Only fully successful task history can be saved as a macro.")
                    return@launch
                }

                macroDao.insertMacro(
                    MacroEntity(
                        id = UUID.randomUUID().toString(),
                        name = trimmedName,
                        trigger = "manual",
                        stepsJson = stepsJson,
                        isSystem = false,
                        isEnabled = true
                    )
                )
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onResult("Couldn't save that macro right now.")
            }
        }
    }
}
