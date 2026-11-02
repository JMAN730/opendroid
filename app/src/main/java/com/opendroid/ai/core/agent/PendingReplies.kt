package com.opendroid.ai.core.agent

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * The auto-reply job waiting to fire for each conversation key.
 *
 * Replies are scheduled from the notification listener's IO scope and finish on
 * the engine's Default scope, so the map is concurrent. A job unregisters only
 * itself: when a newer message has already replaced it, the newer reply has to
 * stay cancellable (#83).
 */
internal class PendingReplies {
    private class Entry(val job: Job, val conversation: ReplyConversation)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Registers [job] for [key], cancelling the reply it replaces. */
    fun register(key: String, job: Job, conversation: ReplyConversation) {
        entries.put(key, Entry(job, conversation))?.job?.takeIf { it !== job }?.cancel()
    }

    /** Called when [job] ends; a newer reply registered for [key] is left in place. */
    fun finished(key: String, job: Job) {
        unregister(key, job)
    }

    /**
     * Called by [job] just before it sends. Returns false if the reply was cancelled or
     * replaced in the meantime and must not go out. Once it returns true nothing can cancel
     * the job: the reply is leaving, and its own "You: ..." echo must not cost it the
     * bookkeeping that the hourly limit counts.
     */
    fun claimForSending(key: String, job: Job): Boolean = unregister(key, job)

    /** Cancels the reply waiting for [key], if any. */
    fun cancel(key: String) {
        entries.remove(key)?.job?.cancel()
    }

    /** Cancels every reply still waiting for the conversation [outgoing] was sent in. */
    fun cancelAnsweredBy(outgoing: OutgoingMessage) {
        entries.forEach { (key, entry) ->
            if (entry.conversation.isAnsweredBy(outgoing) && entries.remove(key, entry)) {
                entry.job.cancel()
            }
        }
    }

    fun cancelAll() {
        entries.keys.toList().forEach(::cancel)
    }

    fun registered(key: String): Job? = entries[key]?.job

    private fun unregister(key: String, job: Job): Boolean {
        val entry = entries[key] ?: return false
        return entry.job === job && entries.remove(key, entry)
    }
}
