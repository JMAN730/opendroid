package com.opendroid.ai.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.actions.base.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RadioToggleActionsTest {
    private val baseContext: Context = ApplicationProvider.getApplicationContext()
    private val context = RecordingContext(baseContext)

    @Test
    fun `wifi rejected by Android is not success and opens the wifi panel`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = false)

        val result = toggleWifiAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "off"), context)

        assertFalse(result.success)
        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(result.error.orEmpty().contains("turn it off"))
        assertEquals(listOf(Settings.Panel.ACTION_WIFI), context.startedActions)
    }

    @Test
    fun `wifi accepted but never observed is not success`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = true)

        val result = toggleWifiAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "off"), context)

        assertTrue(result is ActionResult.UserActionRequired)
    }

    @Test
    fun `wifi reports success only after the new state is observed`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = true, mutateOnRequest = true)

        val result = toggleWifiAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "off"), context)

        assertTrue(result.success)
        assertEquals("WiFi turned off!", result.data)
        assertTrue(context.startedActions.isEmpty())
    }

    @Test
    fun `wifi already in the requested state does not request a change`() = runBlocking {
        val controller = FakeRadioController(enabled = true)

        val result = toggleWifiAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "on"), context)

        assertTrue(result.success)
        assertEquals("WiFi is already on!", result.data)
        assertTrue(controller.requests.isEmpty())
    }

    @Test
    fun `wifi falls back to full settings when the panel cannot open`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = false)
        val context = RecordingContext(baseContext, unavailable = setOf(Settings.Panel.ACTION_WIFI))

        val result = toggleWifiAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "off"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals(listOf(Settings.ACTION_WIFI_SETTINGS), context.startedActions)
    }

    @Test
    fun `real WifiManager wiring verifies the state it requested`() = runBlocking {
        val wifiOn = toggleWifiAction().execute(mapOf("state" to "on"), context)
        val wifiOff = toggleWifiAction().execute(mapOf("state" to "off"), context)

        assertTrue(wifiOn.success)
        assertEquals("WiFi turned off!", wifiOff.data)
    }

    @Test
    fun `mobile data whose direct request throws is not success and opens the panel`() = runBlocking {
        val controller = FakeRadioController(
            enabled = false,
            requestFailure = SecurityException("MODIFY_PHONE_STATE")
        )

        val result = toggleMobileDataAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(result.error.orEmpty().contains("turn it on"))
        assertEquals(listOf(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), context.startedActions)
    }

    @Test
    fun `mobile data toggle flips the observed state instead of assuming on`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = true, mutateOnRequest = true)

        val result = toggleMobileDataAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "toggle"), context)

        assertEquals(listOf(false), controller.requests)
        assertEquals("Mobile data turned off!", result.data)
    }

    @Test
    fun `toggle with an unreadable state asks the user without claiming a direction`() = runBlocking {
        val controller = FakeRadioController(enabled = null)

        val result = toggleMobileDataAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "toggle"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertTrue(controller.requests.isEmpty())
        assertFalse(result.error.orEmpty().contains("turn it on"))
        assertFalse(result.error.orEmpty().contains("turn it off"))
    }

    @Test
    fun `hotspot on a current Android opens tether settings and requires the user`() = runBlocking {
        val result = toggleHotspotAction().execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals(listOf(TETHER_SETTINGS), context.startedActions)
    }

    @Test
    fun `hotspot request that returns normally without changing state is not success`() = runBlocking {
        // Android 8.x: setWifiApEnabled returns normally and does nothing.
        val controller = FakeRadioController(enabled = false, requestResult = true)

        val result = toggleHotspotAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals(listOf(true), controller.requests)
        assertEquals(listOf(TETHER_SETTINGS), context.startedActions)
    }

    @Test
    fun `hotspot toggle flips the observed state instead of assuming on`() = runBlocking {
        val controller = FakeRadioController(enabled = true, requestResult = true, mutateOnRequest = true)

        val result = toggleHotspotAction({ controller }, ::observeOnce)
            .execute(mapOf("state" to "toggle"), context)

        assertEquals(listOf(false), controller.requests)
        assertEquals("Hotspot turned off!", result.data)
    }

    @Test
    fun `hotspot falls back to wireless settings when tether settings are missing`() = runBlocking {
        val context = RecordingContext(baseContext, unavailable = setOf(TETHER_SETTINGS))

        val result = toggleHotspotAction().execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.UserActionRequired)
        assertEquals(listOf(Settings.ACTION_WIRELESS_SETTINGS), context.startedActions)
    }

    @Test
    fun `nothing openable is a plain failure`() = runBlocking {
        val context = RecordingContext(
            baseContext,
            unavailable = setOf(TETHER_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS)
        )

        val result = toggleHotspotAction().execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.Failure)
        assertEquals("Couldn't toggle hotspot.", result.error)
    }

    @Test
    fun `missing hardware is a plain failure`() = runBlocking {
        val result = toggleWifiAction({ null }, ::observeOnce).execute(mapOf("state" to "on"), context)

        assertTrue(result is ActionResult.Failure)
        assertTrue(context.startedActions.isEmpty())
    }

    private fun observeOnce(controller: RadioController, target: Boolean): Boolean =
        controller.isEnabled() == target

    private class FakeRadioController(
        var enabled: Boolean?,
        private val requestResult: Boolean = false,
        private val mutateOnRequest: Boolean = false,
        private val requestFailure: Exception? = null
    ) : RadioController {
        val requests = mutableListOf<Boolean>()

        override fun isEnabled(): Boolean? = enabled

        override fun requestState(enabled: Boolean): Boolean {
            requests += enabled
            requestFailure?.let { throw it }
            if (mutateOnRequest) this.enabled = enabled
            return requestResult
        }
    }

    private class RecordingContext(
        base: Context,
        private val unavailable: Set<String> = emptySet()
    ) : ContextWrapper(base) {
        val startedActions = mutableListOf<String>()

        override fun startActivity(intent: Intent) {
            val action = intent.action.orEmpty()
            if (action in unavailable) throw ActivityNotFoundException(action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            startedActions += action
        }
    }

    private companion object {
        const val TETHER_SETTINGS = "android.settings.TETHER_SETTINGS"
    }
}
