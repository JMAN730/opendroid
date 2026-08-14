package com.opendroid.ai.core.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService

/**
 * Whether OpenDroid's accessibility service is currently enabled.
 *
 * Prefers the live service singleton (instant while the process is up), then
 * falls back to the system enabled-services setting (covers process restarts
 * before onServiceConnected has run).
 */
fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (OpenDroidAccessibilityService.getInstance() != null) return true

    val expected =
        ComponentName(context, OpenDroidAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabled)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}
