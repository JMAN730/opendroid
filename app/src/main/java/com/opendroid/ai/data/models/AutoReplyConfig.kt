package com.opendroid.ai.data.models

data class AutoReplyConfig(
    val globalEnabled: Boolean = true,
    val whatsappEnabled: Boolean = true,
    val smsEnabled: Boolean = true,
    val emailEnabled: Boolean = true,
    val replyDelayMinutes: Int = 15,
    val blacklistedContacts: Set<String> = emptySet(),
    val whitelistedContacts: Set<String> = emptySet(),
    val customPrompt: String? = null,
    val maxRepliesPerContactPerHour: Int = 3
)
