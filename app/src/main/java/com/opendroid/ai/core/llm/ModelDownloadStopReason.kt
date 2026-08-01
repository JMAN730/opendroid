package com.opendroid.ai.core.llm

import androidx.work.WorkInfo

/** Human-readable labels for every WorkManager stop reason available in 2.10.x. */
internal object ModelDownloadStopReason {

    fun label(reason: Int): String = when (reason) {
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not-stopped"
        WorkInfo.STOP_REASON_UNKNOWN -> "unknown"
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled-by-app"
        WorkInfo.STOP_REASON_PREEMPT -> "preempted"
        WorkInfo.STOP_REASON_TIMEOUT -> "timeout"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "device-state"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "battery-not-low-constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "charging-constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "network-constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "device-idle-constraint"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "storage-not-low-constraint"
        WorkInfo.STOP_REASON_QUOTA -> "quota"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "background-restriction"
        WorkInfo.STOP_REASON_APP_STANDBY -> "app-standby"
        WorkInfo.STOP_REASON_USER -> "user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "system-processing"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED ->
            "estimated-app-launch-time-changed"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "foreground-service-timeout"
        else -> "unrecognized"
    }
}
