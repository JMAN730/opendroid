package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.memory.NotificationHistoryCleaner
import com.opendroid.ai.data.db.dao.NotificationDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    val notificationDao: NotificationDao,
    private val notificationHistoryCleaner: NotificationHistoryCleaner
) : ViewModel() {

    /** Clears the history and the patterns learned from it, so neither reaches a prompt. */
    fun clearAll() {
        viewModelScope.launch { notificationHistoryCleaner.clearHistory() }
    }
}
