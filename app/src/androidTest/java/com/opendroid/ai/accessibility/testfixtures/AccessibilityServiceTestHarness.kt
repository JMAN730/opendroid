package com.opendroid.ai.accessibility.testfixtures

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService

/**
 * Enables/disables the real [OpenDroidAccessibilityService] from inside an
 * instrumentation test, with no production-only hooks.
 *
 * How it works, and the two traps this encodes:
 *
 * 1. The instrumentation's shell identity may write secure settings, so
 *    `settings put secure enabled_accessibility_services <component>` turns the
 *    service on exactly the way the Settings app would — the OS binds the real
 *    service, Hilt injection and `onServiceConnected` run for real.
 *
 * 2. TRAP: UiAutomation itself is an accessibility service, and acquiring it
 *    with default flags SUPPRESSES every other enabled accessibility service.
 *    Any test that wants the app's service alive while also using UiAutomation
 *    (shell commands, node access) must acquire it with
 *    [UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES] — and must do so
 *    BEFORE anything else grabs the default one.
 *
 * Promoted from the throwaway prototype on `research/66-a11y-test-proto` (#66)
 * into a supported test fixture (#105).
 */
object AccessibilityServiceTestHarness {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** UiAutomation that does not kill the service under test. */
    val uiAutomation: UiAutomation
        get() = instrumentation.getUiAutomation(
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        )

    private val serviceComponent: String
        get() {
            val appContext = instrumentation.targetContext
            return "${appContext.packageName}/${OpenDroidAccessibilityService::class.java.name}"
        }

    fun enableService(timeoutMs: Long = 10_000): OpenDroidAccessibilityService {
        shell("settings put secure enabled_accessibility_services $serviceComponent")
        shell("settings put secure accessibility_enabled 1")

        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            OpenDroidAccessibilityService.getInstance()?.let { return it }
            SystemClock.sleep(100)
        }
        error("OpenDroidAccessibilityService did not connect within ${timeoutMs}ms")
    }

    fun disableService() {
        shell("settings put secure enabled_accessibility_services \"\"")
        shell("settings put secure accessibility_enabled 0")
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline &&
            OpenDroidAccessibilityService.getInstance() != null
        ) {
            SystemClock.sleep(100)
        }
    }

    /** Cross-check readiness through the public AccessibilityManager API. */
    fun isServiceReportedEnabled(): Boolean {
        val am = instrumentation.targetContext
            .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id == serviceComponent }
    }

    fun shell(command: String) {
        val pfd = uiAutomation.executeShellCommand(command)
        // Drain so the command actually completes before we return.
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    /** Poll until [condition] holds; fail with [message] otherwise. */
    fun await(timeoutMs: Long = 5_000, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Timed out: $message")
    }
}
