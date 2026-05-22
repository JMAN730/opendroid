package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.db.dao.TaskHistoryDao
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val taskHistoryDao: TaskHistoryDao,
    private val unknownActionDao: UnknownActionDao
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
}
