package com.opendroid.ai.core.agent

import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig

internal val WHATSAPP_PACKAGES = setOf(
    "com.whatsapp", "com.whatsapp.w4b"
)
internal val TELEGRAM_PACKAGES = setOf(
    "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"
)
internal val DISCORD_PACKAGES = setOf(
    "com.discord"
)
internal val SMS_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.android.mms",
    "com.samsung.android.messaging"
)

/** The settings toggle a notification's replies belong to. */
internal enum class AutoReplyChannel { WHATSAPP, TELEGRAM, DISCORD, EMAIL, SMS }

/** The channel to auto-reply on, or null if this notification must not get an auto-reply. */
internal fun replyChannelFor(
    notification: NotificationEntity,
    config: AutoReplyConfig,
    defaultSmsPackage: String?
): AutoReplyChannel? {
    if (!config.globalEnabled) return null

    // Check per-app enablement
    val channel = when {
        notification.packageName in WHATSAPP_PACKAGES -> AutoReplyChannel.WHATSAPP
        notification.packageName in TELEGRAM_PACKAGES -> AutoReplyChannel.TELEGRAM
        notification.packageName in DISCORD_PACKAGES -> AutoReplyChannel.DISCORD
        notification.category == "EMAIL" -> AutoReplyChannel.EMAIL
        // Any app can post CATEGORY_MESSAGE, so that alone never enables the SMS toggle
        notification.category == "MESSAGE" &&
            (notification.packageName in SMS_PACKAGES || notification.packageName == defaultSmsPackage) ->
            AutoReplyChannel.SMS
        else -> return null
    }
    val appEnabled = when (channel) {
        AutoReplyChannel.WHATSAPP -> config.whatsappEnabled
        AutoReplyChannel.TELEGRAM -> config.telegramEnabled
        AutoReplyChannel.DISCORD -> config.discordEnabled
        AutoReplyChannel.EMAIL -> config.emailEnabled
        AutoReplyChannel.SMS -> config.smsEnabled
    }
    if (!appEnabled) return null

    // Check blacklist. A bare name still blocks in every app: wrongly staying silent is harmless.
    val contact = notification.contactName ?: notification.title
    val entry = "${notification.packageName}:$contact"
    if (config.blacklistedContacts.any { it.equals(entry, ignoreCase = true) || it.equals(contact, ignoreCase = true) }) {
        return null
    }

    // Check whitelist (if set, only reply to whitelisted contacts). Only an entry naming the app
    // counts: a sender in any app can take a bare name, and that must not let them in.
    if (config.whitelistedContacts.isNotEmpty()) {
        if (!config.whitelistedContacts.any { it.equals(entry, ignoreCase = true) }) {
            return null
        }
    }

    return channel
}
