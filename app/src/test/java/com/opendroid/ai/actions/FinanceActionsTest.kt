package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.isUserFacingStepError
import com.opendroid.ai.core.agent.selectUserFacingFailure
import com.opendroid.ai.core.agent.stopsPlanAfterUserHandOff
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
    fun `gpay hands the UPI payment to Google Pay without claiming it was sent`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = payUpi(context, app = "gpay", to = "alice@okaxis", amount = "500", note = "Lunch")

        assertEquals(
            ActionResult.UserActionRequired(
                "I opened Google Pay with \u20B9500 to alice@okaxis. " +
                    "Confirm and enter your UPI PIN there; I can't see whether it went through."
            ),
            result
        )
        assertEquals(GPAY_PACKAGE, context.startedIntent?.`package`)
        val uri = context.startedIntent?.data
        assertEquals("upi", uri?.scheme)
        assertEquals("pay", uri?.host)
        assertEquals("alice@okaxis", uri?.getQueryParameter("pa"))
        assertEquals("Recipient", uri?.getQueryParameter("pn"))
        assertEquals("Lunch", uri?.getQueryParameter("tn"))
        assertEquals("500", uri?.getQueryParameter("am"))
        assertEquals("INR", uri?.getQueryParameter("cu"))
    }

    @Test
    fun `phonepe hands the UPI payment to PhonePe`() = runBlocking {
        val context = recordingContext(PHONEPE_PACKAGE)

        val result = payUpi(context, app = "phonepe")

        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(result.error!!.startsWith("I opened PhonePe with \u20B9100 to alice@upi."))
        assertEquals(PHONEPE_PACKAGE, context.startedIntent?.`package`)
    }

    @Test
    fun `paytm hands the UPI payment to Paytm`() = runBlocking {
        val context = recordingContext(PAYTM_PACKAGE)

        val result = payUpi(context, app = "paytm")

        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(result.error!!.startsWith("I opened Paytm with \u20B9100 to alice@upi."))
        assertEquals(PAYTM_PACKAGE, context.startedIntent?.`package`)
    }

    @Test
    fun `the payment hand-off reaches the user and stops the plan`() = runBlocking {
        val result = payUpi(recordingContext(GPAY_PACKAGE))

        // The plan summary only repeats a failed step's error when this accepts it.
        assertTrue(isUserFacingStepError(result.error!!))
        assertTrue(stopsPlanAfterUserHandOff("PAY_UPI"))
        assertFalse(stopsPlanAfterUserHandOff("SEND_EMAIL"))
    }

    @Test
    fun `the payment hand-off is spoken ahead of an earlier step's hand-off`() = runBlocking {
        val payment = payUpi(recordingContext(GPAY_PACKAGE)).error!!
        val earlier = "I've opened Telegram - please tap send."

        assertEquals(payment, selectUserFacingFailure(payment, listOf(earlier, payment)))
        assertEquals(earlier, selectUserFacingFailure(null, listOf(earlier, payment)))
    }

    @Test
    fun `contact name asks for the UPI ID and starts nothing`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = payUpi(context, to = "John")

        assertEquals(
            ActionResult.NeedsInput("What's John's UPI ID?", metadata = mapOf("param" to "to")),
            result
        )
        assertNull(context.startedIntent)
    }

    @Test
    fun `recipients that are not UPI IDs ask for one and start nothing`() = runBlocking {
        val notVpas = listOf(
            "9876543210",
            "john@gmail.com",
            "x@upi&am=5000",
            "x@upi?am=5000",
            "x y@upi",
            "@upi",
            "alice@",
            " "
        )
        notVpas.forEach { to ->
            val context = recordingContext(GPAY_PACKAGE)

            val result = payUpi(context, to = to)

            assertEquals(
                to,
                ActionResult.NeedsInput(
                    "What's the recipient's UPI ID? It looks like name@bank.",
                    metadata = mapOf("param" to "to")
                ),
                result
            )
            assertNull(to, context.startedIntent)
        }
    }

    @Test
    fun `UPI IDs with digits, dots and dashes are accepted`() = runBlocking {
        listOf("9876543210@ybl", "john.doe-1_a@kotak811").forEach { to ->
            val context = recordingContext(GPAY_PACKAGE)

            val result = payUpi(context, to = to)

            assertTrue(to, result is ActionResult.UserActionRequired)
            assertEquals(to, context.startedIntent?.data?.getQueryParameter("pa"))
        }
    }

    @Test
    fun `amounts that are not plain numbers ask again and start nothing`() = runBlocking {
        val notAmounts = listOf(
            "Total: 100 divided among 4 people. Individual Share: 25.00 per person.",
            "500&am=1",
            "-500",
            "1,000",
            "12.345",
            "\u0665\u0660\u0660",
            ""
        )
        notAmounts.forEach { amount ->
            val context = recordingContext(GPAY_PACKAGE)

            val result = payUpi(context, amount = amount)

            assertEquals(
                amount,
                ActionResult.NeedsInput(
                    "How much should I send to alice@upi? Use a number like 500 or 500.50.",
                    metadata = mapOf("param" to "amount")
                ),
                result
            )
            assertNull(amount, context.startedIntent)
        }
    }

    @Test
    fun `amount with paise is passed through`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        val result = payUpi(context, amount = "500.50")

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals("500.50", context.startedIntent?.data?.getQueryParameter("am"))
    }

    @Test
    fun `a note cannot add parameters to the payment URI`() = runBlocking {
        val context = recordingContext(GPAY_PACKAGE)

        payUpi(context, amount = "500", note = "Lunch & drinks&am=5000")

        val uri = context.startedIntent?.data
        assertEquals("Lunch & drinks&am=5000", uri?.getQueryParameter("tn"))
        assertEquals(listOf("500"), uri?.getQueryParameters("am"))
    }

    @Test
    fun `PAY_UPI schema stops offering contact names as recipients`() {
        val definition = ActionSchema.getAction("PAY_UPI")!!
        val to = definition.params.single { it.name == "to" }

        assertFalse(to.description, to.description.contains("contact", ignoreCase = true))
        assertTrue(to.description, to.description.contains("name@bank"))
        assertTrue(definition.examples.toString(), definition.examples.all { it.contains("@") })
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

        assertEquals(
            ActionResult.UserActionRequired(
                "PhonePe isn't installed, so I opened your default payment app with \u20B9100 to alice@upi. " +
                    "Confirm and enter your UPI PIN there; I can't see whether it went through."
            ),
            result
        )
        assertEquals(Intent.ACTION_VIEW, context.startedIntent?.action)
        assertNull(context.startedIntent?.`package`)
        val uri = context.startedIntent?.data
        assertEquals("alice@upi", uri?.getQueryParameter("pa"))
        assertEquals("100", uri?.getQueryParameter("am"))
        assertEquals("Sent via OpenDroid", uri?.getQueryParameter("tn"))
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
