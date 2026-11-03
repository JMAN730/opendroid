package com.opendroid.ai.core.voice

/**
 * Decides when hands-free approval may open the mic: only when the approval prompt
 * itself has finished playing. Any other utterance can end while the prompt is queued
 * or loading, and listening then would let the recognizer hear the prompt.
 */
class ApprovalListenGate {
    @Volatile private var awaitedUtteranceId: String? = null

    fun armFor(utteranceId: String) {
        awaitedUtteranceId = utteranceId
    }

    fun disarm() {
        awaitedUtteranceId = null
    }

    /** True exactly once, when the utterance that finished is the armed prompt. */
    @Synchronized
    fun onUtteranceDone(utteranceId: String): Boolean {
        if (utteranceId != awaitedUtteranceId) return false
        awaitedUtteranceId = null
        return true
    }
}
