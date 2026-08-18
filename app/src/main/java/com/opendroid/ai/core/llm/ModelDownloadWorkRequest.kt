package com.opendroid.ai.core.llm

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import java.util.concurrent.TimeUnit

/** Schedules each model transfer with the constraints needed for safe resumable downloads. */
internal object ModelDownloadWorkRequest {

    internal const val RETRY_BACKOFF_SECONDS = 30L

    fun create(inputData: Data, modelId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // Multi-GB downloads are allowed over connected networks (cellular or Wi-Fi).
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .setInputData(inputData)
            .addTag("download_$modelId")
            .build()
}
