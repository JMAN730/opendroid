package com.opendroid.ai.core.agent

import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoReplyChannelTest {
    private val smsOnly = AutoReplyConfig(globalEnabled = true, smsEnabled = true)
    private val telegramOnly = AutoReplyConfig(globalEnabled = true, telegramEnabled = true)

    private fun notification(
        packageName: String,
        category: String = "MESSAGE",
        contactName: String? = "Alice"
    ) = NotificationEntity(
        packageName = packageName,
        appName = packageName,
        title = "Alice",
        text = "hello",
        category = category,
        contactName = contactName
    )

    @Test
    fun `the SMS toggle does not reply in other CATEGORY_MESSAGE apps`() {
        listOf(
            "org.thoughtcrime.securesms",
            "com.Slack",
            "com.microsoft.teams",
            "com.facebook.orca",
            "com.instagram.android",
            "com.google.android.apps.dynamite"
        ).forEach { packageName ->
            assertNull(packageName, replyChannelFor(notification(packageName), smsOnly, defaultSmsPackage = null))
        }
    }

    @Test
    fun `an app posting CATEGORY_MESSAGE is not enabled by having every toggle on`() {
        val everythingOn = AutoReplyConfig(
            globalEnabled = true,
            whatsappEnabled = true,
            telegramEnabled = true,
            discordEnabled = true,
            smsEnabled = true,
            emailEnabled = true
        )

        assertNull(replyChannelFor(notification("org.thoughtcrime.securesms"), everythingOn, defaultSmsPackage = null))
    }

    @Test
    fun `the SMS toggle replies in the known SMS apps`() {
        listOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        ).forEach { packageName ->
            assertEquals(
                packageName,
                AutoReplyChannel.SMS,
                replyChannelFor(notification(packageName), smsOnly, defaultSmsPackage = null)
            )
        }
    }

    @Test
    fun `the SMS toggle replies in the default SMS app`() {
        val channel = replyChannelFor(
            notification("org.thoughtcrime.securesms"),
            smsOnly,
            defaultSmsPackage = "org.thoughtcrime.securesms"
        )

        assertEquals(AutoReplyChannel.SMS, channel)
    }

    @Test
    fun `the default SMS app is off when the SMS toggle is off`() {
        val channel = replyChannelFor(
            notification("org.thoughtcrime.securesms"),
            telegramOnly,
            defaultSmsPackage = "org.thoughtcrime.securesms"
        )

        assertNull(channel)
    }

    @Test
    fun `a default SMS app notification that is not a message gets no reply`() {
        val channel = replyChannelFor(
            notification("org.thoughtcrime.securesms", category = "OTHER"),
            smsOnly,
            defaultSmsPackage = "org.thoughtcrime.securesms"
        )

        assertNull(channel)
    }

    @Test
    fun `Telegram X follows the Telegram toggle`() {
        val telegramX = notification("org.thunderdog.challegram")

        assertEquals(AutoReplyChannel.TELEGRAM, replyChannelFor(telegramX, telegramOnly, defaultSmsPackage = null))
        assertNull(replyChannelFor(telegramX, smsOnly, defaultSmsPackage = null))
    }

    @Test
    fun `a package that only has a messenger's name in it gets that messenger's toggle`() {
        val everyMessenger = AutoReplyConfig(
            globalEnabled = true,
            whatsappEnabled = true,
            telegramEnabled = true,
            discordEnabled = true
        )

        listOf("com.example.telegram", "com.example.whatsapp", "com.example.discord").forEach { packageName ->
            assertNull(packageName, replyChannelFor(notification(packageName), everyMessenger, defaultSmsPackage = null))
        }
    }

    @Test
    fun `each messenger follows its own toggle`() {
        assertEquals(
            AutoReplyChannel.WHATSAPP,
            replyChannelFor(
                notification("com.whatsapp.w4b"),
                AutoReplyConfig(globalEnabled = true, whatsappEnabled = true),
                defaultSmsPackage = null
            )
        )
        assertEquals(
            AutoReplyChannel.TELEGRAM,
            replyChannelFor(notification("org.telegram.messenger", category = "OTHER"), telegramOnly, defaultSmsPackage = null)
        )
        assertEquals(
            AutoReplyChannel.DISCORD,
            replyChannelFor(
                notification("com.discord", category = "SOCIAL"),
                AutoReplyConfig(globalEnabled = true, discordEnabled = true),
                defaultSmsPackage = null
            )
        )
        assertNull(replyChannelFor(notification("com.whatsapp"), smsOnly, defaultSmsPackage = null))
    }

    @Test
    fun `email category notifications follow the email toggle`() {
        val gmail = notification("com.google.android.gm", category = "EMAIL")

        assertEquals(
            AutoReplyChannel.EMAIL,
            replyChannelFor(gmail, AutoReplyConfig(globalEnabled = true, emailEnabled = true), defaultSmsPackage = null)
        )
        assertNull(replyChannelFor(gmail, smsOnly, defaultSmsPackage = null))
    }

    @Test
    fun `the global switch turns every channel off`() {
        val channel = replyChannelFor(
            notification("com.google.android.apps.messaging"),
            smsOnly.copy(globalEnabled = false),
            defaultSmsPackage = null
        )

        assertNull(channel)
    }

    @Test
    fun `a bare name on the blacklist still blocks that name in every app`() {
        val channel = replyChannelFor(
            notification("com.android.mms"),
            smsOnly.copy(blacklistedContacts = setOf("alice")),
            defaultSmsPackage = null
        )

        assertNull(channel)
    }

    @Test
    fun `a whitelist limits replies to its contacts`() {
        val sms = notification("com.android.mms")

        assertNull(
            replyChannelFor(sms, smsOnly.copy(whitelistedContacts = setOf("Bob")), defaultSmsPackage = null)
        )
        assertEquals(
            AutoReplyChannel.SMS,
            replyChannelFor(sms, smsOnly.copy(whitelistedContacts = setOf("com.android.mms:alice")), defaultSmsPackage = null)
        )
    }

    @Test
    fun `a whitelist entry admits its contact only in its own app`() {
        val config = AutoReplyConfig(
            globalEnabled = true,
            whatsappEnabled = true,
            telegramEnabled = true,
            whitelistedContacts = setOf("com.whatsapp:Alice")
        )

        assertEquals(AutoReplyChannel.WHATSAPP, replyChannelFor(notification("com.whatsapp"), config, defaultSmsPackage = null))
        assertNull(replyChannelFor(notification("org.telegram.messenger"), config, defaultSmsPackage = null))
    }

    @Test
    fun `a bare name on the whitelist admits nobody, because any sender can take that name`() {
        val config = telegramOnly.copy(whitelistedContacts = setOf("Alice"))

        assertNull(replyChannelFor(notification("org.telegram.messenger"), config, defaultSmsPackage = null))
    }

    @Test
    fun `a sender cannot reach a whitelist entry by putting it in their name`() {
        val config = telegramOnly.copy(whitelistedContacts = setOf("com.whatsapp:Alice"))
        val forged = notification("org.telegram.messenger", contactName = "com.whatsapp:Alice")

        assertNull(replyChannelFor(forged, config, defaultSmsPackage = null))
    }

    @Test
    fun `a blacklist entry blocks its contact only in its own app`() {
        val config = AutoReplyConfig(
            globalEnabled = true,
            whatsappEnabled = true,
            telegramEnabled = true,
            blacklistedContacts = setOf("com.whatsapp:alice")
        )

        assertNull(replyChannelFor(notification("com.whatsapp"), config, defaultSmsPackage = null))
        assertEquals(AutoReplyChannel.TELEGRAM, replyChannelFor(notification("org.telegram.messenger"), config, defaultSmsPackage = null))
    }
}
