package com.opendroid.ai.core.service

import android.os.Build

/**
 * Foreground service type this app may ask the platform for.
 *
 * Deliberately not the raw `ServiceInfo.FOREGROUND_SERVICE_TYPE_*` ints: those fields are
 * compile-time constants that lint flags as `InlinedApi` whenever they are referenced below
 * their API level, and [ForegroundServiceStartPolicy] has to reason about API levels above
 * `minSdk` on every device. Resolving the enum to a platform int is [OpenDroidService]'s job,
 * behind a real `Build.VERSION.SDK_INT` check.
 */
enum class ForegroundServiceType {
    /** Let the manifest-declared types apply; [android.app.Service.startForeground] takes no type. */
    MANIFEST,

    /** `microphone`, available from API 30. The only type that carries microphone eligibility. */
    MICROPHONE,

    /** `specialUse`, available from API 34. Never restricted at boot, but grants no microphone access. */
    SPECIAL_USE,

    /** No usable type on this platform - nothing left to try. */
    NONE,
}

/**
 * Platform rules for starting [OpenDroidService] as a foreground service.
 *
 * Kept as pure functions of the SDK level so the version gates are unit-testable
 * without a device - the failures they guard against (issue 185) only surface on
 * Android 14+ hardware.
 */
object ForegroundServiceStartPolicy {

    /**
     * Android 14 bans starting a `microphone` foreground service from BOOT_COMPLETED
     * (Android 15 adds `camera`, `dataSync` and `phoneCall` to that list, but never
     * `specialUse`). Attempting the banned type is harmless though - [OpenDroidService]
     * catches the refusal per-attempt and retries with [fallbackType], which is
     * `specialUse` whenever `microphone` was preferred (i.e. RECORD_AUDIO is granted on
     * API 34+), and `specialUse` is never boot-restricted. So a boot-safe type is always
     * reachable and boot auto-start is always allowed; this stays a policy function rather
     * than a constant so a future platform version that also bans `specialUse` has one
     * place to add the gate back.
     */
    fun isBootAutoStartAllowed(sdkInt: Int, micGranted: Boolean): Boolean = true

    /**
     * Android 14+ rejects a `microphone` FGS when RECORD_AUDIO is not granted, so the agent
     * loop runs under `specialUse` until the microphone is actually usable.
     */
    fun preferredType(sdkInt: Int, micGranted: Boolean): ForegroundServiceType = when {
        sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !micGranted -> ForegroundServiceType.SPECIAL_USE
        sdkInt >= Build.VERSION_CODES.R -> ForegroundServiceType.MICROPHONE
        else -> ForegroundServiceType.MANIFEST
    }

    /**
     * Retry type when [preferredType] is refused, or [ForegroundServiceType.NONE] when there
     * is nothing left to try.
     */
    fun fallbackType(sdkInt: Int, micGranted: Boolean): ForegroundServiceType =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && micGranted) {
            ForegroundServiceType.SPECIAL_USE
        } else {
            ForegroundServiceType.NONE
        }

    /**
     * True when a foreground start of [type] leaves the service eligible to use the
     * microphone while in the background. `specialUse` never does - Android 14+ requires the
     * `microphone` type for the while-in-use RECORD_AUDIO grant - while below API 30 the
     * manifest-declared types apply and no per-type restriction exists yet.
     */
    fun carriesMicrophoneEligibility(type: ForegroundServiceType): Boolean =
        type == ForegroundServiceType.MICROPHONE || type == ForegroundServiceType.MANIFEST
}
