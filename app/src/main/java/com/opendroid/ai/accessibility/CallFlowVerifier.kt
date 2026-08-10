package com.opendroid.ai.accessibility

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Platform boundary used to verify that a direct call actually entered Telecom. */
interface CallFlowVerifier {
    fun isCallInProgress(context: Context): Boolean

    suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean
}

class AndroidCallFlowVerifier @Inject constructor() : CallFlowVerifier {

    override fun isCallInProgress(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            context.getSystemService(TelecomManager::class.java)?.isInCall == true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun awaitNewCallStarted(
        context: Context,
        wasAlreadyInProgress: Boolean
    ): Boolean {
        if (wasAlreadyInProgress) return false

        return withTimeoutOrNull(VERIFICATION_TIMEOUT_MS) {
            do {
                if (isCallInProgress(context)) return@withTimeoutOrNull true
                delay(POLL_INTERVAL_MS)
            } while (currentCoroutineContext().isActive)
            false
        } ?: false
    }

    private companion object {
        const val VERIFICATION_TIMEOUT_MS = 4_000L
        const val POLL_INTERVAL_MS = 250L
    }
}
