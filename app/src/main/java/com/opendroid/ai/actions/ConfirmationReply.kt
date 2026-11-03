package com.opendroid.ai.actions

import com.opendroid.ai.actions.base.ActionResult
import java.util.Locale

/**
 * What a reply to a yes/no question means. Replies are read as whole words, because
 * substrings misread refusals: "I disagree" contains "agree" and "yesterday" contains
 * "yes". A reply only confirms when nothing in it is anything but agreement.
 */
enum class ConfirmationReply {
    Confirmed, Declined, Unclear;

    companion object {
        private val WORD = Regex("""[\p{L}\p{M}\p{N}']+""")

        private val DECLINE_WORDS = setOf(
            "no", "nope", "nah", "nay", "not", "never", "dont", "cancel", "stop", "abort", "deny",
            "refuse", "decline", "reject", "skip", "disagree", "unsure", "maybe", "negative",
            "wait", "hold", "later", "tomorrow", "myself",
            "nahi", "nahin", "mat", "नहीं", "मत", "não", "nao", "nein", "non"
        )
        private val AFFIRMATIVE_WORDS = setOf(
            "yes", "yep", "yup", "yeah", "sure", "ok", "okay", "alright", "confirm", "confirmed",
            "proceed", "agree", "absolutely", "correct"
        )
        private val AFFIRMATIVE_PHRASES = listOf("go ahead", "go for it", "do it")
        private val FILLER_WORDS = setOf("please", "pls", "thanks", "i", "now", "just")

        /** Phrases that abandon the task, whatever question they answer. Longest match first. */
        private val CANCELLATIONS = listOf(
            "dont do it", "don't do it", "do not do it", "cancel it", "cancel that", "stop it",
            "never mind", "forget it", "do not", "not now",
            "no", "nope", "nah", "cancel", "stop", "abort", "nevermind", "dont", "don't",
            "nahi", "nahin", "नहीं", "não", "nao", "nein"
        ).map { it.split(" ") }
        private val CANCELLATION_FILLERS = (FILLER_WORDS + "thank you").map { it.split(" ") }

        private fun words(text: String): List<String> =
            WORD.findAll(text.lowercase(Locale.ROOT).replace('’', '\'')).map { it.value }.toList()

        fun parse(text: String): ConfirmationReply {
            val words = words(text)
            if (words.isEmpty()) return Unclear
            val spaced = " ${words.joinToString(" ")} ".replace(" thank you ", " ")
            if (words.any { it in DECLINE_WORDS || it.endsWith("n't") } || " let me " in spaced) {
                return Declined
            }
            val remainder = AFFIRMATIVE_PHRASES.fold(spaced) { rest, phrase -> rest.replace(" $phrase ", " ") }
            val remaining = remainder.split(" ").filter { it.isNotEmpty() }
            val agrees = remainder != spaced || remaining.any { it in AFFIRMATIVE_WORDS }
            val onlyAgrees = remaining.all { it in AFFIRMATIVE_WORDS || it in FILLER_WORDS }
            return if (agrees && onlyAgrees) Confirmed else Unclear
        }

        /**
         * Narrower than [parse] on purpose: an open question's answer is free text, so
         * "Stop by at 5" stays an SMS body and only a reply that is nothing but a
         * refusal cancels: refusal phrases and filler, as in "no, cancel" or "stop please".
         */
        fun isCancellation(text: String): Boolean {
            var rest = words(text)
            var refused = false
            while (rest.isNotEmpty()) {
                val filler = CANCELLATION_FILLERS.firstOrNull { rest.take(it.size) == it }
                val phrase = filler ?: CANCELLATIONS.firstOrNull { rest.take(it.size) == it } ?: return false
                refused = refused || filler == null
                rest = rest.drop(phrase.size)
            }
            return refused
        }
    }
}

/** Asks [message], then once more if the reply is unclear. Still unclear means no. */
internal suspend fun askForConfirmation(message: String, prompt: suspend (String) -> String): ActionResult {
    var response = prompt(message).trim().lowercase()
    var reply = ConfirmationReply.parse(response)
    if (reply == ConfirmationReply.Unclear) {
        response = prompt("Please answer yes or no. $message").trim().lowercase()
        reply = ConfirmationReply.parse(response)
    }
    return if (reply == ConfirmationReply.Confirmed) {
        ActionResult(true, "User confirmed: $response", null)
    } else {
        ActionResult.Declined("Action cancelled by user response: $response")
    }
}

internal fun askUserResult(response: String): ActionResult =
    if (ConfirmationReply.isCancellation(response)) {
        ActionResult.Declined("Action cancelled by user response: $response")
    } else {
        ActionResult(true, response, null)
    }
