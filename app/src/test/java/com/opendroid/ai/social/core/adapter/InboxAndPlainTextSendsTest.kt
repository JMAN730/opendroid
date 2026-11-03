package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Telegram and X adapters put on the wire for posts, inbox polling and inbox replies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxAndPlainTextSendsTest {

    private val requests = mutableListOf<Request>()

    @Volatile
    private var responseBody = """{"ok":true,"result":{"message_id":7}}"""

    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            requests.add(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("stub")
                .body(responseBody.toResponseBody())
                .build()
        })
        .build()

    private val telegram = SocialCredentials(
        accountId = "tg",
        platform = SocialPlatform.TELEGRAM,
        accessToken = "123:real",
        botToken = "123:real",
        channelOrTargetId = "@channel"
    )

    private fun Request.jsonBody(): JSONObject =
        JSONObject(Buffer().also { body!!.writeTo(it) }.readUtf8())

    @Test
    fun `a Telegram post is sent as plain text so underscores and asterisks can't break it`() = runBlocking {
        val post = SocialPost(id = "p", accountId = "tg", platform = SocialPlatform.TELEGRAM, content = "thanks @alex_tech for the *early* test")

        assertTrue(TelegramAdapter(client).publishPost(telegram, post).isSuccess)

        val body = requests.single().jsonBody()
        assertFalse("parse_mode makes Telegram reject unbalanced entities", body.has("parse_mode"))
        assertEquals(post.content, body.getString("text"))
    }

    @Test
    fun `Telegram inbox polling asks only for updates after the last one it saw`() = runBlocking {
        val adapter = TelegramAdapter(client)
        responseBody = """
            {"ok":true,"result":[
              {"update_id":41,"message":{"message_id":3,"date":1,"text":"hi","chat":{"id":555},"from":{"first_name":"Ann"}}},
              {"update_id":42,"edited_message":{"message_id":3}}
            ]}
        """.trimIndent()

        val first = adapter.fetchInbox(telegram, null).getOrThrow()
        adapter.acknowledgeInbox(telegram)
        responseBody = """{"ok":true,"result":[]}"""
        adapter.fetchInbox(telegram, null)

        assertNull(requests[0].url.queryParameter("offset"))
        assertEquals("43", requests[1].url.queryParameter("offset"))
        assertEquals("555:3", first.single().replyTargetId)
    }

    @Test
    fun `Telegram inbox polling fetches a batch again until it has been saved`() = runBlocking {
        val adapter = TelegramAdapter(client)
        responseBody = """{"ok":true,"result":[{"update_id":41,"message":{"message_id":3,"date":1,"text":"hi","chat":{"id":555}}}]}"""

        adapter.fetchInbox(telegram, null)
        adapter.fetchInbox(telegram, null)

        assertNull("an unsaved batch must not be confirmed to Telegram", requests[1].url.queryParameter("offset"))
    }

    @Test
    fun `a Telegram inbox reply answers the message in the chat it came from`() = runBlocking {
        val item = SocialInteraction(
            id = "tg_upd_41",
            platform = SocialPlatform.TELEGRAM,
            accountId = "tg",
            authorName = "Ann",
            content = "hi",
            replyTargetId = "555:3"
        )

        assertTrue(TelegramAdapter(client).replyToInteraction(telegram, item, "hello").isSuccess)

        val body = requests.single().jsonBody()
        assertEquals("555", body.getString("chat_id"))
        assertEquals(3L, body.getLong("reply_to_message_id"))
        assertEquals("hello", body.getString("text"))
    }

    @Test
    fun `an X inbox reply answers the mention tweet`() = runBlocking {
        val credentials = SocialCredentials(accountId = "x", platform = SocialPlatform.X, accessToken = "real")
        responseBody = """{"data":[{"id":"9001","text":"@us hello"}]}"""
        val mention = XAdapter(client).fetchInbox(credentials, null).getOrThrow().single()
        responseBody = """{"data":{"id":"9002"}}"""

        assertTrue(XAdapter(client).replyToInteraction(credentials, mention, "thanks").isSuccess)

        val body = requests.last().jsonBody()
        assertEquals("9001", body.getJSONObject("reply").getString("in_reply_to_tweet_id"))
    }

    @Test
    fun `an inbox item with nothing to reply to fails without calling the platform`() = runBlocking {
        val item = SocialInteraction(id = "tg_upd_1", platform = SocialPlatform.TELEGRAM, accountId = "tg", authorName = "Ann", content = "hi")

        val result = TelegramAdapter(client).replyToInteraction(telegram, item, "hello")

        assertTrue(result.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `inbox replies on platforms without an API call fail without calling the platform`() = runBlocking {
        val credentials = SocialCredentials(accountId = "li", platform = SocialPlatform.LINKEDIN, accessToken = "real")
        val item = SocialInteraction(id = "li_1", platform = SocialPlatform.LINKEDIN, accountId = "li", authorName = "Ann", content = "hi", replyTargetId = "1")

        val result = LinkedInAdapter(client).replyToInteraction(credentials, item, "hello")

        assertEquals("Replying to LinkedIn inbox messages isn't supported yet", result.exceptionOrNull()?.message)
        assertTrue(requests.isEmpty())
    }
}
