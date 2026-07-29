package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.crash.CrashReportExporter
import com.opendroid.ai.data.crash.toRecord
import com.opendroid.ai.data.db.dao.CrashLogDao
import com.opendroid.ai.data.db.entities.CrashLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CrashLogViewModel @Inject constructor(
    private val crashLogDao: CrashLogDao
) : ViewModel() {

    val crashes: Flow<List<CrashLogEntity>> = crashLogDao.getAllFlow()

    fun clearAll() {
        viewModelScope.launch { crashLogDao.clearAll() }
    }

    /** Plain-text render of a single crash, for share and copy. */
    fun exportOne(crash: CrashLogEntity): String =
        CrashReportExporter.exportOne(crash.toRecord())

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
