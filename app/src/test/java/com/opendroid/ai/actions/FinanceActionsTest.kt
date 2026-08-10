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
import org.junit.Assert.assertNull
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
    fun `gpay opens the UPI payment flow in Google Pay`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = payUpi(context, app = "gpay", note = "Lunch")

        assertTrue(result.success)
        assertEquals(GPAY_PACKAGE, context.startedIntent?.`package`)
        assertEquals(
            "upi://pay?pa=alice@upi&pn=Recipient&tn=Lunch&am=100&cu=INR",
            context.startedIntent?.dataString
        )
        assertEquals("Sending \u20B9100 to alice@upi!", result.data)
    }

    @Test
    fun `phonepe opens the UPI payment flow in PhonePe`() = runBlocking {
        val context = recordingContext(PHONEPE_PACKAGE)

        val result = payUpi(context, app = "phonepe")

        assertTrue(result.success)
        assertEquals(PHONEPE_PACKAGE, context.startedIntent?.`package`)
        assertEquals("Sending \u20B9100 to alice@upi!", result.data)
    }

    @Test
    fun `paytm opens the UPI payment flow in Paytm`() = runBlocking {
        val context = recordingContext(PAYTM_PACKAGE)

        val result = payUpi(context, app = "paytm")

        assertTrue(result.success)
        assertEquals(PAYTM_PACKAGE, context.startedIntent?.`package`)
        assertEquals("Sending \u20B9100 to alice@upi!", result.data)
    }

    @Test
    fun `missing UPI recipient reports a failure without launching an app`() = runBlocking {
        val context = recordingContext()

        val result = paymentAction().execute(mapOf("amount" to "100"), context)

        assertFalse(result.success)
        assertEquals("to UPI ID parameter is missing", result.error)
        assertNull(context.startedIntent)
    }

    @Test
    fun `missing UPI amount reports a failure without launching an app`() = runBlocking {
        val context = recordingContext()

        val result = paymentAction().execute(mapOf("to" to "alice@upi"), context)

        assertFalse(result.success)
        assertEquals("amount parameter is missing", result.error)
        assertNull(context.startedIntent)
    }

    @Test
    fun `missing payment app opens the default payment chooser`() = runBlocking {
        val context = recordingContext()

        val result = payUpi(context, app = "phonepe")

        assertTrue(result.success)
        assertEquals("phonepe isn't installed, so I opened the default payment app.", result.data)
        assertEquals(Intent.ACTION_VIEW, context.startedIntent?.action)
        assertNull(context.startedIntent?.`package`)
        assertEquals(
            "upi://pay?pa=alice@upi&pn=Recipient&tn=Sent+via+OpenDroid&am=100&cu=INR",
            context.startedIntent?.dataString
        )
    }

    @Test
    fun `payment launch failure remains a failure`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE, failToStartActivity = true)

        val result = payUpi(context)

        assertFalse(result.success)
        assertEquals("Payment didn't go through. Try again?", result.error)
        assertNull(context.startedIntent)
    }

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

    @Test
    fun `balance launch failure remains a failure`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE, failToStartActivity = true)

        val result = checkBalance(context)

        assertFalse(result.success)
        assertEquals("Couldn't check the balance right now.", result.error)
        assertNull(context.startedIntent)
    }

    @Test
    fun `numeric split bill returns the per-person summary`() = runBlocking {
        val result = splitBill(
            totalAmount = "100",
            people = "4",
            description = "Dinner"
        )

        assertTrue(result.success)
        assertEquals(
            "Total: 100 divided among 4 people. Individual Share: 25.00 per person. Event: 'Dinner'",
            result.data
        )
    }

    @Test
    fun `split bill accepts a people count phrase`() = runBlocking {
        val result = splitBill(totalAmount = "100", people = "4 people")

        assertTrue(result.success)
        assertEquals(
            "Total: 100 divided among 4 people. Individual Share: 25.00 per person. Event: 'Split Bill'",
            result.data
        )
    }

    @Test
    fun `split bill accepts comma separated names`() = runBlocking {
        val result = splitBill(
            totalAmount = "90",
            people = "Alice, Bob, Carol",
            description = "Lunch"
        )

        assertTrue(result.success)
        assertEquals(
            "Total: 90 divided among 3 people (Alice, Bob, Carol). " +
                "Individual Share: 30.00 per person. Event: 'Lunch'",
            result.data
        )
    }

    @Test
    fun `zero people reports a truthful failure`() = runBlocking {
        val result = splitBill(totalAmount = "100", people = "0")

        assertFalse(result.success)
        assertEquals("No people specified to split the bill with.", result.error)
    }

    @Test
    fun `blank people input reports a truthful failure`() = runBlocking {
        val result = splitBill(totalAmount = "100", people = " ")

        assertFalse(result.success)
        assertEquals("No people specified to split the bill with.", result.error)
    }

    private suspend fun checkBalance(
        context: RecordingContext,
        app: String? = null
    ) = action("CHECK_BALANCE")
        .execute(app?.let { mapOf("app" to it) }.orEmpty(), context)

    private suspend fun payUpi(
        context: RecordingContext,
        app: String = "gpay",
        to: String = "alice@upi",
        amount: String = "100",
        note: String = "Sent via OpenDroid"
    ) = paymentAction().execute(
        mapOf("to" to to, "amount" to amount, "note" to note, "app" to app),
        context
    )

    private suspend fun splitBill(
        totalAmount: String,
        people: String,
        description: String = "Split Bill"
    ) = action("SPLIT_BILL").execute(
        mapOf("totalAmount" to totalAmount, "people" to people, "description" to description),
        recordingContext()
    )

    private fun paymentAction() = action("PAY_UPI")

    private fun action(name: String) = FinanceActions()
        .getActions()
        .single { it.name == name }

    private fun recordingContext(
        vararg installedPackages: String,
        failToStartActivity: Boolean = false
    ): RecordingContext {
        val base = ApplicationProvider.getApplicationContext<Context>()
        installedPackages.forEach { installPaymentApp(it, base) }
        return RecordingContext(base, failToStartActivity)
    }

    @Suppress("DEPRECATION")
    private fun installPaymentApp(packageName: String, context: Context) {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("upi")
            }
        )
    }

    private class RecordingContext(
        base: Context,
        private val failToStartActivity: Boolean = false
    ) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent) {
            if (failToStartActivity) {
                throw IllegalStateException("activity launch failed")
            }
            startedIntent = intent
        }
    }

    private companion object {
        const val GPAY_PACKAGE = "com.google.android.apps.nbu.paisa.user"
        const val PHONEPE_PACKAGE = "com.phonepe.app"
        const val PAYTM_PACKAGE = "net.one97.paytm"
    }
}
