package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        PlayMusicAction(),
        PauseMusicAction(),
        ResumeMusicAction(),
        NextTrackAction(),
        PrevTrackAction(),
        SetVolumeMusicAction(),
        PlayYoutubeAction(),
        TakePhotoAction(),
        RecordVideoAction()
    )

    private class PlayMusicAction : Action {
        override val name: String = "PLAY_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: ""
            val app = params["app"] ?: "spotify"
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val intent = when (app.lowercase()) {
                    "spotify" -> {
                        Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encQuery")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    "youtube" -> {
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encQuery")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    else -> {
                        Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                            putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }
                context.startActivity(intent)
                ActionResult(true, if (query.isNotEmpty()) "Playing '$query' for you!" else "Music is playing!", null)
            } catch (e: Exception) {
                Log.e("PlayMusic", "Music failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't play that right now. Try again?")
            }
        }
    }

    private class PauseMusicAction : Action {
        override val name: String = "PAUSE_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
                ActionResult(true, "Music paused!", null)
            } catch (e: Exception) {
                Log.e("PauseMusic", "Pause failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't pause the music.")
            }
        }
    }

    private class ResumeMusicAction : Action {
        override val name: String = "RESUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                ActionResult(true, "Music resumed!", null)
            } catch (e: Exception) {
                Log.e("ResumeMusic", "Resume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't resume the music.")
            }
        }
    }

    private class NextTrackAction : Action {
        override val name: String = "NEXT_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                ActionResult(true, "Skipped to the next song!", null)
            } catch (e: Exception) {
                Log.e("NextTrack", "Skip failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't skip the track.")
            }
        }
    }

    private class PrevTrackAction : Action {
        override val name: String = "PREV_TRACK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                sendMediaKeyEvent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                ActionResult(true, "Going back to the previous song!", null)
            } catch (e: Exception) {
                Log.e("PrevTrack", "Back failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't go back.")
            }
        }
    }

    private class SetVolumeMusicAction : Action {
        override val name: String = "SET_VOLUME_MUSIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val level = params["level"]?.toIntOrNull() ?: 50
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Music volume is at $level% now.", null)
            } catch (e: Exception) {
                Log.e("SetVolumeMusic", "Volume failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't change the music volume.")
            }
        }
    }

    private class PlayYoutubeAction : Action {
        override val name: String = "PLAY_YOUTUBE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: ""
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val uri = Uri.parse("https://www.youtube.com/results?search_query=$encQuery")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, if (query.isNotEmpty()) "Playing '$query' on YouTube!" else "YouTube is open!", null)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    ActionResult(true, "YouTube app isn't installed, but I opened it in your browser!", null, true)
                }
            } catch (e: Exception) {
                Log.e("PlayYoutube", "YouTube failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open YouTube right now.")
            }
        }
    }

    private class TakePhotoAction : Action {
        override val name: String = "TAKE_PHOTO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                        putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                        putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                        putExtra("android.intent.extras.LENS_FACING_BACK", 1)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready — snap away!", null)
            } catch (e: Exception) {
                Log.e("TakePhoto", "Camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the camera.")
            }
        }
    }

    private class RecordVideoAction : Action {
        override val name: String = "RECORD_VIDEO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val camera = params["camera"] ?: "back"
            return try {
                val intent = Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (camera.lowercase() == "front") {
                        putExtra("android.intent.extras.CAMERA_FACING", 1)
                    } else {
                        putExtra("android.intent.extras.CAMERA_FACING", 0)
                    }
                }
                context.startActivity(intent)
                ActionResult(true, "Camera is ready for video!", null)
            } catch (e: Exception) {
                Log.e("RecordVideo", "Video camera failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the video camera.")
            }
        }
    }

    companion object {
        private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val eventTime = SystemClock.uptimeMillis()
            
            val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(downEvent)
            
            val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(upEvent)
        }
    }
}
