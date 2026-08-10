package com.opendroid.ai.actions

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartHomeActionsTest {

    private val baseContext = ApplicationProvider.getApplicationContext<Context>()
    private val actions = SmartHomeActions().getActions().associateBy { it.name }

    @Test
    fun `smart home actions are all registered`() {
        assertEquals(
            setOf("SMART_HOME", "TOGGLE_LIGHT", "SET_THERMOSTAT", "LOCK_DOOR"),
            actions.keys
        )
    }

    @Test
    fun `general smart home opens Google Home when installed`() = runBlocking {
        installGoogleHomeLauncher()
        val context = RecordingContext(baseContext)

        val result = execute(
            "SMART_HOME",
            mapOf("device" to "living room fan", "action" to "toggle", "value" to "on"),
            context
        )

        assertTrue(result.success)
        assertEquals(
            "Got it \u2014 telling Google Home to toggle the living room fan!",
            result.data
        )
        assertEquals(
            GOOGLE_HOME_PACKAGE,
            context.lastStartedIntent?.component?.packageName
        )
    }

    @Test
    fun `general smart home searches when Google Home is not installed`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute(
            "SMART_HOME",
            mapOf("device" to "kitchen light", "action" to "off", "value" to "now"),
            context
        )

        assertTrue(result.success)
        assertEquals("Google Home isn't installed, so I searched for it instead.", result.data)
        assertEquals(Intent.ACTION_WEB_SEARCH, context.lastStartedIntent?.action)
        assertEquals(
            "turn off kitchen light now",
            context.lastStartedIntent?.getStringExtra("query")
        )
    }

    @Test
    fun `toggle light includes optional details and reports on or off state`() = runBlocking {
        val context = RecordingContext(baseContext)

        val onResult = execute(
            "TOGGLE_LIGHT",
            mapOf(
                "room" to "bedroom",
                "on" to "true",
                "brightness" to "40",
                "color" to "blue"
            ),
            context
        )

        assertTrue(onResult.success)
        assertEquals("Turning the bedroom light on!", onResult.data)
        assertEquals(
            "turn light on in bedroom to 40 percent color blue",
            context.lastStartedIntent?.getStringExtra("query")
        )

        val offResult = execute(
            "TOGGLE_LIGHT",
            mapOf("room" to "hallway", "on" to "false"),
            context
        )

        assertTrue(offResult.success)
        assertEquals("Turning the hallway light off!", offResult.data)
        assertEquals(
            "turn light off in hallway ",
            context.lastStartedIntent?.getStringExtra("query")
        )
    }

    @Test
    fun `set thermostat reports missing temperature without launching`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute("SET_THERMOSTAT", emptyMap(), context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals("temperature parameter is missing", result.error)
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `set thermostat dispatches the requested temperature`() = runBlocking {
        val context = RecordingContext(baseContext)

        val result = execute("SET_THERMOSTAT", mapOf("temperature" to "22"), context)

        assertTrue(result.success)
        assertEquals("Setting thermostat to 22!", result.data)
        assertEquals(
            "set thermostat temperature to 22",
            context.lastStartedIntent?.getStringExtra("query")
        )
    }

    @Test
    fun `lock door defaults to the front door and accepts an explicit location`() = runBlocking {
        val context = RecordingContext(baseContext)

        val defaultResult = execute("LOCK_DOOR", emptyMap(), context)

        assertTrue(defaultResult.success)
        assertEquals("Locking the front door!", defaultResult.data)
        assertEquals("lock front door", context.lastStartedIntent?.getStringExtra("query"))

        val explicitResult = execute(
            "LOCK_DOOR",
            mapOf("location" to "garage door"),
            context
        )

        assertTrue(explicitResult.success)
        assertEquals("Locking the garage door!", explicitResult.data)
        assertEquals("lock garage door", context.lastStartedIntent?.getStringExtra("query"))
    }

    @Test
    fun `lock door reports a truthful failure when launching fails`() = runBlocking {
        val context = RecordingContext(
            baseContext,
            IllegalStateException("launcher unavailable")
        )

        val result = execute("LOCK_DOOR", mapOf("location" to "front door"), context)

        assertTrue(result is ActionResult.Failure)
        assertFalse(result.success)
        assertEquals("Couldn't lock the door right now.", result.error)
        assertNull(context.lastStartedIntent)
    }

    @Test
    fun `lock door remains behind the irreversible approval boundary`() {
        val definition = ActionSchema.getAction("LOCK_DOOR")

        assertNotNull(definition)
        assertTrue(definition!!.neverAutoApprove)
        assertEquals(ActionRisk.IRREVERSIBLE, ActionSchema.riskForAction("LOCK_DOOR"))
    }

    private suspend fun execute(
        actionName: String,
        params: Map<String, String>,
        context: Context
    ): ActionResult = actions.getValue(actionName).execute(params, context)

    @Suppress("DEPRECATION")
    private fun installGoogleHomeLauncher() {
        val component = ComponentName(GOOGLE_HOME_PACKAGE, "$GOOGLE_HOME_PACKAGE.MainActivity")
        val packageManager = shadowOf(baseContext.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
    }

    private class RecordingContext(
        base: Context,
        private val startActivityFailure: Exception? = null
    ) : ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()
        val lastStartedIntent: Intent?
            get() = startedIntents.lastOrNull()

        override fun startActivity(intent: Intent) {
            startActivityFailure?.let { throw it }
            startedIntents += intent
        }
    }

    private companion object {
        const val GOOGLE_HOME_PACKAGE = "com.google.android.apps.chromecast.app"
    }
}
