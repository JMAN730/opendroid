package com.opendroid.ai.core.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.core.memory.decodeContactPreference
import com.opendroid.ai.core.memory.encodeContactPreference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactResolverTest {

    private val base = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun installContacts() {
        LookupKeyContactsProvider.install()
    }

    @After
    fun clearContacts() {
        LookupKeyContactsProvider.rows.clear()
    }

    @Test
    fun `alias does not match inside another name`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Joanna Reed", "5550101", "key-joanna")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Sirisha Rao", "5550102", "key-sirisha")

        assertTrue(resolver().resolveWithDisambiguation("brother") is ContactResolution.NotFound)
        assertTrue(resolver().resolveWithDisambiguation("boss") is ContactResolution.NotFound)
    }

    @Test
    fun `alias matches a whole word of the name`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Anna (Chennai)", "5550101", "key-anna")

        val resolved = resolver().resolveWithDisambiguation("brother")

        assertEquals("5550101", (resolved as ContactResolution.Found).contact.phoneNumber)
    }

    @Test
    fun `a sentence with digits in it is not a phone number`() = runBlocking {
        val resolved = resolver().resolveWithDisambiguation("mom about the 2pm 4512 meeting")

        assertTrue(resolved is ContactResolution.NotFound)
    }

    @Test
    fun `saved pick uses the current number of the contact`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "5550199", "key-dad")
        val resolver = resolver(saved = Contact("Dad", "5550100", lookupKey = "key-dad"))

        val contact = (resolver.resolveWithDisambiguation("dad") as ContactResolution.Found).contact

        assertEquals("5550199", contact.phoneNumber)
        assertEquals("memory", contact.source)
        assertTrue(resolver.forgotten.isEmpty())
    }

    @Test
    fun `saved pick keeps the chosen number when the contact has several`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "5550111", "key-dad")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "5550100", "key-dad")
        val resolver = resolver(saved = Contact("Dad", "5550100", lookupKey = "key-dad"))

        val contact = (resolver.resolveWithDisambiguation("dad") as ContactResolution.Found).contact

        assertEquals("5550100", contact.phoneNumber)
    }

    @Test
    fun `saved pick of a deleted contact is dropped and resolved normally`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Daddy Cool", "5550123", "key-other")
        val resolver = resolver(saved = Contact("Dad", "5550100", lookupKey = "key-gone"))

        val contact = (resolver.resolveWithDisambiguation("dad") as ContactResolution.Found).contact

        assertEquals("5550123", contact.phoneNumber)
        assertEquals(listOf("dad"), resolver.forgotten)
    }

    @Test
    fun `saved pick is dropped when its number is gone and several remain`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "5550111", "key-dad")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "5550122", "key-dad")
        val resolver = resolver(saved = Contact("Dad", "5550100", lookupKey = "key-dad"))

        val contact = (resolver.resolveWithDisambiguation("dad") as ContactResolution.Found).contact

        assertEquals(listOf("dad"), resolver.forgotten)
        assertEquals("contacts", contact.source)
    }

    @Test
    fun `pick saved before lookup keys existed is dropped and never used`() = runBlocking {
        val resolver = resolver(saved = Contact("Dad", "5550100"))

        val resolved = resolver.resolveWithDisambiguation("dad")

        assertTrue(resolved is ContactResolution.NotFound)
        assertEquals(listOf("dad"), resolver.forgotten)
    }

    @Test
    fun `saved pick is kept but not used without contacts permission`() = runBlocking {
        val resolver = resolver(
            saved = Contact("Dad", "5550100", lookupKey = "key-dad"),
            readContactsGranted = false
        )

        val resolved = resolver.resolveWithDisambiguation("dad")

        assertTrue(resolved is ContactResolution.NotFound)
        assertTrue(resolver.forgotten.isEmpty())
    }

    @Test
    fun `saved pick round trips with its lookup key`() {
        val decoded = decodeContactPreference(
            encodeContactPreference(Contact("Dad", "+919876543210", lookupKey = "key-dad"))
        )

        assertEquals("Dad", decoded?.name)
        assertEquals("+919876543210", decoded?.phoneNumber)
        assertEquals("key-dad", decoded?.lookupKey)
        assertEquals("memory", decoded?.source)
    }

    @Test
    fun `pick saved in the old format decodes without a lookup key`() {
        val decoded = decodeContactPreference("Dad||+919876543210")

        assertEquals("+919876543210", decoded?.phoneNumber)
        assertNull(decoded?.lookupKey)
        assertNull(decodeContactPreference("garbage"))
    }

    private fun resolver(
        saved: Contact? = null,
        readContactsGranted: Boolean = true
    ) = RecordingResolver(ContactsContext(base, readContactsGranted), saved)

    private class RecordingResolver(
        context: Context,
        private val saved: Contact?
    ) : ContactResolver(context) {
        val forgotten = mutableListOf<String>()

        override suspend fun recallSavedPick(query: String): Contact? = saved

        override suspend fun forgetSavedPick(query: String) {
            forgotten += query
        }
    }
}

/** Grants READ_CONTACTS (or not) and records launches instead of starting them. */
class ContactsContext(
    base: Context,
    private val readContactsGranted: Boolean = true
) : ContextWrapper(base) {
    val startedIntents = mutableListOf<Intent>()

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        checkSelfPermission(permission)

    override fun checkSelfPermission(permission: String): Int =
        if (readContactsGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED

    override fun startActivity(intent: Intent) {
        startedIntents += intent
    }
}

data class LookupKeyContactRow(val name: String, val number: String, val lookupKey: String)

/** Answers the phone-number queries ContactResolver makes, including SQL LIKE matching. */
class LookupKeyContactsProvider : ContentProvider() {
    companion object {
        val rows = mutableListOf<LookupKeyContactRow>()

        fun install() {
            Robolectric.setupContentProvider(LookupKeyContactsProvider::class.java, ContactsContract.AUTHORITY)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val arg = selectionArgs!!.single()
        val matches = rows.filter { row ->
            when {
                selection!!.startsWith(Phone.LOOKUP_KEY) -> row.lookupKey == arg
                !selection.contains("LIKE") -> row.name == arg
                arg.startsWith("%") -> row.name.contains(arg.trim('%'), ignoreCase = true)
                else -> row.name.equals(arg, ignoreCase = true)
            }
        }
        val cursor = MatrixCursor(projection)
        matches.forEach { row ->
            cursor.addRow(
                projection!!.map { column ->
                    when (column) {
                        Phone.DISPLAY_NAME -> row.name
                        Phone.NUMBER -> row.number
                        Phone.TYPE -> Phone.TYPE_MOBILE
                        Phone.LOOKUP_KEY -> row.lookupKey
                        else -> null
                    }
                }
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
