package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.crash.CrashLogRecord
import com.opendroid.ai.core.crash.CrashReportExporter
import com.opendroid.ai.data.crash.toRecord
import com.opendroid.ai.data.db.dao.CrashLogDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One row of the crash list: the storage-agnostic crash plus the stable row id
 * the list needs for keying and expansion. Keeps the Room entity out of the UI.
 */
data class CrashLogItem(
    val id: Long,
    val record: CrashLogRecord
)

@HiltViewModel
class CrashLogViewModel @Inject constructor(
    private val crashLogDao: CrashLogDao
) : ViewModel() {

    val crashes: StateFlow<List<CrashLogItem>> = crashLogDao.getAllFlow()
        .map { entities -> entities.map { CrashLogItem(it.id, it.toRecord()) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun clearAll() {
        viewModelScope.launch { crashLogDao.clearAll() }
    }

    /** Plain-text render of a single crash, for share and copy. */
    fun exportOne(crash: CrashLogRecord): String =
        CrashReportExporter.exportOne(crash)

    /**
     * Plain-text render of the whole log. Reads through the DAO rather than the
     * Compose snapshot so the share always reflects committed state.
     */
    fun exportAll(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val records = crashLogDao.getAll().map { it.toRecord() }
            onReady(CrashReportExporter.export(records))
        }
    }
}
