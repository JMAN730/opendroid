package com.opendroid.ai.core.crash

/**
 * One recorded crash, in storage-agnostic form.
 *
 * Kept separate from the Room entity so the recording logic (and its tests) do
 * not have to drag in the database layer.
 */
data class CrashLogRecord(
    val timestamp: Long,
    val exceptionClass: String,
    val message: String?,
    val threadName: String,
    val stackTrace: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String
) {
    /** Single-line headline for the crash list. */
    val summary: String
        get() = if (message.isNullOrBlank()) exceptionClass else "$exceptionClass: $message"
}
