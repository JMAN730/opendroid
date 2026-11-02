package com.opendroid.ai.core.agent

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
}
