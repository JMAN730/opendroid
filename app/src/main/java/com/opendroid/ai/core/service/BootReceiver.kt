package com.opendroid.ai.core.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || context == null) return
        val micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Android 14+ rejects the `microphone` foreground type when the start comes from
        // BOOT_COMPLETED; OpenDroidService.startForegroundCompat() catches that refusal and
        // falls back to `specialUse`, which BOOT_COMPLETED may still launch (issue 185).
        if (!ForegroundServiceStartPolicy.isBootAutoStartAllowed(Build.VERSION.SDK_INT, micGranted)) return
        OpenDroidService.start(context)
    }
}
