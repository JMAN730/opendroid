package com.opendroid.ai.accessibility.testfixtures

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.opendroid.ai.R
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * "Known screen" fixture for accessibility instrumentation tests: fixed
 * texts a scrape test can assert against, a button that counts clicks, and
 * a full-screen touch recorder so gesture tests can observe a dispatched tap
 * as real MotionEvents.
 *
 * Debug-source-set only (like `ui-test-manifest`'s own activities) - it must
 * NOT ship in release, and `ActivityScenario` cannot launch a test-APK
 * activity into the app process (`resolved to different process`), so this
 * has to live in `src/debug` rather than `src/androidTest`.
 *
 * Promoted from the throwaway prototype on `research/66-a11y-test-proto`
 * (#66) into a supported test fixture (#105), then extended with the
 * distractor/decoy views the click-targeting accuracy bar needs (#167).
 *
 * Every clickable view owns its own counter, because a click-targeting test
 * has to prove *which* view was clicked - asserting "a click happened", or
 * re-reading the node tree, both pass on a wrong-node click whenever the
 * label repeats on screen. That is exactly what the decoys below arrange.
 */
class A11yProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resetCounters()
        touchEvents.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = "a11y-probe-root"
        }

        root.addView(TextView(this).apply {
            text = HEADLINE_TEXT
        })

        root.addView(Button(this).apply {
            text = BUTTON_TEXT
            // Resource ids in a test APK don't resolve through R.id lookups the
            // way product code expects, so the scrape path matches on text and
            // the click path counts presses directly. (The id-targeting views
            // below carry declared debug-source-set ids instead - see #167.)
            setOnClickListener { clickCount.incrementAndGet() }
        })

        root.addView(EditText(this).apply {
            hint = FIELD_HINT
        })

        // (1) Text targeting with a decoy on each side of the match boundary:
        // the prefix decoy comes first and is NOT returned for the target query
        // (loosening the match in either direction would start returning it,
        // and it is first, so it would win), while the superstring decoy IS a
        // genuine competing match and only loses on tree order.
        root.addView(compactButton(PREFIX_DECOY_TEXT, prefixDecoyClicks))
        root.addView(compactButton(EXACT_TARGET_TEXT, exactTargetClicks))
        root.addView(compactButton(SUPERSTRING_DECOY_TEXT, superstringDecoyClicks))

        // (2) Ancestor bubbling: a non-clickable leaf inside a clickable row,
        // the shape every list item in the app's own screens has.
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setOnClickListener { rowClicks.incrementAndGet() }
            addView(TextView(this@A11yProbeActivity).apply {
                text = ROW_LEAF_TEXT
                isClickable = false
            })
        })

        // (3) Id targeting. Both buttons carry the SAME text, so only the
        // resource id distinguishes them; the decoy is added first.
        root.addView(compactButton(ID_SHARED_TEXT, idDecoyClicks).apply {
            id = R.id.a11y_probe_id_decoy
        })
        root.addView(compactButton(ID_SHARED_TEXT, idTargetClicks).apply {
            id = R.id.a11y_probe_id_target
        })

        // (4) Submit-control fallback: a clickable decoy whose label is not on
        // the candidate list, then the real submit control in mixed case - the
        // match is case-insensitive and whole-label, not substring.
        root.addView(compactButton(SUBMIT_DECOY_TEXT, submitDecoyClicks))
        root.addView(compactButton(SUBMIT_TARGET_TEXT, submitTargetClicks))

        // (5) Negative control needs no view: NO_MATCH_TEXT is deliberately
        // absent from this screen, and every counter above must stay at 0.

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /**
     * Small enough that the whole fixture fits one screen on the shortest
     * matrix leg - a node scrolled out of the window is not reported to
     * accessibility clients at all, which would fail the test for the wrong
     * reason.
     */
    private fun compactButton(label: String, counter: AtomicInteger): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(8, 0, 8, 0)
            setOnClickListener { counter.incrementAndGet() }
        }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchEvents.add(MotionEvent.obtain(ev))
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val HEADLINE_TEXT = "A11Y PROBE HEADLINE"
        const val BUTTON_TEXT = "A11Y PROBE TAP ME"
        const val FIELD_HINT = "A11Y PROBE FIELD"

        /** (1) Shares a prefix with [EXACT_TARGET_TEXT] but must never be clicked for it. */
        const val PREFIX_DECOY_TEXT = "A11Y CLICK SEND"
        const val EXACT_TARGET_TEXT = "A11Y CLICK SEND REPORT"

        /**
         * (1) Contains [EXACT_TARGET_TEXT], so the substring match really does
         * return it alongside the target - it loses only because it is later in
         * the tree, which is the selection rule under test.
         */
        const val SUPERSTRING_DECOY_TEXT = "A11Y CLICK SEND REPORT NOW"

        /** (2) Non-clickable leaf; the click must land on its clickable ancestor row. */
        const val ROW_LEAF_TEXT = "A11Y ROW LEAF LABEL"

        /** (3) Identical on both id-scenario buttons, so only the id can tell them apart. */
        const val ID_SHARED_TEXT = "A11Y ID SHARED LABEL"

        /** (4) Clickable, but its label is not in the service's submit-label list. */
        const val SUBMIT_DECOY_TEXT = "A11Y TRANSMIT CONTROL"

        /** (4) Mixed case on purpose: the submit match lowercases before comparing. */
        const val SUBMIT_TARGET_TEXT = "Send"

        /** (5) Never rendered - the negative control searches for this. */
        const val NO_MATCH_TEXT = "A11Y ABSENT CONTROL 404"

        /** Clicks observed on the button, reset in onCreate. */
        val clickCount = AtomicInteger(0)

        val prefixDecoyClicks = AtomicInteger(0)
        val exactTargetClicks = AtomicInteger(0)
        val superstringDecoyClicks = AtomicInteger(0)
        val rowClicks = AtomicInteger(0)
        val idDecoyClicks = AtomicInteger(0)
        val idTargetClicks = AtomicInteger(0)
        val submitDecoyClicks = AtomicInteger(0)
        val submitTargetClicks = AtomicInteger(0)

        /** Every counter on this screen, for "nothing else was clicked" assertions. */
        val allCounters: List<AtomicInteger>
            get() = listOf(
                clickCount,
                prefixDecoyClicks,
                exactTargetClicks,
                superstringDecoyClicks,
                rowClicks,
                idDecoyClicks,
                idTargetClicks,
                submitDecoyClicks,
                submitTargetClicks,
            )

        fun resetCounters() = allCounters.forEach { it.set(0) }

        /**
         * Fully-qualified `viewIdResourceName` of [R.id.a11y_probe_id_target], as
         * `findAndClickById` expects it. Read off the resource table rather than
         * hardcoded: the resource package is the module namespace, which is not
         * the applicationId this app ships under.
         */
        fun targetViewIdName(context: android.content.Context): String =
            context.resources.getResourceName(R.id.a11y_probe_id_target)

        /** Raw touch stream, reset in onCreate - unused until gesture coverage lands (#106). */
        val touchEvents = CopyOnWriteArrayList<MotionEvent>()
    }
}
