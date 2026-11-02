package com.opendroid.ai.social.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.security.SocialCredentialStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.SocialCommentEntity
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.adapter.*
import com.opendroid.ai.social.core.audit.SocialAuditLogger
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SocialManagerIdempotencyTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: SocialRepository
    private lateinit var manager: SocialManager

    private val sends = AtomicInteger(0)

    @Volatile
    private var lastRequestUrl: String? = null

    @Volatile
    private var duringSend: () -> Unit = {}

    @Volatile
    private var responseCode = 200

    private object FakeCredentialStore : SocialCredentialStore {
        override fun getCredentials(accountId: String, platform: SocialPlatform) = SocialCredentials(
            accountId = accountId,
            platform = platform,
            accessToken = "real-token",
            botToken = if (accountId.contains(SANDBOX)) "mock_token" else "123:$accountId",
            channelOrTargetId = "@channel"
        )
        override fun saveCredentials(credentials: SocialCredentials) = true
        override fun removeCredentials(accountId: String, platform: SocialPlatform) = true
        override fun hasCredentials(accountId: String, platform: SocialPlatform) = true
        override fun clearAll() = true
    }

    // Stands in for the network: the request is delivered whatever the calling coroutine does next.
    private val fakePlatform = Interceptor { chain ->
        sends.incrementAndGet()
        lastRequestUrl = chain.request().url.toString()
        duringSend()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message("stub")
            .body(TELEGRAM_OK.toResponseBody())
            .build()
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SocialRepository(
            database.socialAccountDao(),
            database.socialPostDao(),
            database.socialCommentDao(),
            database.socialInteractionDao(),
            database.socialAnalyticsDao(),
            database.socialCampaignDao(),
            database.socialAutomationRuleDao(),
            database.socialAuditLogDao()
        )
        val client = OkHttpClient.Builder().addInterceptor(fakePlatform).build()
        manager = SocialManager(
            context,
            repository,
            FakeCredentialStore,
            SocialAuditLogger(repository),
            MemoryRepository(database.memoryDao(), database.taskHistoryDao(), database.macroDao()),
            TelegramAdapter(client),
            DiscordAdapter(client),
            XAdapter(client),
            LinkedInAdapter(client),
            YouTubeAdapter(client),
            InstagramAdapter(client),
            FacebookAdapter(client)
        )
        runBlocking {
            repository.saveAccount(
                SocialAccount(ACCOUNT_ID, SocialPlatform.TELEGRAM, "bot", "Bot", status = AccountStatus.CONNECTED, permissions = SEND_PERMISSIONS)
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun savePost(status: PostStatus = PostStatus.DRAFT): String {
        repository.savePost(
            SocialPost(
                id = POST_ID,
                accountId = ACCOUNT_ID,
                platform = SocialPlatform.TELEGRAM,
                content = "hello",
                status = status,
                scheduledPublishTime = if (status == PostStatus.SCHEDULED) 1L else null,
                requiresApproval = false
            )
        )
        return POST_ID
    }

    private suspend fun saveComment(status: CommentReplyStatus = CommentReplyStatus.NONE): String {
        repository.saveComment(
            SocialComment(
                id = COMMENT_ID,
                platformCommentId = "42",
                postId = null,
                platform = SocialPlatform.TELEGRAM,
                authorName = "someone",
                content = "a question",
                suggestedReply = "suggested",
                replyStatus = status
            )
        )
        return COMMENT_ID
    }

    // Runs block and cancels its job while the request is on the wire, as leaving the screen does.
    private suspend fun cancelledDuringSend(block: suspend () -> Unit) {
        lateinit var job: Job
        duringSend = {
            duringSend = {}
            job.cancel()
        }
        job = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.LAZY) { block() }
        job.start()
        job.join()
    }

    private suspend fun comment(id: String): SocialCommentEntity =
        database.socialCommentDao().getCommentById(id)!!

    private suspend fun audit(action: String) =
        repository.getRecentAuditLogs().filter { it.action == action }

    // ── THE TICKET'S REPRO ───────────────────────────────────────────────────

    @Test
    fun `leaving the screen mid-publish still records the post that went live`() = runBlocking {
        val id = savePost()

        cancelledDuringSend { manager.publishPost(id, "USER") }

        assertEquals(1, sends.get())
        val post = repository.getPostById(id)!!
        assertEquals(PostStatus.PUBLISHED, post.status)
        assertEquals("7", post.platformPostId)
        assertNotNull(post.publishedTime)
        assertEquals(1, audit("PUBLISHED_POST").size)
    }

    @Test
    fun `publishing again after leaving the screen does not post twice`() = runBlocking {
        val id = savePost()
        cancelledDuringSend { manager.publishPost(id, "USER") }

        val retry = manager.publishPost(id, "USER")

        assertTrue(retry.isFailure)
        assertEquals(1, sends.get())
    }

    @Test
    fun `a second publish while the first is on the wire does not post twice`() = runBlocking {
        val id = savePost(PostStatus.SCHEDULED)
        var second: Result<PublishPostResult>? = null
        duringSend = {
            duringSend = {}
            second = runBlocking { manager.publishPost(id, "USER") }
        }

        val first = manager.publishPost(id, "USER")

        assertTrue(first.isSuccess)
        assertTrue(second!!.isFailure)
        assertEquals(1, sends.get())
        assertEquals(PostStatus.PUBLISHED, repository.getPostById(id)!!.status)
    }

    @Test
    fun `leaving the screen mid-reply still records the reply that went out`() = runBlocking {
        val id = saveComment()

        cancelledDuringSend { manager.replyToComment(id, "thanks") }

        assertEquals(1, sends.get())
        val saved = comment(id)
        assertEquals(CommentReplyStatus.REPLIED.name, saved.replyStatus)
        assertTrue(saved.isAnswered)
        assertEquals("thanks", saved.actualReply)
        assertEquals(1, audit("SENT_REPLY").size)
    }

    @Test
    fun `replying again after leaving the screen does not reply twice`() = runBlocking {
        val id = saveComment()
        cancelledDuringSend { manager.replyToComment(id, "thanks") }

        val retry = manager.replyToComment(id, "thanks")

        assertTrue(retry.isFailure)
        assertEquals(1, sends.get())
    }

    @Test
    fun `a second reply while the first is on the wire does not reply twice`() = runBlocking {
        val id = saveComment()
        var second: Result<ReplyCommentResult>? = null
        duringSend = {
            duringSend = {}
            second = runBlocking { manager.replyToComment(id, "thanks") }
        }

        val first = manager.replyToComment(id, "thanks")

        assertTrue(first.isSuccess)
        assertTrue(second!!.isFailure)
        assertEquals(1, sends.get())
    }

    // ── POSTS: THE PUBLISHING STATE ──────────────────────────────────────────

    @Test
    fun `a post left in PUBLISHING by process death is never re-sent automatically`() = runBlocking {
        val id = savePost(PostStatus.PUBLISHING)

        val retry = manager.publishPost(id, "USER")

        assertTrue(retry.isFailure)
        assertEquals(0, sends.get())
        assertEquals(PostStatus.PUBLISHING, repository.getPostById(id)!!.status)
        assertTrue(repository.getDueScheduledPosts(Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `a post that already went out or was cancelled can not be published`() = runBlocking {
        for (status in listOf(PostStatus.PUBLISHED, PostStatus.CANCELLED)) {
            val id = savePost(status)

            assertTrue(manager.publishPost(id, "USER").isFailure)
            assertEquals(status, repository.getPostById(id)!!.status)
        }
        assertEquals(0, sends.get())
    }

    @Test
    fun `a rejected publish lands in FAILED and Retry sends it once more`() = runBlocking {
        val id = savePost()
        responseCode = 500

        assertTrue(manager.publishPost(id, "USER").isFailure)

        assertEquals(PostStatus.FAILED, repository.getPostById(id)!!.status)
        assertNotNull(repository.getPostById(id)!!.errorMessage)

        responseCode = 200
        assertTrue(manager.publishPost(id, "USER").isSuccess)

        assertEquals(2, sends.get())
        val post = repository.getPostById(id)!!
        assertEquals(PostStatus.PUBLISHED, post.status)
        assertNull(post.errorMessage)
    }

    @Test
    fun `a publish that hits a missing account leaves the post publishable`() = runBlocking {
        repository.savePost(
            SocialPost(id = POST_ID, accountId = "gone", platform = SocialPlatform.X, content = "hello")
        )

        assertTrue(manager.publishPost(POST_ID, "USER").isFailure)

        assertEquals(PostStatus.DRAFT, repository.getPostById(POST_ID)!!.status)
        assertEquals(0, sends.get())
    }

    @Test
    fun `approve and publish immediately sends once and ends PUBLISHED`() = runBlocking {
        val id = savePost()

        val approved = manager.approvePost(id, publishImmediately = true)

        assertTrue(approved.isSuccess)
        assertEquals(PostStatus.PUBLISHED, approved.getOrThrow().status)
        assertEquals(1, sends.get())
        assertEquals("7", repository.getPostById(id)!!.platformPostId)
    }

    @Test
    fun `approve and publish immediately stays unpublished when the send fails`() = runBlocking {
        val id = savePost()
        responseCode = 500

        assertTrue(manager.approvePost(id, publishImmediately = true).isFailure)

        assertEquals(PostStatus.FAILED, repository.getPostById(id)!!.status)
    }

    @Test
    fun `approving or scheduling can not put a sent or unsettled post back in the queue`() = runBlocking {
        for (status in listOf(PostStatus.PUBLISHING, PostStatus.PUBLISHED)) {
            val id = savePost(status)

            assertTrue(manager.approvePost(id).isFailure)
            assertTrue(manager.schedulePost(id, 1L).isFailure)

            assertEquals(status, repository.getPostById(id)!!.status)
        }
    }

    @Test
    fun `the user can settle an interrupted publish as not live and then retry`() = runBlocking {
        val id = savePost(PostStatus.PUBLISHING)

        assertTrue(manager.resolveInterruptedPublish(id, wasPublished = false).isSuccess)

        assertEquals(PostStatus.FAILED, repository.getPostById(id)!!.status)
        assertEquals(0, sends.get())
        assertEquals(1, audit("RESOLVED_INTERRUPTED_PUBLISH").size)

        assertTrue(manager.publishPost(id, "USER").isSuccess)
        assertEquals(1, sends.get())
    }

    @Test
    fun `the user can settle an interrupted publish as live`() = runBlocking {
        val id = savePost(PostStatus.PUBLISHING)

        assertTrue(manager.resolveInterruptedPublish(id, wasPublished = true).isSuccess)

        val post = repository.getPostById(id)!!
        assertEquals(PostStatus.PUBLISHED, post.status)
        assertNotNull(post.publishedTime)
        assertEquals(0, sends.get())
    }

    @Test
    fun `a publish still on the wire can not be settled by the user`() = runBlocking {
        val id = savePost()
        var settled: Result<Unit>? = null
        duringSend = {
            duringSend = {}
            settled = runBlocking { manager.resolveInterruptedPublish(id, wasPublished = false) }
        }

        assertTrue(manager.publishPost(id, "USER").isSuccess)

        assertTrue(settled!!.isFailure)
        assertEquals(PostStatus.PUBLISHED, repository.getPostById(id)!!.status)
    }

    @Test
    fun `only a post in PUBLISHING can be settled`() = runBlocking {
        val id = savePost(PostStatus.DRAFT)

        assertTrue(manager.resolveInterruptedPublish(id, wasPublished = true).isFailure)

        assertEquals(PostStatus.DRAFT, repository.getPostById(id)!!.status)
    }

    // ── REPLIES: THE SENDING STATE ───────────────────────────────────────────

    @Test
    fun `a comment left in SENDING by process death is never replied to again`() = runBlocking {
        val id = saveComment(CommentReplyStatus.SENDING)

        assertTrue(manager.replyToComment(id, "thanks").isFailure)

        assertEquals(0, sends.get())
        assertEquals(CommentReplyStatus.SENDING.name, comment(id).replyStatus)
    }

    @Test
    fun `a new suggested reply can not reopen a comment in SENDING`() = runBlocking {
        val id = saveComment(CommentReplyStatus.SENDING)

        repository.updateSuggestedReply(id, "another draft")

        assertEquals(CommentReplyStatus.SENDING.name, comment(id).replyStatus)
    }

    @Test
    fun `a rejected reply releases the comment so it can be sent again`() = runBlocking {
        val id = saveComment(CommentReplyStatus.SUGGESTED)
        responseCode = 500

        assertTrue(manager.replyToComment(id, "thanks").isFailure)

        val released = comment(id)
        assertEquals(CommentReplyStatus.SUGGESTED.name, released.replyStatus)
        assertNull(released.actualReply)
        assertFalse(released.isAnswered)
        assertEquals(1, audit("REPLY_FAILED").size)

        responseCode = 200
        assertTrue(manager.replyToComment(id, "thanks").isSuccess)
        assertEquals(2, sends.get())
        assertEquals(CommentReplyStatus.REPLIED.name, comment(id).replyStatus)
    }

    @Test
    fun `the user can settle an interrupted reply as not posted and then reply`() = runBlocking {
        val id = saveComment(CommentReplyStatus.SENDING)

        assertTrue(manager.resolveInterruptedReply(id, wasSent = false).isSuccess)

        assertEquals(CommentReplyStatus.SUGGESTED.name, comment(id).replyStatus)
        assertEquals(0, sends.get())
        assertEquals(1, audit("RESOLVED_INTERRUPTED_REPLY").size)

        assertTrue(manager.replyToComment(id, "thanks").isSuccess)
        assertEquals(1, sends.get())
    }

    @Test
    fun `the user can settle an interrupted reply as posted`() = runBlocking {
        val id = saveComment(CommentReplyStatus.SENDING)

        assertTrue(manager.resolveInterruptedReply(id, wasSent = true).isSuccess)

        val saved = comment(id)
        assertEquals(CommentReplyStatus.REPLIED.name, saved.replyStatus)
        assertTrue(saved.isAnswered)
        assertNotNull(saved.replyTimestamp)
        assertEquals(0, sends.get())
    }

    @Test
    fun `a reply still on the wire can not be settled by the user`() = runBlocking {
        val id = saveComment()
        var settled: Result<Unit>? = null
        duringSend = {
            duringSend = {}
            settled = runBlocking { manager.resolveInterruptedReply(id, wasSent = false) }
        }

        assertTrue(manager.replyToComment(id, "thanks").isSuccess)

        assertTrue(settled!!.isFailure)
        assertEquals(CommentReplyStatus.REPLIED.name, comment(id).replyStatus)
    }

    // ── SENDS THE ADAPTER DOESN'T IMPLEMENT ──────────────────────────────────

    @Test
    fun `a YouTube publish with a real token ends FAILED with no fake post id`() = runBlocking {
        repository.saveAccount(
            SocialAccount(YOUTUBE_ACCOUNT_ID, SocialPlatform.YOUTUBE, "channel", "Channel", status = AccountStatus.CONNECTED, permissions = SEND_PERMISSIONS)
        )
        repository.savePost(
            SocialPost(
                id = POST_ID,
                accountId = YOUTUBE_ACCOUNT_ID,
                platform = SocialPlatform.YOUTUBE,
                content = "hello",
                requiresApproval = false
            )
        )

        val result = manager.publishPost(POST_ID, "USER")

        assertEquals("Publishing to YouTube isn't supported yet", result.exceptionOrNull()?.message)
        assertEquals(0, sends.get())
        val saved = repository.getPostById(POST_ID)!!
        assertEquals(PostStatus.FAILED, saved.status)
        assertNull(saved.platformPostId)
        assertEquals("Publishing to YouTube isn't supported yet", saved.errorMessage)
        assertTrue(audit("PUBLISHED_POST").isEmpty())
        assertEquals(1, audit("PUBLISH_FAILED").size)
    }

    @Test
    fun `a LinkedIn reply with a real token isn't marked replied`() = runBlocking {
        assertUnsentReply(SocialPlatform.LINKEDIN, "Replying to LinkedIn comments isn't supported yet")
    }

    @Test
    fun `a YouTube reply with a real token isn't marked replied`() = runBlocking {
        assertUnsentReply(SocialPlatform.YOUTUBE, "Replying to YouTube comments isn't supported yet")
    }

    private suspend fun assertUnsentReply(platform: SocialPlatform, expectedMessage: String) {
        repository.saveAccount(
            SocialAccount("${platform.name}_acct", platform, "account", "Account", status = AccountStatus.CONNECTED, permissions = SEND_PERMISSIONS)
        )
        repository.saveComment(
            SocialComment(
                id = COMMENT_ID,
                platformCommentId = "42",
                postId = null,
                platform = platform,
                authorName = "someone",
                content = "a question"
            )
        )

        val result = manager.replyToComment(COMMENT_ID, "thanks")

        assertEquals(expectedMessage, result.exceptionOrNull()?.message)
        assertEquals(0, sends.get())
        assertEquals(CommentReplyStatus.NONE.name, comment(COMMENT_ID).replyStatus)
        assertTrue(audit("SENT_REPLY").isEmpty())
        assertEquals(1, audit("REPLY_FAILED").size)
    }

    // ── PERMISSIONS ──────────────────────────────────────────────────────────

    private suspend fun connectSandboxTelegram(permissions: List<SocialPermission>): SocialAccount =
        manager.connectAccount(
            SocialPlatform.TELEGRAM,
            SocialCredentials(accountId = SANDBOX, platform = SocialPlatform.TELEGRAM, accessToken = "mock_token"),
            permissions
        ).getOrThrow()

    @Test
    fun `publishing without the publish permission fails and sends nothing`() = runBlocking {
        val account = connectSandboxTelegram(listOf(SocialPermission.READ_POSTS))
        val draft = manager.createDraft(account.id, SocialPlatform.TELEGRAM, "hello")

        val result = manager.publishPost(draft.id, "USER")

        assertEquals(
            "@${account.username} doesn't have the Publish Posts permission. Reconnect the account to turn it on.",
            result.exceptionOrNull()?.message
        )
        assertEquals(0, sends.get())
        val saved = repository.getPostById(draft.id)!!
        assertEquals(PostStatus.DRAFT, saved.status)
        assertNull(saved.platformPostId)
        assertTrue(audit("PUBLISHED_POST").isEmpty())
    }

    @Test
    fun `replying without the reply permission fails and leaves the comment answerable`() = runBlocking {
        val account = connectSandboxTelegram(listOf(SocialPermission.READ_COMMENTS))
        val id = saveCommentFor(account.id)

        val result = manager.replyToComment(id, "thanks")

        assertEquals(
            "@${account.username} doesn't have the Reply to Comments permission. Reconnect the account to turn it on.",
            result.exceptionOrNull()?.message
        )
        assertEquals(0, sends.get())
        val saved = comment(id)
        assertEquals(CommentReplyStatus.NONE.name, saved.replyStatus)
        assertFalse(saved.isAnswered)
        assertTrue(audit("SENT_REPLY").isEmpty())
    }

    @Test
    fun `an account with both send permissions can publish and reply`() = runBlocking {
        val account = connectSandboxTelegram(SEND_PERMISSIONS)
        val draft = manager.createDraft(account.id, SocialPlatform.TELEGRAM, "hello")

        assertTrue(manager.publishPost(draft.id, "USER").isSuccess)
        assertTrue(manager.replyToComment(saveCommentFor(account.id), "thanks").isSuccess)

        assertEquals(PostStatus.PUBLISHED, repository.getPostById(draft.id)!!.status)
    }

    // ── WHICH ACCOUNT ────────────────────────────────────────────────────────

    private suspend fun saveAccount(
        id: String,
        connectedAt: Long,
        status: AccountStatus = AccountStatus.CONNECTED,
        platform: SocialPlatform = SocialPlatform.TELEGRAM
    ) {
        repository.saveAccount(
            SocialAccount(id, platform, id, id, status = status, permissions = SEND_PERMISSIONS, connectedAt = connectedAt)
        )
    }

    private suspend fun saveCommentFor(accountId: String?): String {
        repository.saveComment(
            SocialComment(
                id = COMMENT_ID,
                platformCommentId = "42",
                postId = null,
                platform = SocialPlatform.TELEGRAM,
                authorName = "someone",
                content = "a question",
                accountId = accountId
            )
        )
        return COMMENT_ID
    }

    @Test
    fun `platform lookup skips a disconnected account`() = runBlocking {
        // A is inserted first and connected last, so neither row order nor recency can pick B.
        saveAccount("a", connectedAt = 2000L, status = AccountStatus.DISCONNECTED, platform = SocialPlatform.X)
        saveAccount("b", connectedAt = 1000L, platform = SocialPlatform.X)

        assertEquals("b", repository.getAccountByPlatform(SocialPlatform.X)?.id)
    }

    @Test
    fun `platform lookup picks the most recently connected account`() = runBlocking {
        saveAccount("a", connectedAt = 1000L, platform = SocialPlatform.X)
        saveAccount("b", connectedAt = 3000L, platform = SocialPlatform.X)
        saveAccount("c", connectedAt = 2000L, platform = SocialPlatform.X)

        assertEquals("b", repository.getAccountByPlatform(SocialPlatform.X)?.id)
    }

    @Test
    fun `platform lookup finds nothing when every account is disconnected`() = runBlocking {
        saveAccount("a", connectedAt = 1000L, status = AccountStatus.DISCONNECTED, platform = SocialPlatform.X)

        assertNull(repository.getAccountByPlatform(SocialPlatform.X))
    }

    @Test
    fun `a draft made after swapping accounts publishes through the new account`() = runBlocking {
        database.socialAccountDao().deleteById(ACCOUNT_ID)
        saveAccount("a", connectedAt = 1000L)
        manager.disconnectAccount("a", SocialPlatform.TELEGRAM)
        saveAccount("b", connectedAt = 500L)

        val attachedTo = repository.getAccountByPlatform(SocialPlatform.TELEGRAM)!!.id
        val draft = manager.createDraft(attachedTo, SocialPlatform.TELEGRAM, "hello")

        assertTrue(manager.publishPost(draft.id, "USER").isSuccess)
        assertEquals("b", attachedTo)
        assertTrue(lastRequestUrl!!.contains("123:b"))
    }

    @Test
    fun `a reply goes through the account the comment came from`() = runBlocking {
        database.socialAccountDao().deleteById(ACCOUNT_ID)
        saveAccount("a", connectedAt = 1000L)
        saveAccount("b", connectedAt = 2000L)
        val id = saveCommentFor("b")
        assertTrue(manager.replyToComment(id, "thanks").isSuccess)
        assertTrue(lastRequestUrl!!.contains("123:b"))

        // Stored on A, the older row, which the platform lookup would never pick.
        database.socialCommentDao().deleteById(id)
        assertTrue(manager.replyToComment(saveCommentFor("a"), "thanks").isSuccess)

        assertTrue(lastRequestUrl!!.contains("123:a"))
    }

    @Test
    fun `a comment from before the column existed replies through the newest connected account`() = runBlocking {
        database.socialAccountDao().deleteById(ACCOUNT_ID)
        saveAccount("a", connectedAt = 2000L, status = AccountStatus.DISCONNECTED)
        saveAccount("b", connectedAt = 1000L)
        val id = saveCommentFor(null)

        assertTrue(manager.replyToComment(id, "thanks").isSuccess)

        assertTrue(lastRequestUrl!!.contains("123:b"))
    }

    @Test
    fun `a reply never moves to another account when the comment's account is gone`() = runBlocking {
        saveAccount("disconnected", connectedAt = 1000L, status = AccountStatus.DISCONNECTED)

        for (owner in listOf("disconnected", "revoked")) {
            val id = saveCommentFor(owner)

            assertTrue(manager.replyToComment(id, "thanks").isFailure)

            assertEquals(CommentReplyStatus.NONE.name, comment(id).replyStatus)
        }
        assertEquals(0, sends.get())
    }

    @Test
    fun `syncing stores the account on each fetched comment`() = runBlocking {
        val account = connectSandboxTelegram(SEND_PERMISSIONS)

        manager.syncAccount(account.id)

        assertEquals(account.id, comment("tg_comm_1").accountId)
    }

    private companion object {
        const val SANDBOX = "sandbox"
        val SEND_PERMISSIONS = listOf(SocialPermission.PUBLISH_POSTS, SocialPermission.REPLY_TO_COMMENTS)
        const val ACCOUNT_ID = "acct"
        const val YOUTUBE_ACCOUNT_ID = "yt_acct"
        const val POST_ID = "post"
        const val COMMENT_ID = "comment"
        const val TELEGRAM_OK = """{"ok":true,"result":{"message_id":7}}"""
    }
}
