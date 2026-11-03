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
    val matchScore: Int = 0,
    val lookupKey: String? = null
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
 * "Dad" from [Dad, Dada, Daddu], future "call dad" resolves instantly, using
 * whatever number that contact has at the time.
 */
@Singleton
open class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryManager: MemoryManager? = null
) {

    companion object {
        private const val TAG = "ContactResolver"
        private val CONTACT_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
        )
        private val NAME_TOKEN_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
    }

    // ── New disambiguation-aware resolve ──────────────────

    /**
     * Main resolve function with disambiguation support.
     * Returns single result OR multiple for user to pick.
     */
    open suspend fun resolveWithDisambiguation(input: String): ContactResolution {
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
        val savedPick = recallSavedPick(query)

        // Check contacts permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS permission not granted")
            return ContactResolution.NotFound(query)
        }

        // A saved pick is only used if the contact is still there to confirm it
        if (savedPick != null) {
            val current = currentNumberFor(savedPick)
            if (current != null) {
                Log.d(TAG, "  → Memory preference: ${current.name}")
                return ContactResolution.Found(current)
            }
            Log.d(TAG, "  → Dropping stale memory preference")
            forgetSavedPick(query)
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

    protected open suspend fun recallSavedPick(query: String): Contact? =
        memoryManager?.recallContactPreference(query)

    protected open suspend fun forgetSavedPick(query: String) {
        memoryManager?.forgetContactPreference(query)
    }

    /**
     * Re-read a saved pick from contacts: the number it chose if the contact still has
     * it, otherwise the contact's only number. Null means the pick can't be trusted: the
     * contact is gone, it has several other numbers, or the pick predates lookup keys.
     */
    private fun currentNumberFor(savedPick: Contact): Contact? {
        val lookupKey = savedPick.lookupKey ?: return null
        val numbers = queryContacts(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            CONTACT_PROJECTION,
            "${ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY} = ?",
            arrayOf(lookupKey)
        ).distinctBy { it.phoneNumber }
        val current = numbers.firstOrNull { it.phoneNumber == savedPick.phoneNumber }
            ?: numbers.singleOrNull()
        return current?.copy(source = "memory")
    }

    /**
     * Find ALL contacts that match query using 4-tier search.
     */
    private fun findAllMatches(query: String): List<Contact> {
        val results = mutableListOf<Contact>()
        val seen = mutableSetOf<String>() // avoid duplicates by phone number

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = CONTACT_PROJECTION

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
            ).filter { hasNameToken(it.name, alias) }.forEach { contact ->
                if (seen.add(contact.phoneNumber)) {
                    results.add(contact.copy(matchScore = 50))
                }
            }
        }

        // Sort by match score (highest first)
        return results.sortedByDescending { it.matchScore }
    }

    // lint false positive: the cursor is closed by `?.use { }` on every path,
    // but the Recycle detector does not model Kotlin's use() inlining. See #67.
    @Suppress("Recycle")
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
                            source = "contacts",
                            lookupKey = cursor.getString(3)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Contact query failed: ${e.message}")
        }
        return contacts
    }

    /** "anna" is a word of "Anna (Chennai)", but only a substring of "Joanna". */
    private fun hasNameToken(name: String, token: String): Boolean =
        name.split(NAME_TOKEN_SEPARATOR).any { it.equals(token, ignoreCase = true) }

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
