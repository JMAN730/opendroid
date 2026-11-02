package com.opendroid.ai.core.agent

import com.opendroid.ai.actions.ConfirmationReply
import java.util.Locale

// A sign stays on a number so "-1" is never read as index 1.
private val WORD = Regex("""[-+−]?\p{N}[\p{L}\p{M}\p{N}']*|[\p{L}\p{M}\p{N}']+""")
private val ORDINALS = listOf("first", "second", "third", "fourth", "fifth")
private val FILLER_WORDS = setOf(
    "the", "one", "please", "pls", "thanks", "number", "option", "contact", "use", "pick", "choose"
)

private fun words(text: String): List<String> =
    WORD.findAll(text.lowercase(Locale.ROOT).replace('’', '\'')).map { it.value }.toList()

/**
 * Index of the contact a reply to the picker chose among [names], or null if it did not
 * clearly choose one. The pick goes straight to sending, so nothing is matched by substring:
 * "stop" is inside "Christopher" and "no" is inside "Johnny Noble". A reply must be nothing
 * but an index, an ordinal or a whole name, give or take [FILLER_WORDS], so "not the first
 * one" chooses nobody either.
 */
internal fun selectContact(reply: String, names: List<String>): Int? {
    if (ConfirmationReply.isCancellation(reply)) return null
    val nameWords = names.map(::words)
    fun uniqueName(candidate: List<String>): Int? =
        nameWords.indices.filter { candidate.isNotEmpty() && nameWords[it] == candidate }.singleOrNull()

    val replyWords = words(reply)
    uniqueName(replyWords)?.let { return it }

    val rest = replyWords.filterNot { it in FILLER_WORDS }
    val position = rest.singleOrNull()?.let { word ->
        word.toIntOrNull()?.minus(1) ?: ORDINALS.indexOf(word).takeIf { it >= 0 }
    }
    return if (position != null) position.takeIf { it in names.indices } else uniqueName(rest)
}

/** True when a reply that chose no contact was refusing to choose, not just unclear. */
internal fun isContactPickerRefusal(reply: String): Boolean =
    ConfirmationReply.isCancellation(reply) || ConfirmationReply.parse(reply) == ConfirmationReply.Declined
