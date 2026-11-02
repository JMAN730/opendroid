package com.opendroid.ai.accessibility

/**
 * True only when the app the message was handed to owns the active window. Without a
 * known target the answer is no: a label match in an unknown window could be anything,
 * including OpenDroid's own chat Send button.
 */
internal fun isTargetAppInFront(activePackage: CharSequence?, targetPackage: String?): Boolean =
    targetPackage != null && activePackage?.toString() == targetPackage

/** resolveActivity answers "android" when it would show the chooser, which is no target. */
internal fun resolveTargetPackage(resolvedPackage: String?, fallbackPackage: String?): String? =
    resolvedPackage?.takeUnless { it == "android" } ?: fallbackPackage
