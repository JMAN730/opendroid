package com.opendroid.ai.accessibility.testfixtures

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
 * (#66) into a supported test fixture (#105).
 */
class A11yProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clickCount.set(0)
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
            // the click path counts presses directly.
            setOnClickListener { clickCount.incrementAndGet() }
        })

        root.addView(EditText(this).apply {
            hint = FIELD_HINT
        })

        setContentView(root)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchEvents.add(MotionEvent.obtain(ev))
        return super.dispatchTouchEvent(ev)
    }

    companion object {
        const val HEADLINE_TEXT = "A11Y PROBE HEADLINE"
        const val BUTTON_TEXT = "A11Y PROBE TAP ME"
        const val FIELD_HINT = "A11Y PROBE FIELD"

        /** Clicks observed on the button, reset in onCreate. */
        val clickCount = AtomicInteger(0)

        /** Raw touch stream, reset in onCreate - unused until gesture coverage lands (#106). */
        val touchEvents = CopyOnWriteArrayList<MotionEvent>()
    }
}
