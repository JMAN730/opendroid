package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.ContactResolver
import com.opendroid.ai.core.agent.ContactsContext
import com.opendroid.ai.core.agent.LookupKeyContactRow
import com.opendroid.ai.core.agent.LookupKeyContactsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MakeVideoCallActionTest {

    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val context = ContactsContext(base)

    @Before
    fun installContacts() {
        LookupKeyContactsProvider.install()
    }

    @After
    fun clearContacts() {
        LookupKeyContactsProvider.rows.clear()
    }

    @Test
    fun `sentence containing digits is not called as a number`() = runBlocking {
        val result = videoCall("mom about the 2pm 4512 meeting")

        assertTrue(result is ActionResult.NeedsInput)
        assertTrue(context.startedIntents.isEmpty())
    }

    @Test
    fun `several people matching shows the picker instead of opening the shortest name`() = runBlocking {
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Sam Lee", "5550101", "key-lee")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Samantha Ortiz", "5550102", "key-ortiz")

        val result = videoCall("sam") as ActionResult.NeedsInput

        assertEquals("contact_picker", result.metadata["type"])
        assertEquals("MAKE_VIDEO_CALL", result.metadata["action"])
        assertTrue(result.metadata["matches"]!!.contains("key-lee"))
        assertTrue(context.startedIntents.isEmpty())
    }

    @Test
    fun `single match opens the WhatsApp chat with their number`() = runBlocking {
        setSimRegion("us")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Sam Lee", "(479) 555-0142", "key-lee")

        val result = videoCall("sam")

        assertTrue(result is ActionResult.Success)
        assertEquals("Video call to sam is starting!", result.data)
        val intent = context.startedIntents.single()
        assertEquals("com.whatsapp", intent.`package`)
        assertEquals("14795550142", intent.data?.getQueryParameter("phone"))
    }

    @Test
    fun `dialer fallback keeps the hash in the number`() = runBlocking {
        installDialer()
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Sam", "5550101,,1234#", "key-sam")

        videoCall("sam", app = "meet")

        val uri = context.startedIntents.single().data!!
        assertEquals("tel", uri.scheme)
        assertEquals("5550101,,1234#", uri.schemeSpecificPart)
        assertNull(uri.fragment)
    }

    @Test
    fun `saved pick says which contact was used`() = runBlocking {
        setSimRegion("in")
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "9876543210", "key-dad")
        val resolver = SavedPickResolver(context, Contact("Dad", "9876543210", lookupKey = "key-dad"))

        val result = videoCall("dad", resolver = resolver)

        assertTrue(result is ActionResult.Success)
        assertTrue(result.data!!.startsWith("Using Dad (98765*****)"))
    }

    @Test
    fun `saved pick is named on a call that still needs the user`() = runBlocking {
        installDialer()
        LookupKeyContactsProvider.rows += LookupKeyContactRow("Dad", "9876543210", "key-dad")
        val resolver = SavedPickResolver(context, Contact("Dad", "9876543210", lookupKey = "key-dad"))

        val result = actions(resolver).first { it.name == "MAKE_CALL" }
            .execute(mapOf("contact" to "dad"), context)

        assertTrue(result is ActionResult.PendingUserAction)
        assertTrue(result.data!!.startsWith("Using Dad (98765*****)"))
    }

    private suspend fun videoCall(
        contact: String,
        app: String = "whatsapp",
        resolver: ContactResolver = ContactResolver(context)
    ): ActionResult = actions(resolver).first { it.name == "MAKE_VIDEO_CALL" }
        .execute(mapOf("contact" to contact, "app" to app), context)

    private fun actions(resolver: ContactResolver) = CommunicationActions(
        resolver,
        CallFlowExecutor(object : CallFlowVerifier {
            override fun isCallInProgress(context: Context): Boolean = false
            override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = true
        }),
        TelegramAutoSender { _, _ -> false }
    ).getActions()

    private fun setSimRegion(region: String) {
        shadowOf(base.getSystemService(TelephonyManager::class.java)).setSimCountryIso(region)
    }

    @Suppress("DEPRECATION")
    private fun installDialer() {
        val component = ComponentName("com.example.dialer", "com.example.dialer.DialActivity")
        val packageManager = shadowOf(base.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_DIAL).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tel")
            }
        )
    }

    private class SavedPickResolver(
        context: Context,
        private val saved: Contact
    ) : ContactResolver(context) {
        override suspend fun recallSavedPick(query: String): Contact? = saved
    }
}
