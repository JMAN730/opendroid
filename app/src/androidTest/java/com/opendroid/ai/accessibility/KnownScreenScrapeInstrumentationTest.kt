package com.opendroid.ai.accessibility

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.opendroid.ai.accessibility.testfixtures.A11yProbeActivity
import com.opendroid.ai.accessibility.testfixtures.AccessibilityServiceTestHarness
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Known-screen node-tree scraping, in two independent ways because they
 * answer different questions:
 *
 * - [nodeTraversalSeesTheKnownScreenWithNoBoundService]: [AccessibilityNodeTraversal]
 *   is a pure object - it needs no app accessibility service at all. UiAutomation
 *   hands out the same `AccessibilityNodeInfo` tree the service would see, so this
 *   is the cheap path for traversal/matching logic.
 *
 * - [serviceScrapesTheKnownScreenEndToEnd]: the real service's own
 *   `getScreenText()` over its own `rootInActiveWindow`, proving the production
 *   code path end-to-end (bind -> window -> traversal via the injected
 *   [AccessibilityNodeTraversal]).
 *
 * Promoted from the #66 prototype's `NodeScrapeProtoTest`, now exercising the
 * extracted [AccessibilityNodeTraversal] instead of a copy of the service's logic.
 */
@RunWith(AndroidJUnit4::class)
class KnownScreenScrapeInstrumentationTest {

    private val nodeTraversal = AccessibilityNodeTraversal()

    @After
    fun tearDown() {
        AccessibilityServiceTestHarness.disableService()
    }

    @Test
    fun nodeTraversalSeesTheKnownScreenWithNoBoundService() {
        ActivityScenario.launch(A11yProbeActivity::class.java).use {
            AccessibilityServiceTestHarness.await(message = "probe screen visible to UiAutomation") {
                val root = AccessibilityServiceTestHarness.uiAutomation.rootInActiveWindow
                root != null && nodeTraversal.screenText(root).contains(A11yProbeActivity.HEADLINE_TEXT)
            }
            val root = AccessibilityServiceTestHarness.uiAutomation.rootInActiveWindow!!
            val text = nodeTraversal.screenText(root)
            assertTrue(text.contains(A11yProbeActivity.BUTTON_TEXT))
            assertTrue(text.contains(A11yProbeActivity.FIELD_HINT))
        }
    }

    @Test
    fun serviceScrapesTheKnownScreenEndToEnd() {
        val service = AccessibilityServiceTestHarness.enableService()
        ActivityScenario.launch(A11yProbeActivity::class.java).use {
            AccessibilityServiceTestHarness.await(message = "service scrape sees the probe headline") {
                service.getScreenText().contains(A11yProbeActivity.HEADLINE_TEXT)
            }
            assertTrue(service.getScreenText().contains(A11yProbeActivity.BUTTON_TEXT))
        }
    }
}
