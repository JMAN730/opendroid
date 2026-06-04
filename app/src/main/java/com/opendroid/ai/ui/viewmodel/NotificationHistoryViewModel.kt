package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.opendroid.ai.data.db.dao.NotificationDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NotificationHistoryViewModel @Inject constructor(
    val notificationDao: NotificationDao
) : ViewModel()
