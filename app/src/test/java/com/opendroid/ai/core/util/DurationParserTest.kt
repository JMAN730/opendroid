package com.opendroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    @Test
    fun `parses plain seconds`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertEquals(60, DurationParser.parseToSeconds("60"))
    }

    @Test
    fun `parses second units`() {
        assertEquals(5, DurationParser.parseToSeconds("5s"))
        assertEquals(5, DurationParser.parseToSeconds("5 seconds"))
        assertEquals(30, DurationParser.parseToSeconds("30 sec"))
    }

    @Test
    fun `parses minute units`() {
        assertEquals(300, DurationParser.parseToSeconds("5 minutes"))
        assertEquals(120, DurationParser.parseToSeconds("2 minute"))
        assertEquals(60, DurationParser.parseToSeconds("1 min"))
    }

    @Test
    fun `parses hour units`() {
        assertEquals(3600, DurationParser.parseToSeconds("1 hour"))
        assertEquals(7200, DurationParser.parseToSeconds("2 hours"))
    }

    @Test
    fun `parses compact adjacent units`() {
        assertEquals(5400, DurationParser.parseToSeconds("1h30m"))
        assertEquals(90, DurationParser.parseToSeconds("1m30s"))
    }

    @Test
    fun `parses decimal unit amounts`() {
        assertEquals(5400, DurationParser.parseToSeconds("1.5 hours"))
        assertEquals(150, DurationParser.parseToSeconds("2.5 minutes"))
        assertEquals(30, DurationParser.parseToSeconds("0.5 min"))
        assertEquals(5400, DurationParser.parseToSeconds("1.5h"))
        assertEquals(3, DurationParser.parseToSeconds(" 2.5 SECONDS "))
    }

    @Test
    fun `rounds only after summing all duration parts`() {
        assertEquals(180, DurationParser.parseToSeconds("2m59.56s"))
        assertEquals(179, DurationParser.parseToSeconds("2m59.49s"))
        assertEquals(2, DurationParser.parseToSeconds("0.01m0.6s0.6s"))
        assertEquals(5431, DurationParser.parseToSeconds("1.5h0.5m0.5s"))
        assertEquals(62, DurationParser.parseToSeconds("1.025 minutes"))
    }

    @Test
    fun `clamps zero and subsecond durations to one second`() {
        assertEquals(1, DurationParser.parseToSeconds("0"))
        assertEquals(1, DurationParser.parseToSeconds("0s"))
        assertEquals(1, DurationParser.parseToSeconds("0.0 minutes"))
        assertEquals(1, DurationParser.parseToSeconds("0.1s"))
    }

    @Test
    fun `large unit amounts saturate without integer overflow`() {
        assertEquals(Int.MAX_VALUE, DurationParser.parseToSeconds("2147483648s"))
        assertEquals(Int.MAX_VALUE, DurationParser.parseToSeconds("2147483647h"))
        assertEquals(Int.MAX_VALUE, DurationParser.parseToSeconds("${"9".repeat(400)}h"))
    }

    @Test
    fun `bare digit fallback ignores embedded numbers`() {
        assertEquals(5, DurationParser.parseToSeconds("5"))
        assertNull(DurationParser.parseToSeconds("at 2pm yesterday"))
    }

    @Test
    fun `returns null for empty input`() {
        assertNull(DurationParser.parseToSeconds(""))
        assertNull(DurationParser.parseToSeconds("   "))
    }
}
