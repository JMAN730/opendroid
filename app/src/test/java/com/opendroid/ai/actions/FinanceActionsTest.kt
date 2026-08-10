package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FinanceActionsTest {

    @Test
    fun `missing app opens Google Pay`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = checkBalance(context)

        assertTrue(result.success)
        assertEquals(GPAY_PACKAGE, context.startedIntent?.component?.packageName)
        assertEquals(
            "I opened Google Pay — you'll need to check your balance there with your PIN.",
            result.data
        )
    }

    @Test
    fun `gpay opens Google Pay`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = checkBalance(context, "gpay")

        assertTrue(result.success)
        assertEquals(GPAY_PACKAGE, context.startedIntent?.component?.packageName)
        assertEquals(
            "I opened Google Pay — you'll need to check your balance there with your PIN.",
            result.data
        )
    }

    @Test
    fun `phonepe opens PhonePe`() = runBlocking {
        val context = recordingContext(PHONEPE_PACKAGE)

        val result = checkBalance(context, "phonepe")

        assertTrue(result.success)
        assertEquals(PHONEPE_PACKAGE, context.startedIntent?.component?.packageName)
        assertEquals(
            "I opened PhonePe — you'll need to check your balance there with your PIN.",
            result.data
        )
    }

    @Test
    fun `paytm opens Paytm`() = runBlocking {
        val context = recordingContext(PAYTM_PACKAGE)

        val result = checkBalance(context, "paytm")

        assertTrue(result.success)
        assertEquals(PAYTM_PACKAGE, context.startedIntent?.component?.packageName)
        assertEquals(
            "I opened Paytm — you'll need to check your balance there with your PIN.",
            result.data
        )
    }

    @Test
    fun `missing Google Pay reports the default app fallback`() = runBlocking {
        val context = recordingContext()

        val result = checkBalance(context)

        assertFalse(result.success)
        assertEquals(null, context.startedIntent)
        assertEquals("Google Pay isn't installed. Check your balance in your banking app.", result.error)
    }

    @Test
    fun `missing PhonePe reports the selected app fallback`() = runBlocking {
        val context = recordingContext()

        val result = checkBalance(context, "phonepe")

        assertFalse(result.success)
        assertEquals(null, context.startedIntent)
        assertEquals("PhonePe isn't installed. Check your balance in your banking app.", result.error)
    }

    @Test
    fun `missing Paytm reports the selected app fallback`() = runBlocking {
        val context = recordingContext()

        val result = checkBalance(context, "paytm")

        assertFalse(result.success)
        assertEquals(null, context.startedIntent)
        assertEquals("Paytm isn't installed. Check your balance in your banking app.", result.error)
    }

    private suspend fun checkBalance(
        context: RecordingContext,
        app: String? = null
    ) = FinanceActions()
        .getActions()
        .single { it.name == "CHECK_BALANCE" }
        .execute(app?.let { mapOf("app" to it) }.orEmpty(), context)

    private fun recordingContext(installedPackage: String? = null): RecordingContext {
        val base = ApplicationProvider.getApplicationContext<Context>()
        installedPackage?.let { installLauncher(it, base) }
        return RecordingContext(base)
    }

    @Suppress("DEPRECATION")
    private fun installLauncher(packageName: String, context: Context) {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent) {
            startedIntent = intent
        }
    }

    private companion object {
        const val GPAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"
        const val PHONEPE_PACKAGE = "com.phonepe.app"
        const val PAYTM_PACKAGE = "net.one97.paytm"
    }
}
