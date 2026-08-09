package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.opendroid.ai.core.service.OpenDroidNotificationListener
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifies that a media app actually started the requested content.
 *
 * Opening an app or a search URL is not evidence of playback. Active media
 * sessions are used because they expose playback state without scraping an
 * account's UI or recording private content in logs.
 */
interface MediaPlaybackVerifier {
    suspend fun awaitVerifiedPlayback(
        context: Context,
        app: String,
        query: String
    ): Boolean
}

@Singleton
class AndroidMediaPlaybackVerifier @Inject constructor() : MediaPlaybackVerifier {

    override suspend fun awaitVerifiedPlayback(
        context: Context,
        app: String,
        query: String
    ): Boolean {
        val sessionManager = context.getSystemService(MediaSessionManager::class.java)
            ?: return false
        val listenerComponent = ComponentName(context, OpenDroidNotificationListener::class.java)
        val deadline = System.currentTimeMillis() + VERIFICATION_TIMEOUT_MS

        do {
            val controllers = try {
                sessionManager.getActiveSessions(listenerComponent)
            } catch (_: SecurityException) {
                // The notification-listener permission is required to inspect
                // other apps' media sessions. Fail closed when it is absent.
                return false
            } catch (_: RuntimeException) {
                return false
            }

            if (controllers.any { controller ->
                    isTargetApp(controller.packageName, app) &&
                        controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        matchesQuery(controller.metadata, query)
                }) {
                return true
            }

            if (System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
            }
        } while (System.currentTimeMillis() < deadline)

        return false
    }

    companion object {
        private const val VERIFICATION_TIMEOUT_MS = 5_000L
        private const val POLL_INTERVAL_MS = 250L

        internal fun isTargetApp(packageName: String, app: String): Boolean {
            return when (app.lowercase()) {
                "spotify" -> packageName == SPOTIFY_PACKAGE
                "youtube" -> packageName == YOUTUBE_PACKAGE || packageName == YOUTUBE_MUSIC_PACKAGE
                else -> true
            }
        }

        internal fun matchesQuery(metadata: MediaMetadata?, query: String): Boolean {
            if (query.isBlank()) return metadata != null
            metadata ?: return false

            val searchableMetadata = listOf(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ).filterNotNull().joinToString(" ").normalizeForSearch()

            if (searchableMetadata.isBlank()) return false
            val normalizedQuery = query.normalizeForSearch()
            return searchableMetadata.contains(normalizedQuery) ||
                normalizedQuery.split(' ').filter(String::isNotBlank).all(searchableMetadata::contains)
        }

        private fun String.normalizeForSearch(): String =
            lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
