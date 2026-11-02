package com.opendroid.ai.core.voice

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.security.CredentialStoreResult
import com.opendroid.ai.core.security.ProviderCredentialId
import com.opendroid.ai.core.security.ProviderCredentialRecoveryState
import com.opendroid.ai.core.security.ProviderCredentialStore
import com.opendroid.ai.data.repository.SettingsRepository
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource

class TtsTestApplication : Application()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TtsTestApplication::class)
class TextToSpeechEngineTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val createdPlayers = mutableListOf<MediaPlayer>()
    // One speech thread, as on device; tests wait on it rather than on wall-clock time.
    private val speechThread = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        server.start()
        ShadowMediaPlayer.setMediaInfoProvider { _: DataSource -> ShadowMediaPlayer.MediaInfo(CLIP_MS, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> synchronized(createdPlayers) { createdPlayers += player } }
    }

    @After
    fun tearDown() {
        server.close()
        speechThread.shutdownNow()
        ShadowMediaPlayer.resetStaticState()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `an ElevenLabs clip is deleted from cacheDir once it finishes playing`() {
        server.enqueue(mp3Response())
        val done = mutableListOf<String>()
        val engine = newEngine()
        engine.onCompletionListener = { id -> done += id }

        val id = engine.speak("hello")
        awaitPlaying(1)
        assertEquals(1, clipsInCache().size)

        shadowOf(Looper.getMainLooper()).idleFor(CLIP_MS.toLong() + 10, TimeUnit.MILLISECONDS)

        assertEquals(listOf(id), done)
        assertEquals(emptyList<File>(), clipsInCache())
    }

    @Test
    fun `starting a new clip deletes the clip it replaced`() {
        server.enqueue(mp3Response())
        server.enqueue(mp3Response())
        val engine = newEngine()

        engine.speak("first")
        awaitPlaying(1)
        engine.speak("second")
        awaitPlaying(2)

        assertEquals(1, clipsInCache().size)
        engine.destroy()
        assertEquals(emptyList<File>(), clipsInCache())
    }

    @Test
    fun `clips left behind by an earlier process are removed`() {
        val stale = File(context.cacheDir, "elevenlabs_123.mp3").apply { writeText("x") }
        newEngine()
        speechThread.submit { }.get(5, TimeUnit.SECONDS)
        assertFalse(stale.exists())
    }

    @Test
    fun `completion reports the id of the utterance that finished`() {
        server.enqueue(mp3Response())
        server.enqueue(mp3Response())
        val done = mutableListOf<String>()
        val engine = newEngine()
        engine.onCompletionListener = { id -> done += id }

        val first = engine.speak("first")
        awaitPlaying(1)
        shadowOf(Looper.getMainLooper()).idleFor(CLIP_MS.toLong() + 10, TimeUnit.MILLISECONDS)
        engine.speak("Say approve to run, or cancel.", utteranceId = "approval-prompt")
        awaitPlaying(2)

        assertEquals(listOf(first), done)
        shadowOf(Looper.getMainLooper()).idleFor(CLIP_MS.toLong() + 10, TimeUnit.MILLISECONDS)
        assertEquals(listOf(first, "approval-prompt"), done)
    }

    @Test
    fun `stop during an ElevenLabs fetch means the clip never plays`() {
        server.enqueue(mp3Response().newBuilder().headersDelay(500, TimeUnit.MILLISECONDS).build())
        val engine = newEngine()

        engine.speak("a long readout")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        engine.stop()
        // Long enough for the delayed response to arrive and, before the fix, play.
        Thread.sleep(1500)
        speechThread.submit { }.get(5, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("stopped speech must not start playing", synchronized(createdPlayers) { createdPlayers.isEmpty() })
        assertEquals(emptyList<File>(), clipsInCache())
    }

    private fun awaitPlaying(players: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val last = synchronized(createdPlayers) { createdPlayers.takeIf { it.size >= players }?.get(players - 1) }
            if (last != null && last.isPlaying) return
            Thread.sleep(10)
        }
        throw AssertionError("expected $players clip(s) to start playing")
    }

    private fun newEngine() = TextToSpeechEngine(
        context = context,
        settingsRepository = settingsWithElevenLabsKey(),
        client = client,
        elevenLabsBaseUrl = server.url("/").toString().trimEnd('/'),
        dispatcher = speechThread.asCoroutineDispatcher()
    )

    private fun clipsInCache(): List<File> =
        context.cacheDir.listFiles { file -> file.name.startsWith("elevenlabs_") }.orEmpty().toList()

    private fun mp3Response() = MockResponse.Builder()
        .code(200)
        .body(Buffer().write(ByteArray(64) { 1 }))
        .build()

    private fun settingsWithElevenLabsKey() = SettingsRepository(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = {
                Files.createTempDirectory("opendroid-tts-test").resolve("settings.preferences_pb").toFile()
            }
        ),
        providerCredentialStore = ElevenLabsOnlyCredentialStore(),
        runStartupMigration = false
    )

    private class ElevenLabsOnlyCredentialStore : ProviderCredentialStore {
        override val recoveryState: StateFlow<ProviderCredentialRecoveryState> =
            MutableStateFlow(ProviderCredentialRecoveryState.Ready)

        override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> =
            CredentialStoreResult.Success(
                if (credential == ProviderCredentialId.ElevenLabsApiKey) "xi-test-key" else null
            )

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
        const val CLIP_MS = 50
    }
}
