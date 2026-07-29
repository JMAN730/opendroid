package com.opendroid.ai.core.crash

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * App and device identity stamped onto every crash.
 *
 * Captured once at startup rather than at crash time: [Context] and
 * [android.content.pm.PackageManager] lookups are exactly the kind of work that
 * is unreliable in a process that is already failing.
 */
data class DeviceMetadata(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidSdkInt: Int,
    val deviceManufacturer: String,
    val deviceModel: String
) {
    companion object {
        private const val UNKNOWN = "unknown"

        fun fromContext(context: Context): DeviceMetadata {
            var versionName = UNKNOWN
            var versionCode = 0L
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                versionName = info.versionName ?: UNKNOWN
                versionCode = PackageInfoCompat.getLongVersionCode(info)
            } catch (e: Exception) {
                // Package lookup can fail while the app is being replaced. Falling
                // back to "unknown" is better than having no crash log at all.
            }

            return DeviceMetadata(
                appVersionName = versionName,
                appVersionCode = versionCode,
                androidRelease = Build.VERSION.RELEASE ?: UNKNOWN,
                androidSdkInt = Build.VERSION.SDK_INT,
                deviceManufacturer = Build.MANUFACTURER ?: UNKNOWN,
                deviceModel = Build.MODEL ?: UNKNOWN
            )
        }
    }
}
