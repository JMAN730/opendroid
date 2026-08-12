package com.opendroid.ai.core.service

import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Platform rules for starting [OpenDroidService] as a foreground service.
 *
 * Kept as pure functions of the SDK level so the version gates are unit-testable
 * without a device - the failures they guard against (issue 185) only surface on
 * Android 14+ hardware.
 */
object ForegroundServiceStartPolicy {

    /** Let the manifest-declared types apply; [android.app.Service.startForeground] takes no type. */
    const val TYPE_MANIFEST = 0

    /** No usable fallback type on this platform. */
    const val TYPE_NONE = -1

    /**
     * Android 14 bans starting a `microphone` foreground service from BOOT_COMPLETED
     * (Android 15 adds `camera`, `dataSync` and `phoneCall` to that list, but never
     * `specialUse`). So boot auto-start is only unsafe from API 34 up when RECORD_AUDIO
     * is granted, since that is what makes [preferredType] pick `microphone`; without the
     * grant the service starts under `specialUse`, which BOOT_COMPLETED may still launch.
     * Attempting a banned type throws `ForegroundServiceStartNotAllowedException` and kills
     * the process; the service is started from the UI instead once the user opens the app.
     */
    fun isBootAutoStartAllowed(sdkInt: Int, micGranted: Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !micGranted

    /**
     * Android 14+ rejects a `microphone` FGS when RECORD_AUDIO is not granted, so the agent
     * loop runs under `specialUse` until the microphone is actually usable.
     */
    fun preferredType(sdkInt: Int, micGranted: Boolean): Int = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !micGranted ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        sdkInt >= Build.VERSION_CODES.R ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else -> TYPE_MANIFEST
    }

    /** Retry type when [preferredType] is refused, or [TYPE_NONE] when there is nothing left to try. */
    fun fallbackType(sdkInt: Int, micGranted: Boolean): Int =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && micGranted) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            TYPE_NONE
        }
}
