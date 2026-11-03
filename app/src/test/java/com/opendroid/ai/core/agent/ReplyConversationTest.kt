package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyConversationTest {
    private val whatsapp = "com.whatsapp"
    private val aliceKey = "0|com.whatsapp|1|alice|10123"
    private val bobKey = "0|com.whatsapp|1|bob|10123"

    private fun active(
        title: String?,
        key: String? = null,
        shortcutId: String? = null,
        conversationTitle: String? = null,
        packageName: String = whatsapp,
        hasReplyAction: Boolean = true
    ) = ActiveConversation(packageName, key, shortcutId, title, conversationTitle, hasReplyAction)

    private fun ReplyConversation.find(vararg active: ActiveConversation) =
        active.firstOrNull { matches(it) }

    @Test
    fun `another contact's notification never stands in for the scheduled contact`() {
        val target = ReplyConversation(whatsapp, "Alice")

        assertNull(target.find(active("Bob")))
    }

    @Test
    fun `a blank contact name matches nothing when two conversations are active`() {
        val target = ReplyConversation(whatsapp, null)

        assertNull(target.find(active("Alice"), active("Bob")))
    }

    @Test
    fun `a blank contact name does not match a notification with a blank title`() {
        val target = ReplyConversation(whatsapp, "")

        assertNull(target.find(active("", conversationTitle = "")))
    }

    @Test
    fun `the scheduled contact is picked from among other conversations`() {
        val target = ReplyConversation(whatsapp, "Alice", aliceKey)
        val alice = active("Alice", key = aliceKey)

        assertEquals(alice, target.find(active("Bob", key = bobKey), alice))
    }

    @Test
    fun `a group is matched on its conversation title`() {
        val target = ReplyConversation(whatsapp, "Family", "0|com.whatsapp|1|family|10123")
        val family = active("Family: Alice", key = "0|com.whatsapp|1|family|10123", conversationTitle = "Family")

        assertEquals(family, target.find(family))
    }

    @Test
    fun `a contact with the same name in a different conversation does not match`() {
        val target = ReplyConversation(whatsapp, "Alex", "0|com.whatsapp|1|alex-a|10123")

        assertNull(target.find(active("Alex", key = "0|com.whatsapp|1|alex-b|10123")))
    }

    @Test
    fun `a notification slot reused for another contact does not match`() {
        val sharedKey = "0|com.example.sms|1|null|10123"
        val target = ReplyConversation("com.example.sms", "Alice", sharedKey)

        assertNull(target.find(active("Bob", key = sharedKey, packageName = "com.example.sms")))
    }

    @Test
    fun `the same shortcut matches even when the title and key changed`() {
        val target = ReplyConversation(whatsapp, "Alice", aliceKey, shortcutId = "alice@chat")
        val renamed = active("Alice (2 messages)", key = "0|com.whatsapp|2|alice|10123", shortcutId = "alice@chat")

        assertEquals(renamed, target.find(renamed))
    }

    @Test
    fun `the same shortcut matches when the contact name is blank`() {
        val target = ReplyConversation(whatsapp, null, shortcutId = "alice@chat")
        val alice = active("Alice", shortcutId = "alice@chat")

        assertEquals(alice, target.find(active("Bob", shortcutId = "bob@chat"), alice))
    }

    @Test
    fun `a different shortcut rejects a notification with the same name and key`() {
        val target = ReplyConversation(whatsapp, "Alice", aliceKey, shortcutId = "alice@chat")

        assertNull(target.find(active("Alice", key = aliceKey, shortcutId = "other@chat")))
    }

    @Test
    fun `a notification without a reply action or from another app does not match`() {
        val target = ReplyConversation(whatsapp, "Alice")

        assertNull(
            target.find(
                active("Alice", hasReplyAction = false),
                active("Alice", packageName = "org.telegram.messenger")
            )
        )
    }
}
