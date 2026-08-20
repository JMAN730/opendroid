package com.opendroid.ai.actions

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.ContactResolution
import com.opendroid.ai.core.agent.ContactResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TelegramActionsTest {

    private val context: Context = ContextWrapper(null)
    private lateinit var fakeCallFlowVerifier: CallFlowVerifier
    private lateinit var callFlowExecutor: CallFlowExecutor
    private lateinit var fakeContactResolver: FakeContactResolver
    private lateinit var communicationActions: CommunicationActions

    @Before
    fun setUp() {
        fakeCallFlowVerifier = object : CallFlowVerifier {
            override fun isCallInProgress(context: Context): Boolean = false
            override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = true
        }
        callFlowExecutor = CallFlowExecutor(fakeCallFlowVerifier)
        fakeContactResolver = FakeContactResolver(context)
        communicationActions = CommunicationActions(
            contactResolver = fakeContactResolver,
            callFlowExecutor = callFlowExecutor
        )
    }

    @Test
    fun `schema contains SEND_TELEGRAM and OPEN_TELEGRAM`() {
        assertTrue(ActionSchema.isValid("SEND_TELEGRAM"))
        assertTrue(ActionSchema.isValid("OPEN_TELEGRAM"))

        val sendTgDef = ActionSchema.ALL_ACTIONS.firstOrNull { it.name == "SEND_TELEGRAM" }
        assertNotNull(sendTgDef)
        assertTrue(sendTgDef!!.params.any { it.name == "contact" && it.required })
        assertTrue(sendTgDef.params.any { it.name == "message" && it.required })

        val openTgDef = ActionSchema.ALL_ACTIONS.firstOrNull { it.name == "OPEN_TELEGRAM" }
        assertNotNull(openTgDef)
    }

    @Test
    fun `ActionAutoMapper maps telegram phrases accurately`() {
        val mapper = ActionAutoMapper()
        val registered = setOf("SEND_TELEGRAM", "OPEN_TELEGRAM", "SEND_WHATSAPP", "SEND_SMS")

        val res1 = mapper.mapAction("send telegram to @durov saying hello", emptyMap(), registered)
        assertEquals("SEND_TELEGRAM", res1.mappedAction)

        val res2 = mapper.mapAction("open telegram", emptyMap(), registered)
        assertEquals("OPEN_TELEGRAM", res2.mappedAction)

        val res3 = mapper.mapAction("message alex on telegram", emptyMap(), registered)
        assertEquals("SEND_TELEGRAM", res3.mappedAction)

        val res4 = mapper.mapAction("SEND_TELEGRAM_MESSAGE", emptyMap(), registered)
        assertEquals("SEND_TELEGRAM", res4.mappedAction)
    }

    @Test
    fun `SEND_TELEGRAM with direct handle executes without requiring phone resolution`() = runBlocking {
        val sendTgAction = communicationActions.getActions().first { it.name == "SEND_TELEGRAM" }

        val params = mapOf(
            "contact" to "@durov",
            "message" to "Hello Pavel"
        )
        val result = sendTgAction.execute(params, context)
        assertNotNull(result)
    }

    @Test
    fun `SEND_TELEGRAM with missing params returns failure`() = runBlocking {
        val sendTgAction = communicationActions.getActions().first { it.name == "SEND_TELEGRAM" }

        val missingMsg = sendTgAction.execute(mapOf("contact" to "@alice"), context)
        assertFalse(missingMsg.success)
        assertEquals("message is missing", missingMsg.error)

        val missingContact = sendTgAction.execute(mapOf("message" to "hello"), context)
        assertFalse(missingContact.success)
        assertEquals("contact is missing", missingContact.error)
    }
}

class FakeContactResolver(context: Context) : ContactResolver(context) {
    val contacts = mutableListOf<Contact>()

    override suspend fun resolveWithDisambiguation(input: String): ContactResolution {
        val match = contacts.firstOrNull { it.name.equals(input, ignoreCase = true) }
        return if (match != null) {
            ContactResolution.Found(match)
        } else {
            ContactResolution.NotFound(input)
        }
    }
}
