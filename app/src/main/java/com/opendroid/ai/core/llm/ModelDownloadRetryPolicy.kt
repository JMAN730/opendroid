package com.opendroid.ai.core.llm

import com.opendroid.ai.data.db.entities.ModelStatus
import java.io.IOException
import javax.net.ssl.SSLException

/** Classifies resumable model-download states and failures without weakening integrity checks. */
internal object ModelDownloadRetryPolicy {

    fun simulationStartProgress(status: ModelStatus?, progress: Int): Int = when (status) {
        ModelStatus.DOWNLOADING, ModelStatus.PAUSED -> progress.coerceIn(0, 100)
        else -> 0
    }

    fun isRetryableHttpStatus(statusCode: Int): Boolean =
        statusCode == 408 ||
            statusCode == 425 ||
            statusCode == 429 ||
            statusCode in 500..599

    fun isRetryableTransport(error: Throwable): Boolean =
        error is IOException && error !is SSLException
}
