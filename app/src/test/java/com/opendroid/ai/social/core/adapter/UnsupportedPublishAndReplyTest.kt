package com.opendroid.ai.social.core.adapter

import com.opendroid.ai.social.domain.model.SocialCredentials
import com.opendroid.ai.social.domain.model.SocialPlatform
import com.opendroid.ai.social.domain.model.SocialPost
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for #93: with a real (non-sandbox) token, YouTube publish and the
 * YouTube/LinkedIn reply paths used to return a fabricated success without touching the
 * network, which SocialManager then recorded as PUBLISHED / SENT_REPLY. They must now
 * fail honestly and still make zero HTTP calls.
 */
class UnsupportedPublishAndReplyTest {

    private val callCount = AtomicInteger(0)

    /** Counts every call and answers 200 so an accidental request would look like success. */
    private val countingClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            callCount.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody(null))
                .build()
        })
        .build()

    private fun credentials(platform: SocialPlatform, accessToken: String) = SocialCredentials(
        accountId = "acct_1",
        platform = platform,
        accessToken = accessToken
    )

    private fun post(platform: SocialPlatform) = SocialPost(
        id = "post_1",
        accountId = "acct_1",
        platform = platform,
        content = "Hello from OpenDroid"
    )

    @Test
    fun `YouTube publishPost with real token fails and makes no HTTP call`() = runBlocking {
        val adapter = YouTubeAdapter(countingClient)

        val result = adapter.publishPost(
            credentials(SocialPlatform.YOUTUBE, "ya29.real"),
            post(SocialPlatform.YOUTUBE)
        )

        assertTrue("publish must not report success", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected UnsupportedOperationException, got $error", error is UnsupportedOperationException)
        assertEquals("Publishing to YouTube isn't supported yet", error?.message)
        assertEquals(0, callCount.get())
    }

    @Test
    fun `YouTube replyToComment with real token fails and makes no HTTP call`() = runBlocking {
        val adapter = YouTubeAdapter(countingClient)

        val result = adapter.replyToComment(
            credentials(SocialPlatform.YOUTUBE, "ya29.real"),
            commentId = "yt_c_101",
            text = "Thanks!"
        )

        assertTrue("reply must not report success", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected UnsupportedOperationException, got $error", error is UnsupportedOperationException)
        assertEquals("Replying to YouTube comments isn't supported yet", error?.message)
        assertEquals(0, callCount.get())
    }

    @Test
    fun `LinkedIn replyToComment with real token fails and makes no HTTP call`() = runBlocking {
        val adapter = LinkedInAdapter(countingClient)

        val result = adapter.replyToComment(
            credentials(SocialPlatform.LINKEDIN, "AQX.real"),
            commentId = "li_urn_c1",
            text = "Thanks!"
        )

        assertTrue("reply must not report success", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected UnsupportedOperationException, got $error", error is UnsupportedOperationException)
        assertEquals("Replying to LinkedIn comments isn't supported yet", error?.message)
        assertEquals(0, callCount.get())
    }

    @Test
    fun `sandbox tokens keep the demo success path without HTTP calls`() = runBlocking {
        val youTube = YouTubeAdapter(countingClient)
        val linkedIn = LinkedInAdapter(countingClient)

        val ytPublish = youTube.publishPost(credentials(SocialPlatform.YOUTUBE, "sandbox_yt"), post(SocialPlatform.YOUTUBE))
        val ytReply = youTube.replyToComment(credentials(SocialPlatform.YOUTUBE, "sandbox_yt"), "yt_c_101", "Thanks!")
        val liReply = linkedIn.replyToComment(credentials(SocialPlatform.LINKEDIN, "mock_li"), "li_urn_c1", "Thanks!")

        assertTrue(ytPublish.isSuccess && ytPublish.getOrThrow().success)
        assertTrue(ytReply.isSuccess && ytReply.getOrThrow().success)
        assertTrue(liReply.isSuccess && liReply.getOrThrow().success)
        assertEquals(0, callCount.get())
    }
}
