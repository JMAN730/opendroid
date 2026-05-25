package com.opendroid.ai.core.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Resolves contact names (including relationship words like "dad", "mom")
 * to phone numbers by searching Android Contacts with fuzzy matching.
 */
object ContactResolver {

    private const val TAG = "ContactResolver"

    sealed class ContactResult {
        data class Found(
            val displayName: String,
            val phoneNumber: String
        ) : ContactResult()

        data class NotFound(
            val searchedName: String
        ) : ContactResult()
    }

    /**
     * Relationship word mapping for fuzzy contact matching.
     * "dad" matches contacts named "Dad", "Father", "Papa", "Daddy", etc.
     */
    private val relationshipAliases: Map<String, List<String>> = mapOf(
        "dad"       to listOf("dad", "father", "papa", "baba", "abbu", "pita", "daddy", "pops", "dada"),
        "mom"       to listOf("mom", "mother", "mama", "maa", "amma", "mummy", "mum", "mommy", "ma"),
        "wife"      to listOf("wife", "wifey", "mrs", "better half", "patni", "biwi"),
        "husband"   to listOf("husband", "hubby", "mr", "pati"),
        "brother"   to listOf("brother", "bro", "bhai", "anna", "bhaiya"),
        "sister"    to listOf("sister", "sis", "didi", "akka", "behan"),
        "boss"      to listOf("boss", "manager", "sir", "madam"),
        "home"      to listOf("home", "house", "landline", "ghar"),
        "office"    to listOf("office", "work", "company", "workplace")
    )

    /**
     * Resolve a contact name/number/relationship to a phone number.
     */
    fun resolve(context: Context, input: String): ContactResult {
        val cleaned = input.trim()

        // CASE 1: Input is already a phone number
        val digitsOnly = cleaned.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7 && (digitsOnly.all { it.isDigit() || it == '+' })) {
            return ContactResult.Found(
                displayName = cleaned,
                phoneNumber = digitsOnly
            )
        }

        // Check contacts permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return ContactResult.NotFound(cleaned)
        }

        // CASE 2: Exact name match in Android contacts
        val exactMatch = searchContacts(context, cleaned, exact = true)
        if (exactMatch != null) return exactMatch

        // CASE 3: Partial/LIKE match in Android contacts
        val partialMatch = searchContacts(context, cleaned, exact = false)
        if (partialMatch != null) return partialMatch

        // CASE 4: Fuzzy relationship matching
        val fuzzyMatch = fuzzyRelationshipSearch(context, cleaned)
        if (fuzzyMatch != null) return fuzzyMatch

        // CASE 5: Not found
        return ContactResult.NotFound(cleaned)
    }

    private fun searchContacts(context: Context, name: String, exact: Boolean): ContactResult.Found? {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val selection: String
        val selectionArgs: Array<String>
        if (exact) {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
            selectionArgs = arrayOf(name)
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            selectionArgs = arrayOf("%${name}%")
        }

        try {
            val candidates = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIdx >= 0 && numIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val displayName = cursor.getString(nameIdx) ?: continue
                        val number = cursor.getString(numIdx)?.replace(Regex("[^0-9+]"), "")
                        if (!number.isNullOrBlank()) {
                            candidates.add(displayName to number)
                        }
                    }
                }
            }

            if (candidates.isEmpty()) return null

            // Rank candidates: prefer exact case-insensitive match, then shortest name
            val bestMatch = candidates
                .sortedWith(compareBy(
                    // Priority 1: exact match (case-insensitive) gets 0, partial gets 1
                    { if (it.first.equals(name, ignoreCase = true)) 0 else 1 },
                    // Priority 2: shorter names are more likely the intended contact
                    { it.first.length }
                ))
                .first()

            Log.d(TAG, "Found contact: ${bestMatch.first} → ${bestMatch.second} (from ${candidates.size} candidates)")
            return ContactResult.Found(bestMatch.first, bestMatch.second)
        } catch (e: Exception) {
            Log.e(TAG, "Contact search failed: ${e.message}")
        }

        return null
    }

    /**
     * Try all relationship aliases for the given input.
     * E.g., input "dad" → searches for "dad", "father", "papa", "daddy", etc.
     */
    private fun fuzzyRelationshipSearch(context: Context, name: String): ContactResult.Found? {
        val lower = name.lowercase()

        // Find which relationship group this input belongs to
        val searchTerms = relationshipAliases.entries
            .firstOrNull { (key, aliases) ->
                key == lower || aliases.any { it.equals(lower, ignoreCase = true) }
            }?.value ?: return null

        // Search contacts for each alias in the group
        for (term in searchTerms) {
            // Try exact match
            val exact = searchContacts(context, term, exact = true)
            if (exact != null) return exact

            // Try partial match
            val partial = searchContacts(context, term, exact = false)
            if (partial != null) return partial
        }

        return null
    }
}
