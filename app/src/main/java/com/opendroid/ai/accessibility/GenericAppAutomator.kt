package com.opendroid.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import kotlinx.coroutines.delay

object GenericAppAutomator {

    private const val RETRY_TIMEOUT_MS = 5000L
    private const val RETRY_INTERVAL_MS = 300L

    /**
     * Freshly launched apps (e.g. right after OPEN_APP or a self-contained action
     * like PLAY_YOUTUBE) are often still cold-starting when the next automation step
     * runs, so their UI elements aren't laid out yet. Polls [attempt] every
     * [RETRY_INTERVAL_MS] until it succeeds or [RETRY_TIMEOUT_MS] elapses, instead of
     * giving up after a single immediate try.
     */
    private suspend fun retryUntilTimeout(attempt: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RETRY_TIMEOUT_MS
        while (true) {
            if (attempt()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(RETRY_INTERVAL_MS)
        }
    }

    suspend fun clickText(text: String): Boolean = retryUntilTimeout {
        OpenDroidAccessibilityService.getInstance()?.findAndClick(text) == true
    }

    suspend fun clickId(viewId: String): Boolean = retryUntilTimeout {
        OpenDroidAccessibilityService.getInstance()?.findAndClickById(viewId) == true
    }

    suspend fun typeText(searchText: String, content: String): Boolean = retryUntilTimeout {
        OpenDroidAccessibilityService.getInstance()?.findAndType(searchText, content) == true
    }

    suspend fun typeId(viewId: String, content: String): Boolean = retryUntilTimeout {
        OpenDroidAccessibilityService.getInstance()?.findAndTypeById(viewId, content) == true
    }

    /**
     * Performs the IME 'enter/search/go' action on the currently focused editable
     * field — submits a search box after [typeText]/[typeId] fills it in. No polling
     * here: it's meant to run immediately after a successful type, once the field is
     * already known to exist.
     */
    fun pressEnter(): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        return service.performImeEnter()
    }

    fun scrapeScreen(): String {
        val service = OpenDroidAccessibilityService.getInstance() ?: return ""
        return service.getScreenText()
    }

    fun pressBack(): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun scroll(forward: Boolean): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        return service.performScroll(forward)
    }

    fun clickCoordinates(x: Float, y: Float): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        return service.clickCoordinates(x, y)
    }
}
