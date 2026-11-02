package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinanceActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        PayUpiAction(),
        CheckBalanceAction(),
        SplitBillAction()
    )

    private class PayUpiAction : Action {
        override val name: String = "PAY_UPI"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val to = params["to"]?.trim() ?: return ActionResult(false, null, "to UPI ID parameter is missing")
            val amount = params["amount"]?.trim() ?: return ActionResult(false, null, "amount parameter is missing")
            val note = params["note"] ?: "Sent via OpenDroid"
            val selectedApp = paymentApp(params["app"] ?: "gpay")

            if (!VPA_PATTERN.matches(to)) {
                val question = if (CONTACT_NAME_PATTERN.matches(to)) {
                    "What's $to's UPI ID?"
                } else {
                    "What's the recipient's UPI ID? It looks like name@bank."
                }
                return ActionResult.NeedsInput(question, metadata = mapOf("param" to "to"))
            }
            if (!AMOUNT_PATTERN.matches(amount)) {
                return ActionResult.NeedsInput(
                    "How much should I send to $to? Use a number like 500 or 500.50.",
                    metadata = mapOf("param" to "amount")
                )
            }

            return try {
                val upiUri = Uri.Builder()
                    .scheme("upi")
                    .authority("pay")
                    .appendQueryParameter("pa", to)
                    .appendQueryParameter("pn", "Recipient")
                    .appendQueryParameter("tn", note)
                    .appendQueryParameter("am", amount)
                    .appendQueryParameter("cu", "INR")
                    .build()
                val intent = Intent(Intent.ACTION_VIEW, upiUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                intent.setPackage(selectedApp.packageName)
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    // Started from a non-Activity context, so the UPI response can't be read.
                    ActionResult.UserActionRequired(
                        "I opened ${selectedApp.displayName} with ₹$amount to $to. $PAYMENT_HAND_OFF"
                    )
                } else {
                    // Try without setting package to allow chooser
                    val chooserIntent = Intent(Intent.ACTION_VIEW, upiUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooserIntent)
                    ActionResult.UserActionRequired(
                        "${selectedApp.displayName} isn't installed, so I opened your default payment app " +
                            "with ₹$amount to $to. $PAYMENT_HAND_OFF"
                    )
                }
            } catch (e: Exception) {
                Log.e("PayUPI", "UPI failed: ${e.localizedMessage}")
                ActionResult(false, null, "Payment didn't go through. Try again?")
            }
        }
    }

    private class CheckBalanceAction : Action {
        override val name: String = "CHECK_BALANCE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                // Cannot fetch balance programmatically due to bank NPCI/UPI pin constraints.
                // Open the selected UPI app for the user to authenticate and check balance.
                val selectedApp = paymentApp(params["app"] ?: "gpay")
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(selectedApp.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult(true, "I opened ${selectedApp.displayName} — you'll need to check your balance there with your PIN.", null, true)
                } else {
                    ActionResult(false, null, "${selectedApp.displayName} isn't installed. Check your balance in your banking app.")
                }
            } catch (e: Exception) {
                Log.e("CheckBalance", "Balance check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check the balance right now.")
            }
        }
    }

    private class SplitBillAction : Action {
        override val name: String = "SPLIT_BILL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val totalAmountStr = params["totalAmount"] ?: return ActionResult(false, null, "totalAmount parameter is missing")
            val peopleStr = params["people"] ?: return ActionResult(false, null, "people parameter is missing")
            val description = params["description"] ?: "Split Bill"
            
            return try {
                val total = totalAmountStr.toDouble()
                
                val parsedInt = peopleStr.trim().toIntOrNull()
                val (numPeople, peopleList) = if (parsedInt != null) {
                    Pair(parsedInt, (1..parsedInt).map { "Person $it" })
                } else {
                    // Try parsing "X people", "X friends", "X person", etc.
                    val regex = Regex("""^(\d+)\s+(?:people|person|persons|members|friends)$""", RegexOption.IGNORE_CASE)
                    val matchResult = regex.matchEntire(peopleStr.trim())
                    if (matchResult != null) {
                        val count = matchResult.groupValues[1].toInt()
                        Pair(count, (1..count).map { "Person $it" })
                    } else {
                        // Treat as a comma-separated list of names
                        val list = peopleStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        Pair(list.size, list)
                    }
                }
                
                if (numPeople <= 0) {
                    return ActionResult(false, null, "No people specified to split the bill with.")
                }
                
                val share = total / numPeople
                val formattedShare = String.format(Locale.getDefault(), "%.2f", share)
                
                val summary = if (peopleList.firstOrNull()?.startsWith("Person ") == true) {
                    "Total: $totalAmountStr divided among $numPeople people. " +
                    "Individual Share: $formattedShare per person. Event: '$description'"
                } else {
                    "Total: $totalAmountStr divided among $numPeople people (${peopleList.joinToString(", ")}). " +
                    "Individual Share: $formattedShare per person. Event: '$description'"
                }
                              
                ActionResult(true, summary, null)
            } catch (e: Exception) {
                Log.e("SplitBill", "Split failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't split the bill. Try again?")
            }
        }
    }
}

private const val PAYMENT_HAND_OFF = "Confirm and enter your UPI PIN there; I can't see whether it went through."

// [0-9], not \d: \d also matches non-ASCII digits on Android, and payment apps can't read those.
private val VPA_PATTERN = Regex("""[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}""")
private val AMOUNT_PATTERN = Regex("""[0-9]+(\.[0-9]{1,2})?""")
private val CONTACT_NAME_PATTERN = Regex("""\p{L}[\p{L} .'-]{0,49}""")

private data class PaymentApp(
    val packageName: String,
    val displayName: String
)

private fun paymentApp(app: String): PaymentApp = when (app.lowercase(Locale.ROOT)) {
    "phonepe" -> PaymentApp("com.phonepe.app", "PhonePe")
    "paytm" -> PaymentApp("net.one97.paytm", "Paytm")
    else -> PaymentApp("com.google.android.apps.nbu.paisa.user", "Google Pay")
}
