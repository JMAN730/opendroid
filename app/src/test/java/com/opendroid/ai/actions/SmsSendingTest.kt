package com.opendroid.ai.actions

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.accessibility.CallFlowVerifier
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.Contact
import com.opendroid.ai.core.agent.ReplyDispatcher
import com.opendroid.ai.core.util.SmsSendOutcome
import com.opendroid.ai.core.util.SmsSender
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSmsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSmsManagerWithSubscription::class])
class SmsSendingTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val smsManager = context.getSystemService(SmsManager::class.java)
    private val shadowSms: ShadowSmsManager = shadowOf(smsManager)

    @Before
    fun grantSms() {
        shadowOf(context).grantPermissions(Manifest.permission.SEND_SMS)
        shadowOf(context.packageManager).setSystemFeature(SMS_FEATURE, true)
    }

    @Test
    fun `200 character GSM message goes out as several parts`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("a".repeat(200)) }

        val params = shadowSms.lastSentMultipartTextMessageParams
        assertTrue(params.parts.size > 1)
        assertEquals("a".repeat(200), params.parts.joinToString(""))
        assertEquals(PHONE, params.destinationAddress)
        assertNull(shadowSms.lastSentTextMessageParams)

        reportAll(Activity.RESULT_OK)
        assertTrue(pending.await() is ActionResult.Success)
    }

    @Test
    fun `71 character message with an emoji goes out as several parts`() = runBlocking {
        val message = "a".repeat(69) + "😀"
        assertEquals(71, message.length)

        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction(message) }

        val params = shadowSms.lastSentMultipartTextMessageParams
        assertTrue(params.parts.size > 1)
        assertEquals(message, params.parts.joinToString(""))

        reportAll(Activity.RESULT_OK)
        assertTrue(pending.await() is ActionResult.Success)
    }

    @Test
    fun `short message still asks the radio to report the result`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("hello") }

        assertEquals(1, shadowSms.lastSentMultipartTextMessageParams.sentIntents.size)
        assertFalse(pending.isCompleted)

        reportAll(Activity.RESULT_OK)
        assertTrue(pending.await() is ActionResult.Success)
    }

    @Test
    fun `success is reported only after every part returns RESULT_OK`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("a".repeat(200)) }
        val intents = shadowSms.lastSentMultipartTextMessageParams.sentIntents
        assertFalse(pending.isCompleted)

        intents.dropLast(1).forEach { it.send(context, Activity.RESULT_OK, null) }
        idleMainLooper()
        assertFalse(pending.isCompleted)

        intents.last().send(context, Activity.RESULT_OK, null)
        idleMainLooper()
        assertTrue(pending.await() is ActionResult.Success)
    }

    @Test
    fun `generic failure is not success`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("hello") }

        reportAll(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        val result = pending.await()
        assertFalse(result.success)
        assertFalse(result.error.orEmpty().contains("Text sent"))
    }

    @Test
    fun `no service is not success`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("hello") }

        reportAll(SmsManager.RESULT_ERROR_NO_SERVICE)

        assertFalse(pending.await().success)
    }

    @Test
    fun `one failed part fails the whole message`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("a".repeat(200)) }
        val intents = shadowSms.lastSentMultipartTextMessageParams.sentIntents

        intents.first().send(context, Activity.RESULT_OK, null)
        intents.last().send(context, SmsManager.RESULT_ERROR_RADIO_OFF, null)
        idleMainLooper()

        assertFalse(pending.await().success)
    }

    @Test
    fun `a failed part of a long text warns that some of it may have gone out`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { sendAction("a".repeat(200)) }
        val intents = shadowSms.lastSentMultipartTextMessageParams.sentIntents

        intents.first().send(context, Activity.RESULT_OK, null)
        intents.last().send(context, SmsManager.RESULT_ERROR_RADIO_OFF, null)
        idleMainLooper()

        val error = pending.await().error.orEmpty()
        assertTrue(error, error.contains("Check your messages before sending it again"))
        assertFalse(error, error.contains("didn't send"))
    }

    @Test
    fun `a failed part of a multipart send is reported as partly failed`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            SmsSender.send(context, smsManager, PHONE, "a".repeat(200))
        }

        reportAll(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(SmsSendOutcome.PARTLY_FAILED, pending.await())
    }

    @Test
    fun `a failed single part send is reported as failed`() = runBlocking {
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            SmsSender.send(context, smsManager, PHONE, "hello")
        }

        reportAll(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertEquals(SmsSendOutcome.FAILED, pending.await())
    }

    @Test
    fun `no answer from the radio is unconfirmed rather than sent`() = runBlocking {
        val outcome = SmsSender.send(context, smsManager, PHONE, "hello", timeoutMs = 50L)

        assertEquals(SmsSendOutcome.UNCONFIRMED, outcome)
    }

    @Test
    fun `auto reply splits long text and waits for RESULT_OK`() = runBlocking {
        val dispatcher = ReplyDispatcher(context)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.replyViaSms(PHONE, "a".repeat(200), context)
        }

        assertTrue(shadowSms.lastSentMultipartTextMessageParams.parts.size > 1)
        assertNull(shadowSms.lastSentTextMessageParams)
        assertFalse(pending.isCompleted)

        reportAll(Activity.RESULT_OK)
        assertTrue(pending.await())
    }

    @Test
    fun `auto reply reports a radio failure as not sent`() = runBlocking {
        val dispatcher = ReplyDispatcher(context)
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.replyViaSms(PHONE, "hello", context)
        }

        reportAll(SmsManager.RESULT_ERROR_GENERIC_FAILURE)

        assertFalse(pending.await())
    }

    @Test
    fun `composer left open for the user is a hand-off, not a failure a fallback could act on`() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS)
        installMessagingApp()

        val result = sendAction("hello")

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals("smsto", shadowOf(context).nextStartedActivity.data?.scheme)
        assertNull(shadowSms.lastSentMultipartTextMessageParams)
    }

    @Test
    fun `backup composer left open for the user is a hand-off too`() = runBlocking {
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS)
        installMessagingApp()
        val sendToFails = object : ContextWrapper(context) {
            val opened = mutableListOf<Intent>()
            override fun startActivity(intent: Intent) {
                if (intent.action == Intent.ACTION_SENDTO) throw ActivityNotFoundException()
                opened += intent
            }
        }

        val result = sendAction("hello", sendToFails)

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals("sms", sendToFails.opened.single().data?.scheme)
    }

    private suspend fun sendAction(message: String, sendContext: Context = context): ActionResult {
        val actions = CommunicationActions(
            FakeContactResolver(context).apply { contacts += Contact(name = "Sam", phoneNumber = PHONE) },
            CallFlowExecutor(NoCalls)
        )
        return actions.getActions().first { it.name == "SEND_SMS" }
            .execute(mapOf("contact" to "Sam", "message" to message), sendContext)
    }

    @Suppress("DEPRECATION")
    private fun installMessagingApp() {
        val component = ComponentName("com.example.sms", "com.example.sms.ComposeActivity")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_SENDTO).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("smsto")
            }
        )
    }

    private fun reportAll(resultCode: Int) {
        shadowSms.lastSentMultipartTextMessageParams.sentIntents.forEach {
            it.send(context, resultCode, null)
        }
        idleMainLooper()
    }

    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private object NoCalls : CallFlowVerifier {
        override fun isCallInProgress(context: Context): Boolean = false
        override suspend fun awaitNewCallStarted(context: Context, wasAlreadyInProgress: Boolean): Boolean = false
    }

    private companion object {
        const val PHONE = "+15551234567"
        const val SMS_FEATURE = "android.hardware.telephony.messaging"
    }
}

/**
 * The real SmsManager.divideMessage only needs a subscription id, which Robolectric can't
 * provide without an ISms binder. Supplying it keeps the platform's own splitting under test.
 */
@Implements(SmsManager::class)
class ShadowSmsManagerWithSubscription : ShadowSmsManager() {
    @Implementation
    fun getSubscriptionId(): Int = 1
}
