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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Overlapping refreshes of one Telegram account must not acknowledge a batch they did not save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SocialManagerInboxOverlapTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var repository: SocialRepository
    private lateinit var manager: SocialManager

    private val polls = java.util.Collections.synchronizedList(mutableListOf<String?>())
    private val secondPollStarted = CountDownLatch(1)

    // The first getUpdates answers with update 41 only after giving a second poll the chance to
    // start; a second one then sees 41 and 42, as Telegram would while 41 is still unconfirmed.
    private val fakePlatform = Interceptor { chain ->
        val url = chain.request().url
        val reply = if (url.encodedPath.endsWith("/getUpdates")) {
            val index = synchronized(polls) { polls.add(url.queryParameter("offset")); polls.size }
            if (index == 1) {
                secondPollStarted.await(500, TimeUnit.MILLISECONDS)
                UPDATE_41
            } else {
                secondPollStarted.countDown()
                if (url.queryParameter("offset") == null) UPDATES_41_42 else """{"ok":true,"result":[]}"""
            }
        } else {
            """{"ok":true,"result":[]}"""
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("stub")
            .body(reply.toResponseBody())
            .build()
    }

    private object RealTokenStore : SocialCredentialStore {
        override fun getCredentials(accountId: String, platform: SocialPlatform) = SocialCredentials(
            accountId = accountId,
            platform = platform,
            accessToken = "123:real",
            botToken = "123:real"
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
            RealTokenStore,
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

    @Test
    fun `overlapping syncs of one Telegram account do not acknowledge a batch they did not save`() = runBlocking {
        repository.saveAccount(
            SocialAccount("tg", SocialPlatform.TELEGRAM, "tg", "tg", status = AccountStatus.CONNECTED, connectedAt = 1000L)
        )

        val first = async(Dispatchers.IO) { manager.syncAccount("tg") }
        val second = async(Dispatchers.IO) { manager.syncAccount("tg") }
        first.await()
        second.await()

        assertEquals(
            "the later poll must start after the earlier batch was saved and confirmed",
            listOf<String?>(null, "42"),
            polls.toList()
        )
    }

    private companion object {
        const val UPDATE_41 = """{"ok":true,"result":[{"update_id":41,"message":{"message_id":3,"date":1,"text":"hi","chat":{"id":555}}}]}"""
        const val UPDATES_41_42 = """{"ok":true,"result":[{"update_id":41,"message":{"message_id":3,"date":1,"text":"hi","chat":{"id":555}}},{"update_id":42,"message":{"message_id":4,"date":2,"text":"yo","chat":{"id":555}}}]}"""
    }
}
