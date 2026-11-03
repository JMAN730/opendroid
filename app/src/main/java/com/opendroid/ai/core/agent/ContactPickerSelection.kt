package com.opendroid.ai.core.agent

/**
 * The user's answer to a prompt: what they said, and the contact they tapped on a picker
 * card, if that is how they answered. Both travel together through the one completion of
 * the prompt, so the contact acted on is always the one from the answer that was taken.
 */
data class UserReply(val text: String, val contact: Map<String, String>? = null)

/** Resolves the user's answer to a contact picker against that picker's own matches. */
object ContactPickerSelection {

    /**
     * [tapped] is the contact chosen on the picker card. It is matched by identity (name
     * and number), never by position, and resolves to nothing if it is not in [matches].
     * Typed answers go through [selectContact], so only a whole index, ordinal or name picks.
     */
    fun resolve(
        selection: String,
        matches: List<Map<String, String>>,
        tapped: Map<String, String>? = null
    ): Map<String, String>? = when {
        tapped != null -> matches.find { contact ->
            contact["phone"] == tapped["phone"] && contact["name"] == tapped["name"]
        }

        else -> selectContact(selection, matches.map { it["name"].orEmpty() })?.let { matches[it] }
    }

    fun resolve(reply: UserReply, matches: List<Map<String, String>>): Map<String, String>? =
        resolve(reply.text, matches, reply.contact)
}
