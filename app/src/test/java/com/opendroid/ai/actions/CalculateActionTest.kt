package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalculateActionTest {
    private val context = RecordingContext(ApplicationProvider.getApplicationContext())
    private val action = InformationActions().getActions().first { it.name == "CALCULATE" }

    private fun calculate(expression: String): ActionResult = runBlocking {
        action.execute(mapOf("expression" to expression), context)
    }

    private fun assertCalculates(expression: String, expected: Double) {
        val result = calculate(expression)
        assertTrue("'$expression' should succeed but got $result", result.success)
        assertEquals("$expression = $expected", result.data)
        assertTrue("'$expression' should not open a search", context.started.isEmpty())
    }

    private fun assertFallsBackToSearch(expression: String) {
        context.started.clear()
        val result = calculate(expression)
        assertFalse("'$expression' should not succeed but got ${result.data}", result.success)
        assertTrue(result is ActionResult.Failure)
        assertEquals("'$expression' should open one search", 1, context.started.size)
        assertEquals(Intent.ACTION_VIEW, context.started.single().action)
    }

    @Test
    fun `chains of one operator use every operand`() {
        assertCalculates("120+45+80", 245.0)
        assertCalculates("10+20+30", 60.0)
        assertCalculates("100-10-5", 85.0)
        assertCalculates("2*3*4", 24.0)
        assertCalculates("100/2/5", 10.0)
    }

    @Test
    fun `operator precedence and parentheses are honoured`() {
        assertCalculates("2+3*4", 14.0)
        assertCalculates("(2+3)*4", 20.0)
        assertCalculates("10+20-5", 25.0)
        assertCalculates("2*3+4", 10.0)
        assertCalculates("100-(10-5)", 95.0)
    }

    @Test
    fun `unary minus works in every position`() {
        assertCalculates("-5+3", -2.0)
        assertCalculates("-5-3", -8.0)
        assertCalculates("2*-3", -6.0)
        assertCalculates("-(2+3)", -5.0)
    }

    @Test
    fun `spaces between tokens and decimals are accepted`() {
        assertCalculates("45 * 12", 540.0)
        assertCalculates("1.5 + .5", 2.0)
        assertCalculates("1e3+1", 1001.0)
        assertCalculates("42", 42.0)
    }

    @Test
    fun `percent of is a fraction of the operand that follows`() {
        assertCalculates("15% of 2000", 300.0)
        assertCalculates("7% of 100", 7.0)
        assertCalculates("10% of 200 + 5", 25.0)
        assertCalculates("50% OF 20% of 100", 10.0)
    }

    @Test
    fun `bare percent is ambiguous and falls back instead of guessing`() {
        assertFallsBackToSearch("200+10%")
        assertFallsBackToSearch("10%3")
        assertFallsBackToSearch("15%")
    }

    @Test
    fun `unparseable input is not success`() {
        assertFallsBackToSearch("abc")
        assertFallsBackToSearch("")
        assertFallsBackToSearch("2+")
        assertFallsBackToSearch("*2")
        assertFallsBackToSearch("(2+3")
        assertFallsBackToSearch("2+3)")
        assertFallsBackToSearch("()")
        assertFallsBackToSearch("2 3")
        assertFallsBackToSearch("1.2.3")
        assertFallsBackToSearch("what is 45 * 12")
    }

    @Test
    fun `results with no finite value are not success`() {
        assertFallsBackToSearch("1/0")
        assertFallsBackToSearch("0/0")
        assertFallsBackToSearch("1e308*10")
    }

    @Test
    fun `absurdly deep nesting falls back instead of overflowing the stack`() {
        assertFallsBackToSearch("(".repeat(100_000) + "1" + ")".repeat(100_000))
        assertFallsBackToSearch("-".repeat(100_000) + "1")
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            started += intent
        }
    }
}
