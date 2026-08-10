package com.opendroid.ai.actions

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.media.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MediaActionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `verified playback is reported as success`() = runBlocking {
        val verifier = RecordingVerifier(result = true)
        registerSpotifyHandler()

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "spotify"))

        assertTrue(result.success)
        assertEquals("spotify", verifier.app)
        assertEquals("Daft Punk", verifier.query)
    }

    @Test
    fun `launch without verified playback is reported as failure`() = runBlocking {
        val verifier = RecordingVerifier(result = false)
        registerSpotifyHandler()

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "spotify"))

        assertFalse(result.success)
        assertEquals("The music app opened, but playback could not be verified.", result.error)
    }

    @Test
    fun `unsupported app fails before launching or verifying`() = runBlocking {
        val verifier = RecordingVerifier(result = true)

        val result = playMusic(verifier, mapOf("query" to "Daft Punk", "app" to "unknown"))

        assertFalse(result.success)
        assertEquals("That music app is not supported.", result.error)
        assertFalse(verifier.called)
    }

    @Test
    fun `query matching uses media title artist and album`() {
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Get Lucky")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Daft Punk")
            .build()

        assertTrue(AndroidMediaPlaybackVerifier.matchesQuery(metadata, "Daft Punk Get Lucky"))
        assertFalse(AndroidMediaPlaybackVerifier.matchesQuery(metadata, "Random Access Memories"))
    }

    @Test
    fun `youtube music package is accepted as a youtube target`() {
        assertTrue(AndroidMediaPlaybackVerifier.isTargetApp("com.google.android.apps.youtube.music", "youtube"))
        assertFalse(AndroidMediaPlaybackVerifier.isTargetApp("com.android.browser", "youtube"))
    }

    private suspend fun playMusic(
        verifier: RecordingVerifier,
        params: Map<String, String>
    ): ActionResult = MediaActions(verifier)
        .getActions()
        .first { it.name == "PLAY_MUSIC" }
        .execute(params, context)

    @Suppress("DEPRECATION")
    private fun registerSpotifyHandler() {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.spotify.music"
                name = "PlayerActivity"
            }
        }
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("spotify:search:Daft+Punk")
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }

    private class RecordingVerifier(private val result: Boolean) : MediaPlaybackVerifier {
        var called = false
        var app: String? = null
        var query: String? = null

        override suspend fun awaitVerifiedPlayback(
            context: Context,
            app: String,
            query: String
        ): Boolean {
            called = true
            this.app = app
            this.query = query
            return result
        }
    }
}
