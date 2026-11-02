package com.opendroid.ai.ui.screens.social

import com.opendroid.ai.social.domain.model.SocialPlatform
import org.junit.Assert.*
import org.junit.Test

class ConnectCredentialsTest {

    @Test
    fun `a Discord webhook URL is stored as the webhook, not as a bot token`() {
        val url = "https://discord.com/api/webhooks/123/abc"

        val creds = connectCredentials(SocialPlatform.DISCORD, " $url ", channelOrChatId = "", useSandbox = false)

        assertEquals(url, creds.webhookUrl)
        assertNull(creds.botToken)
    }

    @Test
    fun `a Discord bot token is still sent as a bot token`() {
        val creds = connectCredentials(SocialPlatform.DISCORD, "MTIz.bot.token", channelOrChatId = "42", useSandbox = false)

        assertEquals("MTIz.bot.token", creds.botToken)
        assertNull(creds.webhookUrl)
        assertEquals("42", creds.channelOrTargetId)
    }

    @Test
    fun `a Telegram bot token is a bot token`() {
        val creds = connectCredentials(SocialPlatform.TELEGRAM, "123:abc", channelOrChatId = "@channel", useSandbox = false)

        assertEquals("123:abc", creds.botToken)
        assertNull(creds.webhookUrl)
    }
}
