package com.opendroid.ai.actions

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallFlowExecutorTest {

    @Test
    fun `direct call is successful only after a new call is verified`() = runBlocking {
        val context = recordingContext(grantCallPermission = true)
        val verifier = FakeCallFlowVerifier(callStarted = true)

        val result = CallFlowExecutor(verifier).execute("+1 (555) 010-1234", context)

        assertTrue(result.success)
        assertEquals(Intent.ACTION_CALL, context.startedIntent?.action)
        assertFalse(verifier.wasAlreadyInProgress)
    }

    @Test
    fun `dialer fallback remains pending user action`() = runBlocking {
        val context = recordingContext(grantCallPermission = false)

        val result = CallFlowExecutor(FakeCallFlowVerifier(callStarted = true)).execute("5550100", context)

        assertTrue(result is ActionResult.PendingUserAction)
        assertFalse(result.success)
        assertEquals(Intent.ACTION_DIAL, context.startedIntent?.action)
    }

    @Test
    fun `launch failure is not reported as a successful call`() = runBlocking {
        val context = recordingContext(grantCallPermission = true, failToLaunch = true)

        val result = CallFlowExecutor(FakeCallFlowVerifier(callStarted = true)).execute("5550100", context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
    }

    @Test
    fun `verification timeout is not reported as a successful call`() = runBlocking {
        val context = recordingContext(grantCallPermission = true)

        val result = CallFlowExecutor(FakeCallFlowVerifier(callStarted = false)).execute("5550100", context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals(Intent.ACTION_CALL, context.startedIntent?.action)
    }

    private fun recordingContext(
        grantCallPermission: Boolean,
        failToLaunch: Boolean = false
    ): RecordingContext {
        val base = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(base.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING, true)
        return RecordingContext(base, grantCallPermission, failToLaunch)
    }

    private class RecordingContext(
        base: Context,
        private val grantCallPermission: Boolean,
        private val failToLaunch: Boolean
    ) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun checkSelfPermission(permission: String): Int = when {
            permission == Manifest.permission.CALL_PHONE && grantCallPermission ->
                PackageManager.PERMISSION_GRANTED
            else -> PackageManager.PERMISSION_DENIED
        }

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            checkSelfPermission(permission)

        override fun startActivity(intent: Intent) {
            if (failToLaunch) error("synthetic launch failure")
            startedIntent = intent
        }
    }

    private class FakeCallFlowVerifier(
        private val callStarted: Boolean
    ) : CallFlowVerifier {
        var wasAlreadyInProgress = false

        override fun isCallInProgress(context: Context): Boolean = false

        override suspend fun awaitNewCallStarted(
            context: Context,
            wasAlreadyInProgress: Boolean
        ): Boolean {
            this.wasAlreadyInProgress = wasAlreadyInProgress
            return callStarted
        }
    }
}
