package com.opendroid.ai.core.llm

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.impl.foreground.SystemForegroundService
import com.opendroid.ai.data.db.entities.ModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.net.ssl.SSLHandshakeException

/** Avoids OpenDroidApp because model-download scheduling does not need the Android Keystore. */
class ModelDownloadTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModelDownloadTestApplication::class)
class ModelDownloadSchedulingTest {

    @Test
    fun `model downloads wait for a connected network and retain retry input`() {
        val input = Data.Builder()
            .putString("model_id", "test-model")
            .putString("download_url", "https://example.test/model")
            .build()

        val request = ModelDownloadWorkRequest.create(input, "test-model")

        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(
            TimeUnit.SECONDS.toMillis(ModelDownloadWorkRequest.RETRY_BACKOFF_SECONDS),
            request.workSpec.backoffDelayDuration
        )
        assertEquals("test-model", request.workSpec.input.getString("model_id"))
        assertTrue(request.tags.contains("download_test-model"))
    }

    @Test
    fun `model downloads add no constraint that would strand a long transfer`() {
        val request = ModelDownloadWorkRequest.create(Data.EMPTY, "test-model")
        val constraints = request.workSpec.constraints

        // Only the connected-network requirement is intended; charging, idle,
        // battery and storage constraints would leave multi-GB transfers unrunnable.
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
        assertFalse(constraints.requiresBatteryNotLow())
        assertFalse(constraints.requiresStorageNotLow())
        assertFalse(constraints.hasContentUriTriggers())
    }

    @Test
    fun `model downloads run as regular work, never expedited quota work`() {
        val request = ModelDownloadWorkRequest.create(Data.EMPTY, "test-model")

        // Expedited work draws on the app's JobScheduler quota; a foreground
        // data-sync worker must not compete for it (see the Android 16 quota work).
        assertFalse(request.workSpec.expedited)
    }

    @Test
    fun `foreground info uses an ongoing data sync notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

        val foregroundInfo = ModelDownloadForegroundInfoFactory.create(context, workId)
        val notification = foregroundInfo.notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(ModelDownloadForegroundInfoFactory.CHANNEL_ID)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundInfo.foregroundServiceType
        )
        assertEquals(
            ModelDownloadForegroundInfoFactory.notificationIdFor(workId),
            foregroundInfo.notificationId
        )
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Model download in progress", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    @Test
    @Config(sdk = [28], application = ModelDownloadTestApplication::class)
    fun `foreground info omits service type before Android Q`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(
            0,
            ModelDownloadForegroundInfoFactory.create(context, UUID.randomUUID()).foregroundServiceType
        )
    }

    @Test
    fun `foreground notification ids are stable per work and distinct across downloads`() {
        val firstWorkId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val secondWorkId = UUID.fromString("123e4567-e89b-12d3-a456-426614174001")

        val firstNotificationId = ModelDownloadForegroundInfoFactory.notificationIdFor(firstWorkId)

        assertEquals(firstNotificationId, ModelDownloadForegroundInfoFactory.notificationIdFor(firstWorkId))
        assertNotEquals(firstNotificationId, ModelDownloadForegroundInfoFactory.notificationIdFor(secondWorkId))
    }

    @Test
    fun `retry policy retries transient transport and server failures only`() {
        assertTrue(ModelDownloadRetryPolicy.isRetryableTransport(UnknownHostException()))
        assertTrue(ModelDownloadRetryPolicy.isRetryableTransport(SocketTimeoutException()))
        assertFalse(ModelDownloadRetryPolicy.isRetryableTransport(SSLHandshakeException("bad certificate")))
        assertFalse(ModelDownloadRetryPolicy.isRetryableTransport(IllegalArgumentException()))

        listOf(408, 425, 429, 500, 503, 599).forEach { statusCode ->
            assertTrue("HTTP $statusCode should retry", ModelDownloadRetryPolicy.isRetryableHttpStatus(statusCode))
        }
        listOf(400, 401, 403, 404, 422).forEach { statusCode ->
            assertFalse("HTTP $statusCode should fail", ModelDownloadRetryPolicy.isRetryableHttpStatus(statusCode))
        }
    }

    @Test
    fun `simulation retries preserve only active download progress`() {
        assertEquals(
            47,
            ModelDownloadRetryPolicy.simulationStartProgress(ModelStatus.DOWNLOADING, 47)
        )
        assertEquals(
            47,
            ModelDownloadRetryPolicy.simulationStartProgress(ModelStatus.PAUSED, 47)
        )
        assertEquals(
            0,
            ModelDownloadRetryPolicy.simulationStartProgress(ModelStatus.FAILED, 47)
        )
    }

    @Test
    fun `every WorkManager stop reason has a diagnostic label`() {
        val expectedLabels = mapOf(
            WorkInfo.STOP_REASON_NOT_STOPPED to "not-stopped",
            WorkInfo.STOP_REASON_UNKNOWN to "unknown",
            WorkInfo.STOP_REASON_CANCELLED_BY_APP to "cancelled-by-app",
            WorkInfo.STOP_REASON_PREEMPT to "preempted",
            WorkInfo.STOP_REASON_TIMEOUT to "timeout",
            WorkInfo.STOP_REASON_DEVICE_STATE to "device-state",
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW to "battery-not-low-constraint",
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING to "charging-constraint",
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY to "network-constraint",
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE to "device-idle-constraint",
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW to "storage-not-low-constraint",
            WorkInfo.STOP_REASON_QUOTA to "quota",
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION to "background-restriction",
            WorkInfo.STOP_REASON_APP_STANDBY to "app-standby",
            WorkInfo.STOP_REASON_USER to "user",
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING to "system-processing",
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED to
                "estimated-app-launch-time-changed",
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT to "foreground-service-timeout"
        )

        expectedLabels.forEach { (reason, label) ->
            assertEquals(label, ModelDownloadStopReason.label(reason))
        }
    }

    @Test
    fun `merged WorkManager service declares data sync and permission`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, SystemForegroundService::class.java),
            PackageManager.GET_META_DATA
        )

        assertTrue(
            serviceInfo.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0
        )
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(
                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
                context.packageName
            )
        )
    }
}
