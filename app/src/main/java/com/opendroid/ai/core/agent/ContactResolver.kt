package com.opendroid.ai.core.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.opendroid.ai.core.memory.MemoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contact data model used throughout the disambiguation pipeline.
 */
data class Contact(
    val name: String,
    val phoneNumber: String,
    val type: String = "Mobile",
    val source: String = "contacts",
    val matchScore: Int = 0
)

/**
 * Result of a contact resolution attempt.
 */
sealed class ContactResolution {
    /** Single contact found → use immediately */
    data class Found(val contact: Contact) : ContactResolution()

    /** Multiple contacts found → need user to pick */
    data class Ambiguous(
        val query: String,
        val matches: List<Contact>
    ) : ContactResolution()

    /** Nothing found → ask user for number */
    data class NotFound(val searchedName: String) : ContactResolution()
}

/**
 * Resolves contact names (including relationship words like "dad", "mom")
 * to phone numbers with smart disambiguation support.
 *
 * 3-tier resolution:
 *   Tier 1: Exact match → execute immediately
 *   Tier 2: One fuzzy match → execute immediately
 *   Tier 3: Multiple matches → return Ambiguous for user picker
 *
 * Also supports memory-backed contact preferences — once the user picks
 * "Dad" from [Dad, Dada, Daddu], future "call dad" resolves instantly.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: MemoryManager
) {

    companion object {
        private const val TAG = "ContactResolver"

        /**
         * Legacy static resolve for backward compatibility.
         * Used by CommunicationActions.resolveContactToPhoneNumber().
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
            val exactMatch = searchContactsLegacy(context, cleaned, exact = true)
            if (exactMatch != null) return exactMatch

            // CASE 3: Partial/LIKE match in Android contacts
            val partialMatch = searchContactsLegacy(context, cleaned, exact = false)
            if (partialMatch != null) return partialMatch

            // CASE 4: Fuzzy relationship matching
            val fuzzyMatch = fuzzyRelationshipSearchLegacy(context, cleaned)
            if (fuzzyMatch != null) return fuzzyMatch

            // CASE 5: Not found
            return ContactResult.NotFound(cleaned)
        }

        private fun searchContactsLegacy(context: Context, name: String, exact: Boolean): ContactResult.Found? {
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
                val bestMatch = candidates
                    .sortedWith(compareBy(
                        { if (it.first.equals(name, ignoreCase = true)) 0 else 1 },
                        { it.first.length }
                    ))
                    .first()
                return ContactResult.Found(bestMatch.first, bestMatch.second)
            } catch (e: Exception) {
                Log.e(TAG, "Contact search failed: ${e.message}")
            }
            return null
        }

        private val relationshipAliasesStatic: Map<String, List<String>> = mapOf(
            "dad"       to listOf("dad", "father", "papa", "baba", "abbu", "pita", "daddy", "pops"),
            "mom"       to listOf("mom", "mother", "mama", "maa", "amma", "mummy", "mum", "mommy", "ma"),
            "wife"      to listOf("wife", "wifey", "mrs", "better half", "patni", "biwi"),
            "husband"   to listOf("husband", "hubby", "mr", "pati"),
            "brother"   to listOf("brother", "bro", "bhai", "anna", "bhaiya"),
            "sister"    to listOf("sister", "sis", "didi", "akka", "behan"),
            "boss"      to listOf("boss", "manager", "sir", "madam"),
            "home"      to listOf("home", "house", "landline", "ghar"),
            "office"    to listOf("office", "work", "company", "workplace")
        )

        private fun fuzzyRelationshipSearchLegacy(context: Context, name: String): ContactResult.Found? {
            val lower = name.lowercase()
            val searchTerms = relationshipAliasesStatic.entries
                .firstOrNull { (key, aliases) ->
                    key == lower || aliases.any { it.equals(lower, ignoreCase = true) }
                }?.value ?: return null
            for (term in searchTerms) {
                val exact = searchContactsLegacy(context, term, exact = true)
                if (exact != null) return exact
                val partial = searchContactsLegacy(context, term, exact = false)
                if (partial != null) return partial
            }
            return null
        }
    }

    /** Legacy result type kept for backward compat with CommunicationActions */
    sealed class ContactResult {
        data class Found(val displayName: String, val phoneNumber: String) : ContactResult()
        data class NotFound(val searchedName: String) : ContactResult()
    }

    // ── New disambiguation-aware resolve ──────────────────

    /**
     * Main resolve function with disambiguation support.
     * Returns single result OR multiple for user to pick.
     */
    suspend fun resolveWithDisambiguation(input: String): ContactResolution {
        val query = input.trim()

        Log.d(TAG, "── Contact Resolution for: '$query' ──")

        // STEP 1: Is it already a phone number?
        if (isPhoneNumber(query)) {
            Log.d(TAG, "  → Direct phone number")
            return ContactResolution.Found(
                Contact(
                    name = query,
                    phoneNumber = cleanPhone(query),
                    source = "direct_input"
                )
            )
        }

        // STEP 2: Check memory for saved preference
        val memoryMatch = memoryManager.recallContactPreference(query)
        if (memoryMatch != null) {
            Log.d(TAG, "  → Memory preference: ${memoryMatch.name}")
            return ContactResolution.Found(memoryMatch)
        }

        // Check contacts permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return ContactResolution.NotFound(query)
        }

        // STEP 3: Get ALL matching contacts
        val allMatches = findAllMatches(query)

        Log.d(TAG, "  Found ${allMatches.size} matches:")
        allMatches.forEach { c -> Log.d(TAG, "    - ${c.name} (${c.phoneNumber}) score=${c.matchScore}") }

        return when {
            // No matches found
            allMatches.isEmpty() ->
                ContactResolution.NotFound(query)

            // Exactly one match → use it
            allMatches.size == 1 -> {
                Log.d(TAG, "  → Single match: ${allMatches.first().name}")
                ContactResolution.Found(allMatches.first())
            }

            else -> {
                // Check if we have an exact case-insensitive match.
                // If we do, and there's only one distinct contact name among exact matches,
                // we bypass partial/fuzzy matches and return the exact match.
                val exactMatches = allMatches.filter { it.name.equals(query, ignoreCase = true) }
                val distinctExactNames = exactMatches.map { it.name.lowercase().trim() }.distinct()

                if (exactMatches.isNotEmpty() && distinctExactNames.size == 1) {
                    Log.d(TAG, "  → Found exact match, bypassing partial matches: ${exactMatches.first().name}")
                    ContactResolution.Found(exactMatches.first())
                } else {
                    // Multiple matches — check if they are all the SAME person
                    // (same display name, just different phone numbers)
                    val distinctNames = allMatches.map { it.name.lowercase().trim() }.distinct()

                    if (distinctNames.size == 1) {
                        // All matches are the same person — pick the highest-scored one
                        Log.d(TAG, "  → All same name, using highest score: ${allMatches.first().name}")
                        ContactResolution.Found(allMatches.first())
                    } else {
                        // Multiple DIFFERENT people matched → ALWAYS show picker
                        // Even if one is an exact match, user should choose
                        Log.d(TAG, "  → ${distinctNames.size} different names — showing picker")

                        // Deduplicate by name (keep highest score for each name)
                        val deduped = allMatches
                            .groupBy { it.name.lowercase().trim() }
                            .map { (_, contacts) -> contacts.maxByOrNull { it.matchScore }!! }
                            .sortedByDescending { it.matchScore }
                            .take(5)

                        ContactResolution.Ambiguous(
                            query = query,
                            matches = deduped
                        )
                    }
                }
            }
        }
    }

    /**
     * Find ALL contacts that match query using 4-tier search.
     */
    private fun findAllMatches(query: String): List<Contact> {
        val results = mutableListOf<Contact>()
        val seen = mutableSetOf<String>() // avoid duplicates by phone number

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        // Search 1: Exact name match (highest priority, score=100)
        queryContacts(uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
            arrayOf(query)
        ).forEach { contact ->
            if (seen.add(contact.phoneNumber)) {
                results.add(contact.copy(matchScore = 100))
            }
        }

        // Search 2: Case-insensitive exact match (score=90)
        queryContacts(uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf(query)
        ).forEach { contact ->
            if (seen.add(contact.phoneNumber)) {
                results.add(contact.copy(matchScore = 90))
            }
        }

        // Search 3: Contains query anywhere in name (score=60)
        // This catches "Dad", "Dada", "Daddu" all together
        queryContacts(uri, projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%")
        ).forEach { contact ->
            if (seen.add(contact.phoneNumber)) {
                // Boost score if the name IS the query (just different case)
                val score = if (contact.name.equals(query, ignoreCase = true)) 90
                // Boost if name starts with query and query is a full word
                // e.g., query="dad", name="Dad Mobile" gets 80
                // but query="dad", name="Daddu" only gets 60
                else if (contact.name.startsWith(query, ignoreCase = true) &&
                    (contact.name.length == query.length ||
                        contact.name.getOrNull(query.length)?.let { it == ' ' || it == '(' } == true)) 80
                else 60
                results.add(contact.copy(matchScore = score))
            }
        }

        // Search 4: Relationship aliases (score=50)
        val aliases = getRelationshipAliases(query)
        aliases.forEach { alias ->
            queryContacts(uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$alias%")
            ).forEach { contact ->
                if (seen.add(contact.phoneNumber)) {
                    results.add(contact.copy(matchScore = 50))
                }
            }
        }

        // Sort by match score (highest first)
        return results.sortedByDescending { it.matchScore }
    }

    private fun queryContacts(
        uri: android.net.Uri,
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>
    ): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                uri, projection, selection, selectionArgs, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val number = cursor.getString(1) ?: continue
                    val type = cursor.getInt(2)
                    contacts.add(
                        Contact(
                            name = name,
                            phoneNumber = cleanPhone(number),
                            type = getPhoneTypeLabel(type),
                            source = "contacts"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query failed: ${e.message}")
        }
        return contacts
    }

    /** Relationship word mappings for fuzzy matching */
    private fun getRelationshipAliases(query: String): List<String> {
        val lower = query.lowercase()
        return when {
            lower in listOf("dad", "daddy", "father", "papa",
                "baba", "abbu", "pita", "bapu") ->
                listOf("dad", "father", "papa", "baba",
                    "abbu", "pita", "daddy", "bapu")

            lower in listOf("mom", "mum", "mother", "mama",
                "maa", "amma", "mummy", "ammi") ->
                listOf("mom", "mother", "mama", "maa",
                    "amma", "mummy", "ammi", "mum")

            lower in listOf("bro", "brother", "bhai", "anna",
                "dada", "bhaiya") ->
                listOf("brother", "bhai", "anna", "bhaiya", "bro")

            lower in listOf("sis", "sister", "didi", "akka",
                "behen") ->
                listOf("sister", "didi", "akka", "behen", "sis")

            lower in listOf("wife", "wifey", "patni", "biwi") ->
                listOf("wife", "wifey", "patni", "biwi")

            lower in listOf("husband", "hubby", "pati") ->
                listOf("husband", "hubby", "pati")

            lower in listOf("boss", "manager", "sir") ->
                listOf("boss", "manager", "sir", "madam")

            else -> emptyList()
        }
    }

    private fun isPhoneNumber(input: String): Boolean =
        input.replace(Regex("[+\\-\\s()]"), "")
            .all { it.isDigit() } &&
        input.replace(Regex("[+\\-\\s()]"), "").length >= 7

    private fun cleanPhone(number: String): String =
        number.replace(Regex("[\\s\\-()]"), "").trim()

    private fun getPhoneTypeLabel(type: Int): String =
        when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            else -> "Phone"
        }
}

/** Mask phone for privacy in picker display: 9876543210 → 98765***** */
fun maskPhone(phone: String): String {
    return if (phone.length >= 5)
        phone.take(5) + "*".repeat(phone.length - 5)
    else phone
}
