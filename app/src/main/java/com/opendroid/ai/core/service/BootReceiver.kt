package com.opendroid.ai.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || context == null) return
        // Android 14+ rejects this service's foreground types when the start comes from
        // BOOT_COMPLETED; the app starts it on first launch instead (issue 185).
        if (!ForegroundServiceStartPolicy.isBootAutoStartAllowed(Build.VERSION.SDK_INT)) return
        OpenDroidService.start(context)
    }
}
