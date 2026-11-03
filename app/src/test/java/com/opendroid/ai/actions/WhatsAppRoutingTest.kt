package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.ContactResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WhatsAppRoutingTest {

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val context = RecordingContext(baseContext)
    private val resolver = FakeContactResolver(baseContext)

    @Test
    fun `national numbers get the country code of their region`() {
        assertEquals("14795550142", internationalPhoneDigits("(479) 555-0142", "US"))
        assertEquals("919876543210", internationalPhoneDigits("98765 43210", "IN"))
        assertEquals("447400123456", internationalPhoneDigits("07400 123456", "GB"))
    }

    @Test
    fun `number with a plus keeps its own country code in any region`() {
        assertEquals("14795550142", internationalPhoneDigits("+1 479 555 0142", "GB"))
        assertEquals("14795550142", internationalPhoneDigits("+1 479 555 0142", null))
    }

    @Test
    fun `national number with no region or no valid form has no digits`() {
        assertNull(internationalPhoneDigits("(479) 555-0142", null))
        assertNull(internationalPhoneDigits("not a number", null))
        assertNull(internationalPhoneDigits("*86", "US"))
    }

    @Test
    fun `saved national number is sent with the SIM country code, not read as Norway`() = runBlocking {
        setRegion(sim = "us")
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        sendWhatsApp("Alex")

        assertWhatsAppPhone("14795550142")
    }

    @Test
    fun `typed national number is sent with the SIM country code`() = runBlocking {
        setRegion(sim = "gb")

        sendWhatsApp("07400 123456", ContactResolver(context))

        assertWhatsAppPhone("447400123456")
    }

    @Test
    fun `network region is used when there is no SIM region`() = runBlocking {
        setRegion(network = "in")
        resolver.contacts += Contact(name = "Dad", phoneNumber = "98765 43210")

        sendWhatsApp("Dad")

        assertWhatsAppPhone("919876543210")
    }

    @Test
    fun `international number is sent as its digits without the plus`() = runBlocking {
        setRegion(sim = "us")
        resolver.contacts += Contact(name = "Mum", phoneNumber = "+447700900123")

        sendWhatsApp("Mum")

        assertWhatsAppPhone("447700900123")
    }

    @Test
    fun `saved national number with no known region asks for the country code`() = runBlocking {
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = sendWhatsApp("Alex")

        assertAsksForCountryCode(result)
    }

    @Test
    fun `unconfirmed send leaves the message for the user instead of failing into a fallback`() = runBlocking {
        setRegion(sim = "us")
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = sendWhatsApp("Alex")

        assertTrue(result is ActionResult.UserActionRequired)
        assertWhatsAppPhone("14795550142")
    }

    @Test
    fun `video call to a saved national number uses the SIM country code`() = runBlocking {
        setRegion(sim = "us")
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = videoCall("Alex")

        assertTrue(result is ActionResult.Success)
        assertWhatsAppPhone("14795550142")
    }

    @Test
    fun `video call to a national number with no known region asks for the country code`() = runBlocking {
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = videoCall("Alex")

        assertAsksForCountryCode(result)
    }

    private suspend fun sendWhatsApp(contact: String, contactResolver: ContactResolver = resolver): ActionResult =
        actions(contactResolver).first { it.name == "SEND_WHATSAPP" }
            .execute(mapOf("contact" to contact, "message" to MESSAGE), context)

    private suspend fun videoCall(contact: String): ActionResult =
        actions(resolver).first { it.name == "MAKE_VIDEO_CALL" }
            .execute(mapOf("contact" to contact, "app" to "whatsapp"), context)

    private fun actions(contactResolver: ContactResolver) = CommunicationActions(
        contactResolver,
        CallFlowExecutor(object : CallFlowVerifier {
            override fun isCallInProgress(context: Context): Boolean = false
            override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = true
        }),
        TelegramAutoSender { _, _ -> false }
    ).getActions()

    private fun setRegion(sim: String = "", network: String = "") {
        val telephony = shadowOf(baseContext.getSystemService(TelephonyManager::class.java))
        telephony.setSimCountryIso(sim)
        telephony.setNetworkCountryIso(network)
    }

    private fun assertWhatsAppPhone(expectedDigits: String) {
        val intent = context.startedIntents.single()
        assertEquals("com.whatsapp", intent.`package`)
        assertEquals("api.whatsapp.com", intent.data?.host)
        assertEquals(expectedDigits, intent.data?.getQueryParameter("phone"))
    }

    private fun assertAsksForCountryCode(result: ActionResult) {
        assertTrue(result is ActionResult.NeedsInput)
        val needsInput = result as ActionResult.NeedsInput
        assertEquals("contact", needsInput.metadata["param"])
        assertTrue(needsInput.question.contains("country code"))
        assertTrue(context.startedIntents.isEmpty())
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()

        override fun startActivity(intent: Intent) {
            startedIntents += intent
        }
    }

    private companion object {
        const val MESSAGE = "running late"
    }
}
