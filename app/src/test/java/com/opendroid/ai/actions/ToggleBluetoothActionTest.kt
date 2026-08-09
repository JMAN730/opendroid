package com.opendroid.ai.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToggleBluetoothActionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `already requested state succeeds without requesting a change`() = runBlocking {
        val controller = FakeBluetoothController(enabled = true)
        val action = actionFor(controller)

        val result = action.execute(mapOf("state" to "on"), context)

        assertTrue(result.success)
        assertEquals("Bluetooth is already on!", result.data)
        assertFalse(controller.requested)
    }

    @Test
    fun `direct request succeeds only after target state is observed`() = runBlocking {
        val controller = FakeBluetoothController(enabled = false, mutateOnRequest = true)
        val action = actionFor(controller)

        val result = action.execute(mapOf("state" to "on"), context)

        assertTrue(result.success)
        assertEquals("Bluetooth turned on!", result.data)
        assertTrue(controller.requested)
    }

    @Test
    fun `toggle verifies the opposite of the observed state`() = runBlocking {
        val controller = FakeBluetoothController(enabled = true, mutateOnRequest = true)
        val action = actionFor(controller)

        val result = action.execute(mapOf("state" to "toggle"), context)

        assertTrue(result.success)
        assertEquals("Bluetooth turned off!", result.data)
        assertFalse(controller.enabled)
    }

    @Test
    fun `fallback reports pending instead of success when settings did not change state`() = runBlocking {
        val controller = FakeBluetoothController(enabled = false, requestResult = false)
        var launchedIntentAction: String? = null
        val action = ToggleBluetoothAction(
            controllerProvider = { controller },
            waitForState = { _, _ -> false },
            launchIntent = { _, intent -> launchedIntentAction = intent.action }
        )

        val result = action.execute(mapOf("state" to "on"), context)

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("opened the Bluetooth enable prompt"))
        assertEquals("android.bluetooth.adapter.action.REQUEST_ENABLE", launchedIntentAction)
    }

    @Test
    fun `verification failure remains a failure when fallback cannot be opened`() = runBlocking {
        val controller = FakeBluetoothController(enabled = false, mutateOnRequest = false)
        val action = ToggleBluetoothAction(
            controllerProvider = { controller },
            waitForState = { _, _ -> false },
            launchIntent = { _, _ -> error("no settings activity") }
        )

        val result = action.execute(mapOf("state" to "on"), context)

        assertFalse(result.success)
        assertEquals("Couldn't open the Bluetooth enable prompt to turn Bluetooth on.", result.error)
    }

    private fun actionFor(controller: FakeBluetoothController) = ToggleBluetoothAction(
        controllerProvider = { controller },
        waitForState = { bluetoothController, targetOn ->
            bluetoothController.isEnabled() == targetOn
        },
        launchIntent = { _, _ -> error("fallback should not be used") }
    )

    private class FakeBluetoothController(
        var enabled: Boolean,
        private val requestResult: Boolean = true,
        private val mutateOnRequest: Boolean = false
    ) : BluetoothController {
        var requested: Boolean = false

        override fun isEnabled(): Boolean = enabled

        override fun requestState(enabled: Boolean): Boolean {
            requested = true
            if (mutateOnRequest) this.enabled = enabled
            return requestResult
        }
    }
}
