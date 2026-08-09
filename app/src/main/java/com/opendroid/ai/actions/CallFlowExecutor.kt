package com.opendroid.ai.actions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.util.DeviceCapabilities
import javax.inject.Inject

/** Executes a phone call without treating intent launch as proof that the call started. */
class CallFlowExecutor @Inject constructor(
    private val callFlowVerifier: CallFlowVerifier
) {

    suspend fun execute(phone: String, context: Context): ActionResult {
        val cleanPhone = phone.replace(Regex("[\\s\\-()]"), "").trim()
        if (cleanPhone.isEmpty()) {
            return ActionResult.Failure("The requested phone number is empty.")
        }

        val callUri = "tel:$cleanPhone".toUri()
        val hasTelephony = DeviceCapabilities.canMakeCalls(context)
        val dialIntent = Intent(Intent.ACTION_DIAL, callUri).withNewTask()

        if (!hasTelephony && dialIntent.resolveActivity(context.packageManager) == null) {
            return ActionResult.Failure(
                "This device cannot place phone calls because no calling hardware or dialer is available."
            )
        }

        return try {
            if (hasTelephony && canVerifyDirectCall(context)) {
                val wasAlreadyInProgress = callFlowVerifier.isCallInProgress(context)
                context.startActivity(Intent(Intent.ACTION_CALL, callUri).withNewTask())

                if (callFlowVerifier.awaitNewCallStarted(context, wasAlreadyInProgress)) {
                    ActionResult.Success(mapOf("message" to "Call started."))
                } else {
                    ActionResult.Failure("The call could not be verified as started. No call was reported as active.")
                }
            } else {
                context.startActivity(dialIntent)
                pendingDialerResult()
            }
        } catch (_: SecurityException) {
            openDialerAfterDirectCallFailure(context, dialIntent)
        } catch (_: Exception) {
            ActionResult.Failure("The call could not be started.")
        }
    }

    private fun openDialerAfterDirectCallFailure(context: Context, dialIntent: Intent): ActionResult {
        return try {
            context.startActivity(dialIntent)
            pendingDialerResult()
        } catch (_: Exception) {
            ActionResult.Failure("The call could not be started.")
        }
    }

    private fun pendingDialerResult(): ActionResult.PendingUserAction =
        ActionResult.PendingUserAction(
            message = "The dialer is open. Tap Call to place the call.",
            metadata = mapOf("action" to "MAKE_CALL", "requiresUserAction" to "true")
        )

    /**
     * ACTION_CALL alone is not enough: without READ_PHONE_STATE the verifier can never
     * observe the call, so a call that really was placed would be reported as failed.
     * Users who upgrade keep CALL_PHONE but start without READ_PHONE_STATE, so both
     * permissions must be granted before taking the verified direct-call path.
     */
    private fun canVerifyDirectCall(context: Context): Boolean =
        isGranted(context, Manifest.permission.CALL_PHONE) &&
            isGranted(context, Manifest.permission.READ_PHONE_STATE)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Intent.withNewTask(): Intent = apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
