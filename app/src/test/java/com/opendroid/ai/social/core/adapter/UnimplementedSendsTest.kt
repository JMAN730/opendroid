package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube publishing and YouTube/LinkedIn replies have no API call behind them, so a real token
 * has to get a failure. Reporting success here would mark a post live that nobody can see.
 */
class UnimplementedSendsTest {

    private val calls = AtomicInteger(0)

    private val client = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("stub")
                .body("{}".toResponseBody())
                .build()
        })
        .build()

    private fun credentials(platform: SocialPlatform, accessToken: String = "ya29.real") =
        SocialCredentials(accountId = "acct", platform = platform, accessToken = accessToken)

    private fun post(platform: SocialPlatform) =
        SocialPost(id = "post", accountId = "acct", platform = platform, content = "hello")

    private fun assertUnsupported(result: Result<*>, expectedMessage: String) {
        val error = result.exceptionOrNull()
        assertTrue("Expected a failure but got $result", error is UnsupportedOperationException)
        assertEquals(expectedMessage, error?.message)
        assertEquals("Nothing should reach the platform", 0, calls.get())
    }

    @Test
    fun `YouTube publish with a real token fails without calling the platform`() = runBlocking {
        val result = YouTubeAdapter(client).publishPost(credentials(SocialPlatform.YOUTUBE), post(SocialPlatform.YOUTUBE))

        assertUnsupported(result, "Publishing to YouTube isn't supported yet")
    }

    @Test
    fun `YouTube reply with a real token fails without calling the platform`() = runBlocking {
        val result = YouTubeAdapter(client).replyToComment(credentials(SocialPlatform.YOUTUBE), "c1", "thanks")

        assertUnsupported(result, "Replying to YouTube comments isn't supported yet")
    }

    @Test
    fun `LinkedIn reply with a real token fails without calling the platform`() = runBlocking {
        val result = LinkedInAdapter(client).replyToComment(credentials(SocialPlatform.LINKEDIN), "c1", "thanks")

        assertUnsupported(result, "Replying to LinkedIn comments isn't supported yet")
    }

    @Test
    fun `sandbox tokens keep their demo results`() = runBlocking {
        val youTube = YouTubeAdapter(client)
        val linkedIn = LinkedInAdapter(client)
        val ytCredentials = credentials(SocialPlatform.YOUTUBE, "sandbox_demo")
        val liCredentials = credentials(SocialPlatform.LINKEDIN, "mock_demo")

        assertTrue(youTube.publishPost(ytCredentials, post(SocialPlatform.YOUTUBE)).isSuccess)
        assertTrue(youTube.replyToComment(ytCredentials, "c1", "thanks").isSuccess)
        assertTrue(linkedIn.replyToComment(liCredentials, "c1", "thanks").isSuccess)
        assertEquals(0, calls.get())
    }
}
