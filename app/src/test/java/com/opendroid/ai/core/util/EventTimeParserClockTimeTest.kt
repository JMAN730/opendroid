package com.opendroid.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventTimeParserClockTimeTest {

    @Test
    fun `relative times apply a trailing am or pm`() {
        assertEquals(Pair(19, 45), EventTimeParser.parseClockTime("quarter to 8 pm"))
        assertEquals(Pair(18, 30), EventTimeParser.parseClockTime("half past 6 pm"))
        assertEquals(Pair(17, 15), EventTimeParser.parseClockTime("quarter past 5 p.m."))
        assertEquals(Pair(7, 45), EventTimeParser.parseClockTime("quarter to 8 am"))
        assertEquals(Pair(23, 45), EventTimeParser.parseClockTime("quarter to 12 am"))
        assertEquals(Pair(0, 30), EventTimeParser.parseClockTime("half past 12 am"))
    }

    @Test
    fun `relative times without a qualifier keep their hour`() {
        assertEquals(Pair(7, 45), EventTimeParser.parseClockTime("quarter to 8"))
        assertEquals(Pair(6, 30), EventTimeParser.parseClockTime("half past 6"))
        assertEquals(Pair(5, 15), EventTimeParser.parseClockTime("quarter past 5"))
        assertEquals(Pair(23, 45), EventTimeParser.parseClockTime("quarter to 0"))
    }

    @Test
    fun `relative times reject anything the parser does not consume`() {
        assertNull(EventTimeParser.parseClockTime("quarter to 8 tonight"))
        assertNull(EventTimeParser.parseClockTime("saturday half past 6"))
        assertNull(EventTimeParser.parseClockTime("quarter to 13 pm"))
    }
}
