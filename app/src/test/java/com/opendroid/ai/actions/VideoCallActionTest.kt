package com.opendroid.ai.actions

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ContactResolver
import com.opendroid.ai.core.agent.FakeContactsProvider
import com.opendroid.ai.core.agent.PlanValidatorContactsTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Runs MAKE_VIDEO_CALL through the real [ContactResolver] against a fake contacts provider. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlanValidatorContactsTestApplication::class)
class VideoCallActionTest {

    private val application: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun videoCall(params: Map<String, String>, vararg contacts: Pair<String, String>): ActionResult = runBlocking {
        FakeContactsProvider.rows = contacts.toList()
        val callFlowExecutor = CallFlowExecutor(object : CallFlowVerifier {
            override fun isCallInProgress(context: Context): Boolean = false
            override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = true
        })
        CommunicationActions(ContactResolver(application), callFlowExecutor)
            .getActions()
            .first { it.name == "MAKE_VIDEO_CALL" }
            .execute(params, application)
    }

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
    }

    @Test
    fun `a name several people match shows the picker and starts no call`() {
        val result = videoCall(mapOf("contact" to "John"), "John Smith" to "+15550101", "John Doe" to "+15550102")

        val picker = result as ActionResult.NeedsInput
        assertEquals("contact_picker", picker.metadata["type"])
        assertEquals("MAKE_VIDEO_CALL", picker.metadata["action"])
        assertEquals(2, picker.options.size)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a name nobody matches asks for the number and starts no call`() {
        val result = videoCall(mapOf("contact" to "John"), "Mary Doe" to "+15550102")

        assertEquals("contact", (result as ActionResult.NeedsInput).metadata["param"])
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `a name one person matches calls that person`() {
        val result = videoCall(mapOf("contact" to "John"), "John Smith" to "+15550101", "Mary Doe" to "+15550102")

        assertEquals(true, result.success)
        val started = shadowOf(application).nextStartedActivity
        assertEquals("com.whatsapp", started.`package`)
        assertEquals("15550101", started.data?.getQueryParameter("phone"))
    }

    @Test
    fun `a number with post-dial digits calls the primary number, not the digits run together`() {
        val result = videoCall(mapOf("contact" to "John"), "John Smith" to "+15550100#1234")

        assertEquals(true, result.success)
        assertEquals("15550100", shadowOf(application).nextStartedActivity.data?.getQueryParameter("phone"))
    }

    @Test
    fun `the number chosen at the picker calls that person`() {
        val result = videoCall(mapOf("contact" to "+15550102"), "John Smith" to "+15550101", "John Doe" to "+15550102")

        assertEquals(true, result.success)
        assertEquals("15550102", shadowOf(application).nextStartedActivity.data?.getQueryParameter("phone"))
    }
}
