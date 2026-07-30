package com.opendroid.ai.core.permissions

import android.content.Context

object PermissionAskedStore {
    private const val PREFS_NAME = "permission_asked"
    private const val KEY_ASKED = "asked_permissions"

    fun asked(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_ASKED, emptySet())
            ?.toSet()
            .orEmpty()

    fun markAsked(
        context: Context,
        permissions: Collection<String>,
    ) {
        if (permissions.isEmpty()) return

        val updated = asked(context) + permissions
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ASKED, updated)
            .apply()
    }
}
