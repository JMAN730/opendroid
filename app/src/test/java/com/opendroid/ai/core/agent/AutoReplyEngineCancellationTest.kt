package com.opendroid.ai.core.agent

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.os.Looper
import android.telephony.SmsManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.ShadowSmsManagerWithSubscription
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProfileStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.core.security.UserProfile
import com.opendroid.ai.core.security.UserProfileStore
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.dao.NotificationDao
import com.opendroid.ai.data.db.entities.NotificationEntity
import com.opendroid.ai.data.models.AutoReplyConfig
import com.opendroid.ai.data.repository.SettingsRepository
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSmsManager
import org.robolectric.util.ReflectionHelpers

/**
 * A second text from the same contact arriving while the first reply is with the radio leaves
 * that send alone: it is recorded, and the newer reply stays pending and cancellable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    application = MigrationTestApplication::class,
    shadows = [ShadowSmsManagerWithSubscription::class]
)
class AutoReplyEngineCancellationTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val shadowSms: ShadowSmsManager = shadowOf(context.getSystemService(SmsManager::class.java))
    private lateinit var database: OpenDroidDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var engine: AutoReplyEngine

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        shadowOf(context.packageManager).setSystemFeature(SMS_FEATURE, true)
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java).build()
        settings = SettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = {
                    Files.createTempDirectory("opendroid-auto-reply-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            providerCredentialStore = NoCredentials,
            runStartupMigration = false
        )
        // A custom reply text means the LLM and memory collaborators are never reached.
        engine = AutoReplyEngine(
            notificationDao = CancellationHonouringDao(database.notificationDao()),
            llmProviderFactory = unused(),
            replyDispatcher = ReplyDispatcher(context),
            settingsRepository = settings,
            userProfileStore = NoProfile
        )
    }

    @After
    fun tearDown() {
        engine.cancelAll()
        database.close()
    }

    @Test
    fun `reply the radio confirms is recorded when a newer message arrives during the send`() = runBlocking {
        val firstId = insertMessage("first")
        val inFlight = startReplyThenReceiveNewerMessage(firstId)

        confirmSend(inFlight.sentIntents)
        withTimeout(WAIT_MS) { inFlight.firstJob.join() }

        val dao = database.notificationDao()
        assertEquals(1, dao.getAutoReplyCountForContact(MESSAGES_PACKAGE, PHONE, 0L))
        val first = dao.getNotificationsForContactInApp(MESSAGES_PACKAGE, PHONE).first { it.id == firstId }
        assertEquals(REPLY, first.autoReplyText)
    }

    @Test
    fun `finishing a cancelled send leaves the newer pending reply cancellable`() = runBlocking {
        val inFlight = startReplyThenReceiveNewerMessage(insertMessage("first"))

        confirmSend(inFlight.sentIntents)
        withTimeout(WAIT_MS) { inFlight.firstJob.join() }

        assertSame(inFlight.newerJob, pendingReplies().registered(CONTACT_KEY))
        engine.cancelPendingReply(OutgoingMessage(MESSAGES_PACKAGE, PHONE, null, null))
        assertTrue(inFlight.newerJob.isCancelled)
    }

    private class InFlightReply(
        val firstJob: Job,
        val newerJob: Job,
        val sentIntents: List<PendingIntent>
    )

    private suspend fun startReplyThenReceiveNewerMessage(firstId: Long): InFlightReply {
        settings.updateAutoReplyConfig(config(replyDelayMinutes = 0))
        engine.scheduleAutoReply(message("first", firstId), null, context)
        val sentIntents = awaitSentIntents()
        // A reply with the radio has already left the pending map, so take it from the engine's scope.
        val firstJob = ReflectionHelpers.getField<CoroutineScope>(engine, "scope")
            .coroutineContext.job.children.single()

        // The newer reply sits out a long delay, so the first is the only one with the radio.
        settings.updateAutoReplyConfig(config(replyDelayMinutes = 15))
        engine.scheduleAutoReply(message("second", insertMessage("second")), null, context)
        assertFalse(firstJob.isCancelled)

        return InFlightReply(firstJob, pendingReplies().registered(CONTACT_KEY)!!, sentIntents)
    }

    private suspend fun awaitSentIntents(): List<PendingIntent> = withTimeout(WAIT_MS) {
        while (shadowSms.lastSentMultipartTextMessageParams == null) delay(10)
        shadowSms.lastSentMultipartTextMessageParams.sentIntents
    }

    private fun confirmSend(sentIntents: List<PendingIntent>) {
        sentIntents.forEach { it.send(context, Activity.RESULT_OK, null) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun insertMessage(text: String): Long =
        database.notificationDao().insertNotification(message(text))

    private fun message(text: String, id: Long = 0L) = NotificationEntity(
        id = id,
        packageName = MESSAGES_PACKAGE,
        appName = "Messages",
        title = PHONE,
        text = text,
        category = "MESSAGE",
        contactName = PHONE
    )

    private fun config(replyDelayMinutes: Int) = AutoReplyConfig(
        globalEnabled = true,
        smsEnabled = true,
        replyDelayMinutes = replyDelayMinutes,
        customReplyText = REPLY
    )

    private fun pendingReplies(): PendingReplies =
        ReflectionHelpers.getField(engine, "pendingReplies")

    /** An instance that fails on first use, for final collaborators a test never reaches. */
    private inline fun <reified T> unused(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, T::class.java) as T
    }

    /**
     * Room 2.8.4 runs the write under the database's own job, so on a cancelled caller it
     * still writes and only then throws. That's an internal detail the engine can't lean on,
     * so this stands in for any suspend call that honours cancellation on entry.
     */
    private class CancellationHonouringDao(
        private val room: NotificationDao
    ) : NotificationDao by room {
        override suspend fun markAsAutoReplied(id: Long, replyText: String) {
            currentCoroutineContext().ensureActive()
            room.markAsAutoReplied(id, replyText)
        }
    }

    /** The custom reply text means the profile is never read. */
    private object NoProfile : UserProfileStore {
        override fun read(): ProfileStoreResult<UserProfile?> = error("profile is not read")
        override fun write(profile: UserProfile): ProfileStoreResult<Unit> = error("profile is not written")
        override fun migrateLegacyProfile(): ProfileStoreResult<Unit> = error("profile is not migrated")
        override fun resetForReentry(): ProfileStoreResult<Unit> = error("profile is not reset")
    }

    private object NoCredentials : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(null)

        override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
            CredentialStoreResult.Success(emptyMap())

        override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)

        override fun resetForReentry(): CredentialStoreResult<Unit> =
            CredentialStoreResult.Success(Unit)
    }

    private companion object {
        const val PHONE = "+15551234567"
        const val MESSAGES_PACKAGE = "com.google.android.apps.messaging"
        const val CONTACT_KEY = "$MESSAGES_PACKAGE:$PHONE"
        const val REPLY = "Busy, back soon"
        const val SMS_FEATURE = "android.hardware.telephony.messaging"
        const val WAIT_MS = 10_000L
    }
}
