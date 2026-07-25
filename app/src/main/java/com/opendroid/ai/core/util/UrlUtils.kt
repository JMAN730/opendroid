package com.opendroid.ai.core.util

/**
 * Normalizes user-entered API base URLs (scheme + no trailing slash).
 */
object UrlUtils {
    fun formatBaseUrl(url: String, defaultUrl: String): String {
        val trimmed = url.trim()
        val raw = if (trimmed.isEmpty()) defaultUrl.trim() else trimmed
        if (raw.isEmpty()) return ""

        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "http://$raw"
        }
        return withScheme.trimEnd('/')
    }
}
