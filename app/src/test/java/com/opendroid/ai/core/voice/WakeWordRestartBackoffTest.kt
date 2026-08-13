package com.opendroid.ai.core.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordRestartBackoffTest {

    @Test
    fun `a healthy loop keeps the one second cadence`() {
        assertEquals(
            WakeWordDetector.BASE_RESTART_DELAY_MS,
            WakeWordDetector.restartDelayMillis(0)
        )
    }

    @Test
    fun `repeated hard failures back off exponentially`() {
        assertEquals(2000L, WakeWordDetector.restartDelayMillis(1))
        assertEquals(4000L, WakeWordDetector.restartDelayMillis(2))
        assertEquals(8000L, WakeWordDetector.restartDelayMillis(3))
    }

    @Test
    fun `backoff is capped so a permanently broken recognizer stops burning battery`() {
        listOf(5, 10, 100, Int.MAX_VALUE).forEach { failures ->
            assertEquals(
                "failures $failures",
                WakeWordDetector.MAX_RESTART_DELAY_MS,
                WakeWordDetector.restartDelayMillis(failures)
            )
        }
    }

    @Test
    fun `silence is not treated as a failure`() {
        assertTrue(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun `errors from an unusable recognizer are failures`() {
        // What a specialUse foreground service without microphone eligibility produces.
        assertFalse(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_CLIENT))
        assertFalse(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_AUDIO))
        assertFalse(WakeWordDetector.isTransientListeningError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}
