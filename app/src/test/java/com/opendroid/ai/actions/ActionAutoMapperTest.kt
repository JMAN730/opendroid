package com.opendroid.ai.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionAutoMapperTest {

    private val mapper = ActionAutoMapper()
    private val registered = setOf(
        "MAKE_CALL", "SEND_SMS", "SEND_WHATSAPP", "SEND_TELEGRAM", "OPEN_TELEGRAM", "SEND_EMAIL",
        "READ_MESSAGES", "READ_EMAILS", "OPEN_APP", "WEB_SEARCH"
    )
    private val mom = mapOf("contact" to "Mom")

    private fun map(action: String, params: Map<String, String> = mom) =
        mapper.mapAction(action, params, registered)

    @Test
    fun `reading a channel runs the read action for that channel`() {
        mapOf(
            "READ_SMS" to "sms",
            "READSMS" to "sms",
            "SMS_READ" to "sms",
            "GET_SMS" to "sms",
            "CHECK_MESSAGES" to "sms",
            "READ_TEXTS" to "sms",
            "SHOW_MESSAGES" to "sms",
            "READ_WHATSAPP_MESSAGES" to "whatsapp",
            "LIST_WHATSAPP_CHATS" to "whatsapp",
            "READ_TELEGRAM" to "telegram",
            "FETCH_TELEGRAM_MESSAGES" to "telegram"
        ).forEach { (name, app) ->
            val mapping = map(name)
            assertEquals(name, "READ_MESSAGES", mapping.mappedAction)
            assertEquals(name, app, mapping.mappedParams["app"])
        }
    }

    @Test
    fun `an app named in the plan wins over the one inferred from the action name`() {
        assertEquals("telegram", map("READ_MESSAGE", mapOf("app" to "telegram")).mappedParams["app"])
    }

    @Test
    fun `reading or opening email runs the read action instead of composing`() {
        listOf("READ_EMAIL", "CHECK_EMAIL", "GET_MAIL", "SHOW_GMAIL", "OPEN_EMAIL", "LAUNCH_GMAIL").forEach { name ->
            assertEquals(name, "READ_EMAILS", map(name).mappedAction)
        }
    }

    @Test
    fun `lookups with no read action stay unknown instead of calling or sending`() {
        listOf(
            "GET_PHONE_NUMBER",
            "getPhoneNumber",
            "FIND_PHONE_NUMBER",
            "LOOK_UP_PHONE_NUMBER",
            "PHONE_NUMBER",
            "READ_CALL_LOG",
            "GET_CALL_HISTORY",
            "CHECK_MISSED_CALLS",
            "CHECK_PHONE_NUMBER",
            "MISSED_CALLS",
            "GET_EMAIL_ADDRESS"
        ).forEach { name ->
            val mapping = map(name)
            assertNull(name, mapping.mappedAction)
            assertFalse(name, mapping.wasMapped)
        }
    }

    @Test
    fun `an unsupported channel is unknown rather than a carrier SMS or WhatsApp`() {
        listOf(
            "SEND_SIGNAL",
            "SEND_SIGNAL_MESSAGE",
            "SIGNAL_MESSAGE",
            "SEND_DISCORD",
            "SEND_TEXT_ON_SIGNAL",
            "SEND_POSTCARD"
        ).forEach { name ->
            val mapping = map(name, mapOf("contact" to "Mom", "message" to "hi"))
            assertNull(name, mapping.mappedAction)
            assertFalse(name, mapping.wasMapped)
        }
    }

    @Test
    fun `open aliases open the app and never call or send`() {
        mapOf(
            "OPEN_DIALER" to "Phone",
            "OPEN_PHONE" to "Phone",
            "OPEN_WHATSAPP" to "WhatsApp",
            "OPEN_WHATSAPP_CHAT" to "WhatsApp",
            "LAUNCH_WHATSAPP" to "WhatsApp",
            "OPEN_MESSAGES" to "Messages",
            "OPEN_SMS" to "Messages"
        ).forEach { (name, app) ->
            val mapping = map(name)
            assertEquals(name, "OPEN_APP", mapping.mappedAction)
            assertEquals(name, mapOf("appName" to app), mapping.mappedParams)
        }
    }

    @Test
    fun `open, launch and start wording never reaches a call or send rule`() {
        mapOf(
            "OPEN_DIALER_APP" to "Phone",
            "LAUNCH_PHONE" to "Phone",
            "START_CALL" to "Phone",
            "START_WHATSAPP_CHAT" to "WhatsApp",
            "LAUNCH_SMS_APP" to "Messages",
            "START_TELEGRAM" to "Telegram"
        ).forEach { (name, app) ->
            val mapping = map(name)
            assertEquals(name, "OPEN_APP", mapping.mappedAction)
            assertEquals(name, app, mapping.mappedParams["appName"])
        }
    }

    @Test
    fun `an explicit app name is kept when opening an app`() {
        assertEquals("Signal", map("OPEN_SIGNAL", mapOf("appName" to "Signal")).mappedParams["appName"])
        assertEquals("Maps", map("LAUNCH_APP", mapOf("app" to "Maps")).mappedParams["appName"])
    }

    @Test
    fun `words that only contain a call keyword do not place a call`() {
        listOf("TOGGLE_MICROPHONE", "DISMISS_DIALOG").forEach { name ->
            assertTrue(name, map(name, emptyMap()).mappedAction != "MAKE_CALL")
        }
    }

    @Test
    fun `display wording is not mistaken for music once it skips the call rule`() {
        assertNull(map("DISPLAY_CALL_LOG").mappedAction)
    }

    @Test
    fun `existing send and call mappings still hold`() {
        mapOf(
            "SEND_TEXT" to "SEND_SMS",
            "SEND_TEXT_MESSAGE" to "SEND_SMS",
            "SEND_TEXT_TO_MOM" to "SEND_SMS",
            "TEXT" to "SEND_SMS",
            "SMS" to "SEND_SMS",
            "SMS_SEND" to "SEND_SMS",
            "SMS_MOM" to "SEND_SMS",
            "WHATSAPP" to "SEND_WHATSAPP",
            "SEND_WHATSAPP_MESSAGE" to "SEND_WHATSAPP",
            "WHATSAPP_MOM" to "SEND_WHATSAPP",
            "SEND_MESSAGE" to "SEND_WHATSAPP",
            "SEND_TELEGRAM_MESSAGE" to "SEND_TELEGRAM",
            "EMAIL" to "SEND_EMAIL",
            "SEND_MAIL" to "SEND_EMAIL",
            "CALL" to "MAKE_CALL",
            "CALL_MOM" to "MAKE_CALL",
            "DIAL_CONTACT" to "MAKE_CALL",
            "DIAL_MOM" to "MAKE_CALL",
            "PHONE_MOM" to "MAKE_CALL",
            "SEND_SMS_TO_BRIDGET" to "SEND_SMS"
        ).forEach { (name, expected) ->
            assertEquals(name, expected, map(name).mappedAction)
        }
    }

    @Test
    fun `read requests outside the known verbs never become a send`() {
        listOf(
            "SUMMARIZE_SMS",
            "UNREAD_SMS",
            "QUERY_SMS",
            "SMS_HISTORY",
            "SMS_THREAD",
            "SMS_SENT",
            "SENT_SMS",
            "SUMMARIZE_MESSAGES",
            "UNREAD_MESSAGES",
            "RECENT_MESSAGES",
            "MESSAGE_HISTORY",
            "SUMMARIZE_EMAILS",
            "EMAIL_INBOX",
            "SUMMARIZE_WHATSAPP",
            "TELEGRAM_UNREAD",
            "MESSAGE_HISTORY_ON_TELEGRAM",
            "GET_SEND_TEXT_HISTORY"
        ).forEach { name ->
            assertNull(name, map(name).mappedAction)
        }
    }

    @Test
    fun `lookup and open names outside the known verbs never place a call`() {
        listOf(
            "phoneNumber",
            "PHONENUMBER",
            "callLog",
            "CALLLOG",
            "callHistory",
            "CALLHISTORY",
            "CONTACT_PHONE",
            "RECENT_CALLS",
            "PHONE_BOOK",
            "CALL_RECORDS",
            "GO_TO_DIALER",
            "NAVIGATE_TO_DIALER",
            "ANSWER_CALL"
        ).forEach { name ->
            assertNull(name, map(name).mappedAction)
        }
    }

    @Test
    fun `a channel led name only acts when the rest of it is the recipient`() {
        assertEquals("MAKE_CALL", map("CALL_BRIDGET", mapOf("contact" to "Bridget")).mappedAction)
        assertEquals("MAKE_CALL", map("callMom").mappedAction)
        assertEquals("MAKE_CALL", map("PHONE_CALL_MOM").mappedAction)
        assertEquals("MAKE_CALL", map("CALL_5551234", mapOf("number" to "5551234")).mappedAction)
        assertEquals("SEND_WHATSAPP", map("MESSAGE_ALEX", mapOf("contact" to "Alex Smith")).mappedAction)
        assertEquals("SEND_TELEGRAM", map("TELEGRAM_BOB", mapOf("username" to "bob")).mappedAction)
        assertEquals("SEND_EMAIL", map("EMAIL_THE_BOSS", mapOf("to" to "Boss")).mappedAction)

        assertNull(map("CALL_BRIDGET").mappedAction)
        assertNull(map("CALL_MOM", emptyMap()).mappedAction)
    }

    @Test
    fun `an explicit send or call verb still resolves to the channel it names`() {
        mapOf(
            "sendSms" to "SEND_SMS",
            "sendWhatsApp" to "SEND_WHATSAPP",
            "SEND_SMS_TO_MOM" to "SEND_SMS",
            "COMPOSE_MESSAGE" to "SEND_WHATSAPP",
            "REPLY_TO_EMAIL" to "SEND_EMAIL",
            "SMS_TO_MOM" to "SEND_SMS",
            "WHATSAPP_SEND_MOM" to "SEND_WHATSAPP",
            "MAKE_A_CALL" to "MAKE_CALL",
            "PLACE_CALL_TO_MOM" to "MAKE_CALL"
        ).forEach { (name, expected) ->
            assertEquals(name, expected, map(name).mappedAction)
        }
    }

    @Test
    fun `a read word inside the recipient's name does not turn a send into a read`() {
        val johnRead = mapOf("contact" to "John Read")
        mapOf(
            "SEND_SMS_TO_JOHN_READ" to "SEND_SMS",
            "SEND_WHATSAPP_TO_JOHN_READ" to "SEND_WHATSAPP",
            "SEND_EMAIL_TO_JOHN_READ" to "SEND_EMAIL",
            "SMS_TO_JOHN_READ" to "SEND_SMS"
        ).forEach { (name, expected) ->
            assertEquals(name, expected, map(name, johnRead).mappedAction)
        }
    }

    @Test
    fun `the canonical name for history uses the step's params, as execution does`() {
        val bob = mapOf("contact" to "Bob", "to" to "bob@example.com", "subject" to "Hi", "body" to "Secret")
        assertEquals("SEND_EMAIL", map("EMAIL_BOB", bob).mappedAction)
        assertEquals("SEND_EMAIL", mapper.canonicalActionName("EMAIL_BOB", bob, registered))
        assertEquals("SEND_EMAIL", mapper.canonicalActionName("send-email", emptyMap(), registered))
    }

    @Test
    fun `schema actions pass through untouched`() {
        listOf("MAKE_CALL", "SEND_SMS", "SEND_WHATSAPP", "READ_MESSAGES", "OPEN_TELEGRAM").forEach { name ->
            assertEquals(name, name, map(name).mappedAction)
        }
    }
}
