package com.opendroid.ai.accessibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendroid.ai.accessibility.testfixtures.AccessibilityServiceTestHarness
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves an instrumentation test can turn the real
 * [OpenDroidAccessibilityService] on and off (shell secure-settings write),
 * observe the OS bind it (singleton set in `onServiceConnected`), and
 * cross-check through the public AccessibilityManager API. No production
 * hooks beyond the existing `getInstance()` accessor.
 *
 * Promoted from the #66 prototype's `ServiceReadinessProtoTest`.
 */
@RunWith(AndroidJUnit4::class)
class ServiceReadinessInstrumentationTest {

    @After
    fun tearDown() {
        AccessibilityServiceTestHarness.disableService()
    }

    @Test
    fun serviceBindsWhenEnabledAndUnbindsWhenDisabled() {
        assertNull(
            "precondition: service must start disabled",
            OpenDroidAccessibilityService.getInstance()
        )

        val service = AccessibilityServiceTestHarness.enableService()
        assertNotNull(service)
        assertTrue(
            "AccessibilityManager should report the service enabled",
            AccessibilityServiceTestHarness.isServiceReportedEnabled()
        )

        AccessibilityServiceTestHarness.disableService()
        assertNull(OpenDroidAccessibilityService.getInstance())
    }
}
