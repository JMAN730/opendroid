package com.opendroid.ai.core.util

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventTimeParserTest {
    private val zone = ZoneId.of("Europe/Stockholm")

    // Monday 2026-11-02, 10:00 local.
    private val now = ZonedDateTime.of(2026, 11, 2, 10, 0, 0, 0, zone)

    private fun at(month: Int, day: Int, hour: Int, minute: Int, year: Int = 2026) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

    private fun assertTimed(text: String, expected: ZonedDateTime) {
        val moment = EventTimeParser.parseMoment(text, now)
        assertEquals(text, expected, moment?.begin)
        assertEquals(text, true, moment?.hasTime)
    }

    private fun assertDateOnly(text: String, month: Int, day: Int, year: Int = 2026) {
        val moment = EventTimeParser.parseMoment(text, now)
        assertEquals(text, at(month, day, 0, 0, year), moment?.begin)
        assertEquals(text, false, moment?.hasTime)
    }

    @Test
    fun `time alone means its next occurrence`() {
        assertTimed("20:00", at(11, 2, 20, 0))
        assertTimed("8pm", at(11, 2, 20, 0))
        assertTimed("at 8 pm", at(11, 2, 20, 0))
        assertTimed("08:00", at(11, 3, 8, 0))
        assertTimed("10:00", at(11, 3, 10, 0))
    }

    @Test
    fun `date and time combine in either order`() {
        assertTimed("2026-11-05 15:00", at(11, 5, 15, 0))
        assertTimed("2026-11-05T15:00:30", at(11, 5, 15, 0))
        assertTimed("tomorrow 3pm", at(11, 3, 15, 0))
        assertTimed("tomorrow at 3pm", at(11, 3, 15, 0))
        assertTimed("3pm tomorrow", at(11, 3, 15, 0))
        assertTimed("tomorrow half past 5", at(11, 3, 5, 30))
        assertTimed("next friday at 9:30 am", at(11, 6, 9, 30))
        assertTimed("tonight", at(11, 2, 21, 0))
        assertTimed("Nov 5, 2026 at 15:00", at(11, 5, 15, 0))
    }

    @Test
    fun `an explicit past date stays where the user put it`() {
        assertDateOnly("2026-01-15", 1, 15)
        assertTimed("today 8am", at(11, 2, 8, 0))
    }

    @Test
    fun `offset timestamps convert to the local zone`() {
        assertTimed("2026-11-05T14:00:00Z", at(11, 5, 15, 0))
    }

    @Test
    fun `relative durations count from now`() {
        assertTimed("in 30 minutes", at(11, 2, 10, 30))
        assertTimed("in 2 hours", at(11, 2, 12, 0))
        assertTimed("in 1 hour and 30 minutes", at(11, 2, 11, 30))
        assertTimed("in 1h30m", at(11, 2, 11, 30))
    }

    @Test
    fun `relative durations with an unread part are null rather than shortened`() {
        listOf("in 1 day and 2 hours", "in 2 weeks 3 hours", "in 2 hours or so").forEach {
            assertNull(it, EventTimeParser.parseMoment(it, now))
        }
    }

    @Test
    fun `dates without a time are date only`() {
        assertDateOnly("2026-11-05", 11, 5)
        assertDateOnly("today", 11, 2)
        assertDateOnly("tomorrow", 11, 3)
        assertDateOnly("day after tomorrow", 11, 4)
        assertDateOnly("friday", 11, 6)
        assertDateOnly("by Friday", 11, 6)
        assertDateOnly("monday", 11, 2)
        assertDateOnly("next monday", 11, 9)
        assertDateOnly("November 5", 11, 5)
        assertDateOnly("5th of nov", 11, 5)
        assertDateOnly("Nov 5, 2027", 11, 5, 2027)
    }

    @Test
    fun `month and day already past roll to next year`() {
        assertDateOnly("Jan 15", 1, 15, 2027)
    }

    @Test
    fun `unreadable text is null rather than a guess`() {
        listOf("", "whenever", "sometime soon", "2026-13-40", "Feb 31", "25:00", "in a while").forEach {
            assertNull(it, EventTimeParser.parseMoment(it, now))
        }
    }

    @Test
    fun `clock times keep the formats SET_ALARM accepts`() {
        assertEquals(5 to 0, EventTimeParser.parseClockTime("5 am"))
        assertEquals(17 to 30, EventTimeParser.parseClockTime("17:30"))
        assertEquals(0 to 0, EventTimeParser.parseClockTime("12am"))
        assertEquals(12 to 0, EventTimeParser.parseClockTime("noon"))
        assertEquals(5 to 45, EventTimeParser.parseClockTime("quarter to 6"))
        assertEquals(7 to 0, EventTimeParser.parseClockTime("7"))
        assertNull(EventTimeParser.parseClockTime("25:00"))
    }

    @Test
    fun `half past and quarter forms honour am and pm`() {
        assertEquals(18 to 30, EventTimeParser.parseClockTime("half past 6 pm"))
        assertEquals(18 to 30, EventTimeParser.parseClockTime("half past 6pm"))
        assertEquals(18 to 30, EventTimeParser.parseClockTime("half past 6 p.m."))
        assertEquals(6 to 30, EventTimeParser.parseClockTime("half past 6 am"))
        assertEquals(18 to 15, EventTimeParser.parseClockTime("quarter past 6 pm"))
        assertEquals(19 to 45, EventTimeParser.parseClockTime("quarter to 8 pm"))
        assertEquals(7 to 45, EventTimeParser.parseClockTime("quarter to 8 am"))
    }

    @Test
    fun `half past and quarter forms read 12 am as 0 and 12 pm as 12`() {
        assertEquals(0 to 30, EventTimeParser.parseClockTime("half past 12 am"))
        assertEquals(12 to 30, EventTimeParser.parseClockTime("half past 12 pm"))
        assertEquals(0 to 15, EventTimeParser.parseClockTime("quarter past 12 am"))
        assertEquals(23 to 45, EventTimeParser.parseClockTime("quarter to 12 am"))
        assertEquals(11 to 45, EventTimeParser.parseClockTime("quarter to 12 pm"))
        assertEquals(12 to 45, EventTimeParser.parseClockTime("quarter to 1 pm"))
        assertEquals(0 to 45, EventTimeParser.parseClockTime("quarter to 1 am"))
    }

    @Test
    fun `half past and quarter forms honour the part of the day`() {
        assertEquals(6 to 30, EventTimeParser.parseClockTime("half past 6 in the morning"))
        assertEquals(15 to 15, EventTimeParser.parseClockTime("quarter past 3 in the afternoon"))
        assertEquals(18 to 30, EventTimeParser.parseClockTime("half past 6 in the evening"))
        assertEquals(18 to 30, EventTimeParser.parseClockTime("half past 6 this evening"))
        assertEquals(21 to 30, EventTimeParser.parseClockTime("half past 9 at night"))
        assertEquals(20 to 45, EventTimeParser.parseClockTime("quarter to 9 at night"))
        // Night runs past midnight, so the small hours stay AM.
        assertEquals(0 to 30, EventTimeParser.parseClockTime("half past 12 at night"))
        assertEquals(1 to 30, EventTimeParser.parseClockTime("half past 1 at night"))
    }

    @Test
    fun `half past and quarter forms read 12 with a part of the day`() {
        assertEquals(11 to 45, EventTimeParser.parseClockTime("quarter to 12 in the morning"))
        assertEquals(11 to 45, EventTimeParser.parseClockTime("quarter to 12 in the afternoon"))
        assertEquals(23 to 45, EventTimeParser.parseClockTime("quarter to 12 in the evening"))
        assertEquals(23 to 45, EventTimeParser.parseClockTime("quarter to 12 at night"))
        assertEquals(0 to 30, EventTimeParser.parseClockTime("half past 12 in the morning"))
        assertEquals(0 to 15, EventTimeParser.parseClockTime("quarter past 12 in the morning"))
        assertEquals(12 to 30, EventTimeParser.parseClockTime("half past 12 in the afternoon"))
        assertEquals(0 to 30, EventTimeParser.parseClockTime("half past 12 in the evening"))
        assertEquals(0 to 45, EventTimeParser.parseClockTime("quarter to 1 in the morning"))
        assertEquals(12 to 45, EventTimeParser.parseClockTime("quarter to 1 in the afternoon"))
    }

    @Test
    fun `half past and quarter forms without a suffix stay as written`() {
        assertEquals(6 to 30, EventTimeParser.parseClockTime("half past 6"))
        assertEquals(14 to 30, EventTimeParser.parseClockTime("at half past 14"))
        assertEquals(5 to 15, EventTimeParser.parseClockTime("quarter past 5"))
        assertEquals(23 to 45, EventTimeParser.parseClockTime("quarter to 0"))
    }

    @Test
    fun `half past and quarter forms with anything unread are null rather than a guess`() {
        listOf(
            "half past 6 every weekday",
            "quarter to 8 pm on tuesdays",
            "half past 6 amazing",
            "half past 18 pm",
            "quarter to 13 am",
            "half past 24",
            "quarter to 30"
        ).forEach { assertNull(it, EventTimeParser.parseClockTime(it)) }
    }

    @Test
    fun `dated half past forms keep their pm`() {
        assertTimed("half past 6 pm", at(11, 2, 18, 30))
        assertTimed("tomorrow half past 6 pm", at(11, 3, 18, 30))
        assertTimed("quarter to 8 pm tomorrow", at(11, 3, 19, 45))
        assertTimed("tomorrow half past 6 in the evening", at(11, 3, 18, 30))
    }

    @Test
    fun `repeat schedules map to recurrence rules`() {
        assertEquals("FREQ=DAILY", EventTimeParser.parseRepeatRule("daily")?.rule)
        assertEquals("FREQ=DAILY", EventTimeParser.parseRepeatRule("Every day")?.rule)
        assertEquals("FREQ=WEEKLY", EventTimeParser.parseRepeatRule("weekly")?.rule)
        assertEquals("FREQ=MONTHLY", EventTimeParser.parseRepeatRule("every month")?.rule)
        assertEquals("FREQ=YEARLY", EventTimeParser.parseRepeatRule("annually")?.rule)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", EventTimeParser.parseRepeatRule("weekdays")?.rule)
        assertEquals("FREQ=WEEKLY;BYDAY=WE", EventTimeParser.parseRepeatRule("every Wednesday")?.rule)
    }

    @Test
    fun `no-repeat words have no rule and unknown schedules are null`() {
        val none = EventTimeParser.parseRepeatRule("none")
        assertNull(none?.rule)
        assertFalse(none == null)
        assertTrue(EventTimeParser.parseRepeatRule("when it rains") == null)
    }
}
