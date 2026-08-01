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
import androidx.work.impl.foreground.SystemForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

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
    fun `foreground info uses an ongoing data sync notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val foregroundInfo = ModelDownloadForegroundInfoFactory.create(context)
        val notification = foregroundInfo.notification
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = manager.getNotificationChannel(ModelDownloadForegroundInfoFactory.CHANNEL_ID)

        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundInfo.foregroundServiceType
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

        assertEquals(0, ModelDownloadForegroundInfoFactory.create(context).foregroundServiceType)
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
