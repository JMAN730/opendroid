package com.opendroid.ai.social.core.ai

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CampaignPostTimeTest {

    private val zone = ZoneId.of("Europe/Stockholm")

    private fun at(text: String): Long = Instant.parse(text).toEpochMilli()

    private fun time(now: String, dayOffset: Int): Instant =
        Instant.ofEpochMilli(SocialCampaignGenerator.campaignPostTime(at(now), dayOffset, zone))

    @Test
    fun `a post is scheduled for 10 00 local time on its day`() {
        // 18:00 in Stockholm: the old now + 10h landed at 04:00 the next morning.
        assertEquals(Instant.parse("2026-11-04T09:00:00Z"), time("2026-11-02T17:00:00Z", dayOffset = 2))
    }

    @Test
    fun `a day 0 post generated before 10 00 goes out at 10 00 that day`() {
        assertEquals(Instant.parse("2026-11-02T09:00:00Z"), time("2026-11-02T07:00:00Z", dayOffset = 0))
    }

    @Test
    fun `a day 0 post generated after 10 00 waits for the next morning instead of being due already`() {
        assertEquals(Instant.parse("2026-11-03T09:00:00Z"), time("2026-11-02T17:00:00Z", dayOffset = 0))
    }

    @Test
    fun `a negative day offset is treated as day 0`() {
        assertEquals(time("2026-11-02T07:00:00Z", dayOffset = 0), time("2026-11-02T07:00:00Z", dayOffset = -5))
    }

    @Test
    fun `10 00 stays 10 00 across a daylight saving change`() {
        // Stockholm leaves summer time on 2026-10-25, so 10:00 moves from 08:00Z to 09:00Z.
        assertEquals(Instant.parse("2026-10-26T09:00:00Z"), time("2026-10-23T07:00:00Z", dayOffset = 3))
    }
}
