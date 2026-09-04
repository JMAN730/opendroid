package com.opendroid.ai.social.domain.model

import org.junit.Assert.*
import org.junit.Test

class SocialPlatformTest {

    @Test
    fun `all seven platforms have valid ids and display names`() {
        val platforms = SocialPlatform.values()
        assertEquals(7, platforms.size)

        platforms.forEach { platform ->
            assertFalse(platform.id.isBlank())
            assertFalse(platform.displayName.isBlank())
        }
    }

    @Test
    fun `fromId resolves platforms case insensitively`() {
        assertEquals(SocialPlatform.X, SocialPlatform.fromId("x"))
        assertEquals(SocialPlatform.X, SocialPlatform.fromId("X"))
        assertEquals(SocialPlatform.TELEGRAM, SocialPlatform.fromId("telegram"))
        assertEquals(SocialPlatform.TELEGRAM, SocialPlatform.fromId("TELEGRAM"))
        assertEquals(SocialPlatform.DISCORD, SocialPlatform.fromId("discord"))
        assertEquals(SocialPlatform.LINKEDIN, SocialPlatform.fromId("linkedin"))
        assertEquals(SocialPlatform.INSTAGRAM, SocialPlatform.fromId("instagram"))
        assertEquals(SocialPlatform.FACEBOOK, SocialPlatform.fromId("facebook"))
        assertEquals(SocialPlatform.YOUTUBE, SocialPlatform.fromId("youtube"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `fromId throws on unknown platform`() {
        SocialPlatform.fromId("myspace")
    }

    @Test
    fun `automation level has correct display properties`() {
        assertEquals("Safe Mode", AutomationLevel.SAFE.displayName)
        assertEquals("Approval Required", AutomationLevel.APPROVAL.displayName)
        assertEquals("Autonomous Agent", AutomationLevel.AUTONOMOUS.displayName)
    }

    @Test
    fun `post status values are distinct`() {
        val statuses = PostStatus.values().map { it.name }.toSet()
        assertEquals(PostStatus.values().size, statuses.size)
        assertTrue(statuses.contains("DRAFT"))
        assertTrue(statuses.contains("SCHEDULED"))
        assertTrue(statuses.contains("PUBLISHED"))
        assertTrue(statuses.contains("FAILED"))
        assertTrue(statuses.contains("CANCELLED"))
    }
}
