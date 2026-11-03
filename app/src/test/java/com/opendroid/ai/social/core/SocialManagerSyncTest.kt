package com.opendroid.ai.social.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.security.SocialCredentialStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.adapter.*
import com.opendroid.ai.social.core.audit.SocialAuditLogger
import com.opendroid.ai.social.domain.model.*
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

/** Refresh, re-sync, inbox replies, audit actors and analytics storage across the social module. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SocialManagerSyncTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: SocialRepository
    private lateinit var manager: SocialManager

    private val sends = AtomicInteger(0)

    // Sandbox tokens never reach the network; anything that does is counted.
    private val fakePlatform = Interceptor { chain ->
        sends.incrementAndGet()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("stub")
            .body("""{"ok":true,"result":{"message_id":7}}""".toResponseBody())
            .build()
    }

    private object SandboxCredentialStore : SocialCredentialStore {
        override fun getCredentials(accountId: String, platform: SocialPlatform) = SocialCredentials(
            accountId = accountId,
            platform = platform,
            accessToken = "mock_${platform.id}_token"
        )
        override fun saveCredentials(credentials: SocialCredentials) = true
        override fun removeCredentials(accountId: String, platform: SocialPlatform) = true
        override fun hasCredentials(accountId: String, platform: SocialPlatform) = true
        override fun clearAll() = true
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
            SandboxCredentialStore,
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun saveAccount(
        id: String,
        platform: SocialPlatform,
        status: AccountStatus = AccountStatus.CONNECTED
    ) {
        repository.saveAccount(
            SocialAccount(id, platform, id, id, status = status, permissions = SEND_PERMISSIONS, connectedAt = 1000L)
        )
    }

    private suspend fun interaction(id: String) = database.socialInteractionDao().getInteractionById(id)!!

    private suspend fun comment(id: String) = database.socialCommentDao().getCommentById(id)!!

    private suspend fun audit(action: String) =
        repository.getRecentAuditLogs().filter { it.action == action }

    // ── REFRESH ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh syncs every connected account and skips disconnected ones`() = runBlocking {
        saveAccount("tg", SocialPlatform.TELEGRAM)
        saveAccount("dc", SocialPlatform.DISCORD)
        saveAccount("x", SocialPlatform.X, status = AccountStatus.DISCONNECTED)

        assertTrue(manager.syncAllAccounts().isSuccess)

        assertNotNull(repository.getAccountById("tg")!!.lastSyncAt)
        assertNotNull(repository.getAccountById("dc")!!.lastSyncAt)
        assertNull(repository.getAccountById("x")!!.lastSyncAt)
        assertEquals("tg", interaction("tg_int_1").accountId)
        assertEquals("dc", interaction("dc_int_1").accountId)
        assertNull(database.socialInteractionDao().getInteractionById("x_int_1"))
    }

    // ── RE-SYNC KEEPS TRIAGE ─────────────────────────────────────────────────

    @Test
    fun `a re-sync keeps resolved and dismissed inbox items as they were`() = runBlocking {
        saveAccount("tg", SocialPlatform.TELEGRAM)
        saveAccount("dc", SocialPlatform.DISCORD)
        manager.syncAllAccounts()
        repository.updateInteractionStatus("tg_int_1", "RESOLVED")
        repository.updateInteractionStatus("dc_int_1", "DISMISSED")

        manager.syncAllAccounts()

        assertEquals("RESOLVED", interaction("tg_int_1").status)
        assertEquals("DISMISSED", interaction("dc_int_1").status)
    }

    @Test
    fun `a re-sync keeps a replied comment replied and refuses a second reply`() = runBlocking {
        saveAccount("tg", SocialPlatform.TELEGRAM)
        manager.syncAccount("tg")
        assertTrue(manager.replyToComment("tg_comm_1", "thanks").isSuccess)

        manager.syncAccount("tg")

        val saved = comment("tg_comm_1")
        assertEquals(CommentReplyStatus.REPLIED.name, saved.replyStatus)
        assertTrue(saved.isAnswered)
        assertEquals("thanks", saved.actualReply)
        assertTrue(manager.replyToComment("tg_comm_1", "thanks again").isFailure)
        assertEquals(1, audit("SENT_REPLY").size)
    }

    @Test
    fun `a re-sync updates the text of an item it already has`() = runBlocking {
        repository.saveInteractions(listOf(inboxItem(content = "first")))
        repository.updateInteractionStatus(INBOX_ID, "RESOLVED")

        repository.saveInteractions(listOf(inboxItem(content = "edited")))

        val saved = interaction(INBOX_ID)
        assertEquals("edited", saved.content)
        assertEquals("RESOLVED", saved.status)
    }

    // ── INBOX REPLIES ────────────────────────────────────────────────────────

    private fun inboxItem(content: String = "hi", accountId: String = "tg") = SocialInteraction(
        id = INBOX_ID,
        platform = SocialPlatform.TELEGRAM,
        accountId = accountId,
        authorName = "someone",
        type = InteractionType.DIRECT_MESSAGE,
        content = content,
        replyTargetId = "555:9"
    )

    @Test
    fun `replying to an inbox item sends it and marks the item replied`() = runBlocking {
        saveAccount("tg", SocialPlatform.TELEGRAM)
        repository.saveInteractions(listOf(inboxItem()))

        val result = manager.replyToInteraction(INBOX_ID, "thanks")

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals("REPLIED", interaction(INBOX_ID).status)
        assertEquals(1, audit("SENT_INBOX_REPLY").size)
        assertTrue(manager.replyToInteraction(INBOX_ID, "thanks").isFailure)
    }

    @Test
    fun `an inbox reply needs the reply permission`() = runBlocking {
        repository.saveAccount(
            SocialAccount("tg", SocialPlatform.TELEGRAM, "bot", "Bot", status = AccountStatus.CONNECTED, permissions = listOf(SocialPermission.READ_POSTS))
        )
        repository.saveInteractions(listOf(inboxItem()))

        val result = manager.replyToInteraction(INBOX_ID, "thanks")

        assertEquals(
            "@bot doesn't have the Reply to Comments permission. Reconnect the account to turn it on.",
            result.exceptionOrNull()?.message
        )
        assertEquals("UNREAD", interaction(INBOX_ID).status)
    }

    @Test
    fun `an inbox reply never moves to another account`() = runBlocking {
        saveAccount("other", SocialPlatform.TELEGRAM)
        saveAccount("tg", SocialPlatform.TELEGRAM, status = AccountStatus.DISCONNECTED)
        repository.saveInteractions(listOf(inboxItem()))

        assertTrue(manager.replyToInteraction(INBOX_ID, "thanks").isFailure)

        assertEquals("UNREAD", interaction(INBOX_ID).status)
    }

    // ── AUDIT ACTOR ──────────────────────────────────────────────────────────

    @Test
    fun `a user publishing an unapproved draft is logged as the user`() = runBlocking {
        saveAccount("tg", SocialPlatform.TELEGRAM)
        val draft = manager.createDraft("tg", SocialPlatform.TELEGRAM, "hello")
        assertNull(draft.approvedBy)

        assertTrue(manager.publishPost(draft.id, actor = "USER").isSuccess)

        assertEquals(listOf("USER"), audit("PUBLISHED_POST").map { it.actor })
    }

    // ── ANALYTICS ────────────────────────────────────────────────────────────

    @Test
    fun `an analytics snapshot keeps unknown metrics unknown`() = runBlocking {
        repository.recordAnalyticsSnapshot(
            SocialAnalytics(accountId = "tg", platform = SocialPlatform.TELEGRAM, followersCount = null, postsCount = 12)
        )

        val saved = repository.getLatestSnapshotForPlatform(SocialPlatform.TELEGRAM)!!

        assertNull(saved.followersCount)
        assertNull(saved.engagementRate)
        assertEquals(12, saved.postsCount)
    }

    private companion object {
        val SEND_PERMISSIONS = listOf(SocialPermission.PUBLISH_POSTS, SocialPermission.REPLY_TO_COMMENTS)
        const val INBOX_ID = "tg_upd_1"
    }
}
