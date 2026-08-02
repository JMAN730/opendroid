package com.opendroid.ai.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Probes optional hardware before an action reaches for it.
 *
 * The manifest declares telephony and camera with `android:required="false"` so
 * Play still ships the app to ChromeOS, telephony-less tablets, and foldables.
 * That means every call, SMS, and camera path has to assume the hardware may be
 * missing and fail with a message the user can act on instead of throwing.
 */
object DeviceCapabilities {

    /** True when the device can place a voice call (not just carry mobile data). */
    fun canMakeCalls(context: Context): Boolean =
        hasFeature(context, PackageManager.FEATURE_TELEPHONY_CALLING, PackageManager.FEATURE_TELEPHONY)

    /** True when the device can send SMS through its telephony stack. */
    fun canSendSms(context: Context): Boolean =
        hasFeature(context, PackageManager.FEATURE_TELEPHONY_MESSAGING, PackageManager.FEATURE_TELEPHONY)

    /** True when the device has any camera (front, back, or external). */
    fun hasCamera(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * The granular telephony features ([PackageManager.FEATURE_TELEPHONY_CALLING],
     * [PackageManager.FEATURE_TELEPHONY_MESSAGING]) only exist from API 33; below
     * that the OS never declares them, so the coarse [PackageManager.FEATURE_TELEPHONY]
     * is the best available signal.
     */
    private fun hasFeature(context: Context, modernFeature: String, legacyFeature: String): Boolean {
        val feature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) modernFeature else legacyFeature
        return context.packageManager.hasSystemFeature(feature)
    }
}
