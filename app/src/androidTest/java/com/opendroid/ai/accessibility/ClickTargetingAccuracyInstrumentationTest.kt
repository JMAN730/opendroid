package com.opendroid.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opendroid.ai.accessibility.testfixtures.A11yProbeActivity
import com.opendroid.ai.accessibility.testfixtures.AccessibilityServiceTestHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The click-targeting accuracy bar for the accessibility-tree primitives (#167,
 * from the #133 investigation).
 *
 * Five scenarios, and the bar is that **all five pass on both matrix legs
 * (API 26 and API 36) every run** - a hard regression gate, not a percentage.
 * The corpus is small and fully owned, so there is nothing to average over.
 *
 * What makes these tests worth anything is *how* they assert: each scenario
 * checks a per-view counter inside [A11yProbeActivity], never the node tree.
 * Re-scraping the tree after a click cannot tell a correct click from a click
 * on a same-labelled decoy - and decoys are precisely what the fixture stages.
 * Each scenario also asserts every *other* counter stayed at zero.
 *
 * Scenarios 1, 2, 4 and 5 exercise [AccessibilityNodeTraversal] directly against
 * a `UiAutomation` root - it is a pure object and needs no bound service.
 * Scenario 3 targets `findAndClickById`, which lives on the service itself, so
 * it runs the real bound service end to end.
 *
 * Not covered here, deliberately: real third-party apps (WhatsApp/SMS) are not
 * stable on the CI emulators and stay manual smoke, and vision-guided coordinate
 * interaction has no code path yet - if one is built it needs its own bar.
 */
@RunWith(AndroidJUnit4::class)
class ClickTargetingAccuracyInstrumentationTest {

    private val nodeTraversal = AccessibilityNodeTraversal()

    /** Mirrors `OpenDroidAccessibilityService.imeSubmitLabels`. */
    private val submitLabels = listOf("search", "go", "send", "done", "submit", "ok", "enter")

    @After
    fun tearDown() {
        AccessibilityServiceTestHarness.disableService()
    }

    /**
     * The contract being pinned is the one `findAndClick` actually has:
     * `findAccessibilityNodeInfosByText` is a case-insensitive *substring*
     * match, and the first clickable result in tree order wins. There is no
     * exact-match preference, so the screen stages a decoy on each side of that
     * boundary - one that must not be returned at all, and one that is returned
     * and must lose on order.
     */
    @Test
    fun textClickPicksTheFirstGenuineMatchAndIgnoresTheSamePrefixDecoy() = onProbeScreen { root ->
        assertTrue(
            "findAndClick reported no click for ${A11yProbeActivity.EXACT_TARGET_TEXT}",
            nodeTraversal.findAndClick(root, A11yProbeActivity.EXACT_TARGET_TEXT),
        )
        awaitClick(A11yProbeActivity.exactTargetClicks, "exact-text target")
        assertOnlyClicked(A11yProbeActivity.exactTargetClicks)
    }

    @Test
    fun clickOnANonClickableLeafBubblesToItsClickableAncestorRow() = onProbeScreen { root ->
        assertTrue(
            "findAndClick reported no click for ${A11yProbeActivity.ROW_LEAF_TEXT}",
            nodeTraversal.findAndClick(root, A11yProbeActivity.ROW_LEAF_TEXT),
        )
        awaitClick(A11yProbeActivity.rowClicks, "clickable ancestor row")
        assertOnlyClicked(A11yProbeActivity.rowClicks)
    }

    @Test
    fun idClickPicksTheTargetOverASameTextDifferentIdDecoy() {
        val service = AccessibilityServiceTestHarness.enableService()
        val viewId = A11yProbeActivity.targetViewIdName(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
        ActivityScenario.launch(A11yProbeActivity::class.java).use {
            AccessibilityServiceTestHarness.await(message = "service sees the probe screen") {
                service.getScreenText().contains(A11yProbeActivity.ID_SHARED_TEXT)
            }
            assertTrue(
                "findAndClickById reported no click for $viewId",
                service.findAndClickById(viewId),
            )
            awaitClick(A11yProbeActivity.idTargetClicks, "id-targeted button")
            assertOnlyClicked(A11yProbeActivity.idTargetClicks)
        }
    }

    @Test
    fun submitFallbackMatchesTheLabelCaseInsensitivelyAndSkipsTheDecoy() = onProbeScreen { root ->
        assertTrue(
            "findAndClickSubmitControl found no submit control",
            nodeTraversal.findAndClickSubmitControl(root, submitLabels),
        )
        awaitClick(A11yProbeActivity.submitTargetClicks, "submit control")
        assertOnlyClicked(A11yProbeActivity.submitTargetClicks)
    }

    @Test
    fun noMatchingElementClicksNothingAtAll() = onProbeScreen { root ->
        assertFalse(
            "findAndClick claimed a click for absent text",
            nodeTraversal.findAndClick(root, A11yProbeActivity.NO_MATCH_TEXT),
        )
        assertFalse(
            "findAndClickSubmitControl claimed a click with no candidate label",
            nodeTraversal.findAndClickSubmitControl(root, listOf(A11yProbeActivity.NO_MATCH_TEXT)),
        )
        assertOnlyClicked(null)
    }

    /**
     * Launches the probe screen, waits until it is the window `UiAutomation`
     * reports, and hands the block a fresh root node.
     */
    private fun onProbeScreen(block: (AccessibilityNodeInfo) -> Unit) {
        ActivityScenario.launch(A11yProbeActivity::class.java).use {
            AccessibilityServiceTestHarness.await(message = "probe screen visible to UiAutomation") {
                val root = AccessibilityServiceTestHarness.uiAutomation.rootInActiveWindow
                root != null &&
                    nodeTraversal.screenText(root).contains(A11yProbeActivity.HEADLINE_TEXT)
            }
            block(AccessibilityServiceTestHarness.uiAutomation.rootInActiveWindow!!)
        }
    }

    /** ACTION_CLICK dispatch is asynchronous, so the counter is polled, not read once. */
    private fun awaitClick(counter: java.util.concurrent.atomic.AtomicInteger, what: String) {
        AccessibilityServiceTestHarness.await(message = "$what registered a click") {
            counter.get() > 0
        }
    }

    /** [expected] `null` means nothing on the screen may have been clicked. */
    private fun assertOnlyClicked(expected: java.util.concurrent.atomic.AtomicInteger?) {
        A11yProbeActivity.allCounters
            .filter { it !== expected }
            .forEach { assertEquals("a view other than the intended target was clicked", 0, it.get()) }
        expected?.let { assertEquals("intended target clicked more than once", 1, it.get()) }
    }
}
