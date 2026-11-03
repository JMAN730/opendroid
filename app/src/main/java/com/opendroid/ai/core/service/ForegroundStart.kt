package com.opendroid.ai.core.service

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo

/**
 * Picks the foreground service type for [OpenDroidService] and starts it without
 * letting a refused start escape onCreate.
 */
internal object ForegroundStart {

    /**
     * Types to try in order. null means an untyped startForeground (before API 30).
     * The constants are compile-time ints gated on [sdk], which lint's InlinedApi check
     * cannot tie to SDK_INT.
     */
    @SuppressLint("InlinedApi")
    fun typesFor(sdk: Int, micGranted: Boolean): List<Int?> = when {
        sdk >= 34 -> if (micGranted) {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        sdk >= 30 -> listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else -> listOf(null)
    }

    /**
     * Tries each type until one starts. Android 14+ refuses a microphone FGS without
     * RECORD_AUDIO in use (SecurityException). Android 15 refuses one from BOOT_COMPLETED,
     * and Android 12+ refuses any FGS started from the background, such as a START_STICKY
     * restart; both throw ForegroundServiceStartNotAllowedException, an IllegalStateException.
     * False means nothing started and the caller must stop instead of crashing.
     */
    fun startFirstAllowed(types: List<Int?>, start: (Int?) -> Unit): Boolean {
        for (type in types) {
            try {
                start(type)
                return true
            } catch (e: SecurityException) {
                // try the next type
            } catch (e: IllegalStateException) {
                // try the next type
            }
        }
        return false
    }
}
