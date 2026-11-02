package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.ContactResolution
import com.opendroid.ai.core.agent.ContactResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TelegramRoutingTest {

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val context = RecordingContext(baseContext)
    private val resolver = FakeContactResolver(baseContext)
    private val typedMessages = mutableListOf<String>()

    @Test
    fun `plus number opens a phone link and never a username link`() = runBlocking {
        installTelegram()

        val result = send("+447700900123")

        assertTrue(result is ActionResult.Success)
        assertPhoneLink("447700900123")
        assertEquals(listOf(MESSAGE), typedMessages)
    }

    @Test
    fun `formatted numbers are classified as phone numbers`() = runBlocking {
        installTelegram()
        setRegion(sim = "us")

        send("+44 7700 900123")
        assertPhoneLink("447700900123")

        send("(479)5550142")
        assertPhoneLink("14795550142")

        send("479.555.0142")
        assertPhoneLink("14795550142")

        send("479-555-0142")
        assertPhoneLink("14795550142")
    }

    @Test
    fun `saved national number gets the country code of the SIM`() = runBlocking {
        installTelegram()
        setRegion(sim = "us")
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = send("Alex")

        assertTrue(result is ActionResult.Success)
        assertPhoneLink("14795550142")
    }

    @Test
    fun `national number drops its trunk prefix for the region`() = runBlocking {
        installTelegram()
        setRegion(sim = "gb")

        send("07400 123456")

        assertPhoneLink("447400123456")
    }

    @Test
    fun `SIM region wins over the network region when roaming`() = runBlocking {
        installTelegram()
        setRegion(sim = "gb", network = "us")

        send("07400 123456")

        assertPhoneLink("447400123456")
    }

    @Test
    fun `network region is used when there is no SIM region`() = runBlocking {
        installTelegram()
        setRegion(network = "us")

        send("(479) 555-0142")

        assertPhoneLink("14795550142")
    }

    @Test
    fun `national number with no known region asks for the country code`() = runBlocking {
        installTelegram()

        val result = send("(479) 555-0142")

        assertAsksForCountryCode(result)
    }

    @Test
    fun `saved national number with no known region asks for the country code`() = runBlocking {
        installTelegram()
        resolver.contacts += Contact(name = "Alex", phoneNumber = "(479) 555-0142")

        val result = send("Alex")

        assertAsksForCountryCode(result)
    }

    @Test
    fun `number that is not valid in the region asks for the country code`() = runBlocking {
        installTelegram()
        setRegion(sim = "us")

        val result = send("447700900123")

        assertAsksForCountryCode(result)
    }

    @Test
    fun `one word name resolves through ContactResolver to the saved number`() = runBlocking {
        installTelegram()
        resolver.contacts += Contact(name = "Mom", phoneNumber = "+447700900123")

        val result = send("mom")

        assertTrue(result is ActionResult.Success)
        assertPhoneLink("447700900123")
    }

    @Test
    fun `saved number that is not dialable fails without opening Telegram`() = runBlocking {
        installTelegram()
        resolver.contacts += Contact(name = "Voicemail", phoneNumber = "*86")

        val result = send("voicemail")

        assertFalse(result.success)
        assertTrue(context.startedIntents.isEmpty())
        assertTrue(typedMessages.isEmpty())
    }

    @Test
    fun `one word name with several matches shows the contact picker`() = runBlocking {
        installTelegram()
        val ambiguousResolver = object : ContactResolver(baseContext) {
            override suspend fun resolveWithDisambiguation(input: String): ContactResolution =
                ContactResolution.Ambiguous(
                    input,
                    listOf(
                        Contact(name = "Alex Smith", phoneNumber = "+447700900123"),
                        Contact(name = "Alex Jones", phoneNumber = "+447700900124")
                    )
                )
        }

        val result = send("alex", ambiguousResolver)

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals("contact_picker", (result as ActionResult.NeedsInput).metadata["type"])
        assertTrue(context.startedIntents.isEmpty())
        assertTrue(typedMessages.isEmpty())
    }

    @Test
    fun `unknown one word name asks instead of messaging a guessed public username`() = runBlocking {
        installTelegram()

        val result = send("Jonathan")

        assertTrue(result is ActionResult.NeedsInput)
        assertEquals("contact", (result as ActionResult.NeedsInput).metadata["param"])
        assertTrue(context.startedIntents.isEmpty())
        assertTrue(typedMessages.isEmpty())
    }

    @Test
    fun `explicit handle opens a username link`() = runBlocking {
        installTelegram()

        val result = send("@durov")

        assertTrue(result is ActionResult.Success)
        val uri = context.lastUri()
        assertEquals("tg", uri.scheme)
        assertEquals("resolve", uri.host)
        assertEquals("durov", uri.getQueryParameter("domain"))
        assertNull(uri.getQueryParameter("phone"))
    }

    @Test
    fun `handle carrying extra link parameters is not treated as a username`() = runBlocking {
        installTelegram()

        val result = send("@durov&start=abc")

        assertTrue(result is ActionResult.NeedsInput)
        assertTrue(context.startedIntents.isEmpty())
    }

    @Test
    fun `short digit string is looked up as a contact rather than dialled`() = runBlocking {
        installTelegram()

        val result = send("12345")

        assertTrue(result is ActionResult.NeedsInput)
        assertTrue(context.startedIntents.isEmpty())
    }

    @Test
    fun `failed auto send is reported as not sent`() = runBlocking {
        installTelegram()

        val result = send("+447700900123", autoSendSucceeds = false)

        assertFalse(result.success)
        assertTrue(result is ActionResult.UserActionRequired)
        assertPhoneLink("447700900123")
    }

    @Test
    fun `phone number without a tg handler falls back to the number's web link`() = runBlocking {
        val result = send("+447700900123")

        assertTrue(result is ActionResult.Success)
        val uri = context.lastUri()
        assertEquals("https", uri.scheme)
        assertEquals("t.me", uri.host)
        assertEquals("/+447700900123", uri.path)
        assertEquals(MESSAGE, uri.getQueryParameter("text"))
    }

    private suspend fun send(
        contact: String,
        contactResolver: ContactResolver = resolver,
        autoSendSucceeds: Boolean = true
    ): ActionResult {
        val callFlowVerifier = object : CallFlowVerifier {
            override fun isCallInProgress(context: Context): Boolean = false
            override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = true
        }
        val actions = CommunicationActions(
            contactResolver,
            CallFlowExecutor(callFlowVerifier),
            TelegramAutoSender { message, _ ->
                typedMessages += message
                autoSendSucceeds
            }
        )
        return actions.getActions()
            .first { it.name == "SEND_TELEGRAM" }
            .execute(mapOf("contact" to contact, "message" to MESSAGE), context)
    }

    private fun setRegion(sim: String = "", network: String = "") {
        val telephony = shadowOf(baseContext.getSystemService(TelephonyManager::class.java))
        telephony.setSimCountryIso(sim)
        telephony.setNetworkCountryIso(network)
    }

    private fun assertAsksForCountryCode(result: ActionResult) {
        assertTrue(result is ActionResult.NeedsInput)
        val needsInput = result as ActionResult.NeedsInput
        assertEquals("contact", needsInput.metadata["param"])
        assertTrue(needsInput.question.contains("country code"))
        assertTrue(context.startedIntents.isEmpty())
        assertTrue(typedMessages.isEmpty())
    }

    private fun assertPhoneLink(expectedDigits: String) {
        val uri = context.lastUri()
        assertEquals("tg", uri.scheme)
        assertEquals("resolve", uri.host)
        assertEquals(expectedDigits, uri.getQueryParameter("phone"))
        assertNull(uri.getQueryParameter("domain"))
        assertEquals(MESSAGE, uri.getQueryParameter("text"))
    }

    @Suppress("DEPRECATION")
    private fun installTelegram() {
        val component = ComponentName(TELEGRAM_PACKAGE, "$TELEGRAM_PACKAGE.LaunchActivity")
        val packageManager = shadowOf(baseContext.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("tg")
            }
        )
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()

        fun lastUri(): Uri = startedIntents.last().data!!

        override fun startActivity(intent: Intent) {
            startedIntents += intent
        }
    }

    private companion object {
        const val TELEGRAM_PACKAGE = "org.telegram.messenger"
        const val MESSAGE = "running late"
    }
}
