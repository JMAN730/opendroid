package com.opendroid.ai.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult

internal interface RadioController {
    /** The current state, or null when Android won't tell this app. */
    fun isEnabled(): Boolean?

    /** True means Android accepted the request, not that the state changed. */
    fun requestState(enabled: Boolean): Boolean
}

private class AndroidWifiController(private val manager: WifiManager) : RadioController {
    @Suppress("DEPRECATION")
    override fun isEnabled(): Boolean? = manager.isWifiEnabled

    // Returns false without throwing for apps targeting API 29+.
    @Suppress("DEPRECATION")
    override fun requestState(enabled: Boolean): Boolean = manager.setWifiEnabled(enabled)
}

private class AndroidMobileDataController(private val telephony: TelephonyManager) : RadioController {
    @SuppressLint("MissingPermission")
    override fun isEnabled(): Boolean? = telephony.isDataEnabled

    // Throws without MODIFY_PHONE_STATE, which this app can't hold.
    override fun requestState(enabled: Boolean): Boolean {
        val method = telephony.javaClass.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
        method.invoke(telephony, enabled)
        return true
    }
}

private class AndroidHotspotController(private val manager: WifiManager) : RadioController {
    override fun isEnabled(): Boolean? =
        manager.javaClass.getMethod("isWifiApEnabled").invoke(manager) as? Boolean

    // On Android 8.x this can return normally without doing anything.
    override fun requestState(enabled: Boolean): Boolean {
        val method = manager.javaClass.getMethod(
            "setWifiApEnabled",
            android.net.wifi.WifiConfiguration::class.java,
            Boolean::class.javaPrimitiveType
        )
        return method.invoke(manager, null, enabled) as? Boolean ?: false
    }
}

private fun wifiController(context: Context): RadioController? =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.let(::AndroidWifiController)

private fun mobileDataController(context: Context): RadioController? =
    (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
        ?.let(::AndroidMobileDataController)

private fun hotspotController(context: Context): RadioController? =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
        ?.let(::AndroidHotspotController)

private fun RadioController.readState(tag: String): Boolean? = try {
    isEnabled()
} catch (e: Exception) {
    Log.w(tag, "State read failed: ${e.message}")
    null
}

private suspend fun waitForRadioState(controller: RadioController, targetOn: Boolean): Boolean {
    val deadline = System.currentTimeMillis() + 2_000L
    do {
        if (controller.readState("RadioToggle") == targetOn) return true
        if (System.currentTimeMillis() >= deadline) return false
        kotlinx.coroutines.delay(100L)
    } while (true)
}

private class SettingsScreen(val action: String, val description: String)

/**
 * Turns a radio on or off, reporting success only once the new state has been read back.
 * Android reserves these switches for the user on current versions, so the usual outcome
 * is an opened settings screen, which is [ActionResult.UserActionRequired] and not success.
 */
private class RadioToggleAction(
    override val name: String,
    private val label: String,
    private val controllerProvider: (Context) -> RadioController?,
    private val waitForState: suspend (RadioController, Boolean) -> Boolean,
    private val settingsScreens: List<SettingsScreen>
) : Action {
    private val capitalizedLabel = label.replaceFirstChar { it.uppercase() }

    override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
        val requestedState = (params["state"] ?: params["on"] ?: "toggle")
            .lowercase().trim()

        val controller = controllerProvider(context)
            ?: return ActionResult(false, null, "This device doesn't support $label.")

        val currentlyOn = controller.readState(name)
        val targetOn = when (requestedState) {
            "on", "true", "enable", "yes"    -> true
            "off", "false", "disable", "no"  -> false
            else                             -> currentlyOn?.not()
        }

        if (targetOn != null && targetOn == currentlyOn) {
            return ActionResult(true, "$capitalizedLabel is already ${stateWord(targetOn)}!", null)
        }

        if (targetOn != null && requestAndVerify(controller, targetOn)) {
            return ActionResult(true, "$capitalizedLabel turned ${stateWord(targetOn)}!", null)
        }

        val opened = openSettings(context)
            ?: return ActionResult(false, null, "Couldn't toggle $label.")
        return ActionResult.UserActionRequired(
            if (targetOn != null) {
                val stateWord = stateWord(targetOn)
                "Couldn't turn $label $stateWord directly. I opened $opened; please turn it $stateWord there."
            } else {
                "Couldn't read the $label state, so I opened $opened; please switch it there."
            }
        )
    }

    private fun stateWord(on: Boolean) = if (on) "on" else "off"

    private suspend fun requestAndVerify(controller: RadioController, targetOn: Boolean): Boolean {
        val accepted = try {
            controller.requestState(targetOn)
        } catch (e: Exception) {
            Log.w(name, "Direct request failed: ${e.message}")
            false
        }
        return accepted && waitForState(controller, targetOn)
    }

    /** Opens the first screen this device has and returns its description. */
    private fun openSettings(context: Context): String? {
        for (screen in settingsScreens) {
            try {
                context.startActivity(
                    Intent(screen.action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
                return screen.description
            } catch (e: Exception) {
                Log.w(name, "Couldn't open ${screen.action}: ${e.message}")
            }
        }
        return null
    }
}

// Panels exist from API 29; the app's minSdk is 26. Callers pass the inlined
// Settings.Panel strings and suppress InlinedApi, since lint does not see this guard.
private fun panelIfAvailable(action: String, description: String): List<SettingsScreen> =
    if (android.os.Build.VERSION.SDK_INT >= 29) listOf(SettingsScreen(action, description)) else emptyList()

@SuppressLint("InlinedApi")
internal fun toggleWifiAction(
    controllerProvider: (Context) -> RadioController? = ::wifiController,
    waitForState: suspend (RadioController, Boolean) -> Boolean = ::waitForRadioState
): Action = RadioToggleAction(
    name = "TOGGLE_WIFI",
    label = "WiFi",
    controllerProvider = controllerProvider,
    waitForState = waitForState,
    settingsScreens = panelIfAvailable(Settings.Panel.ACTION_WIFI, "the WiFi panel") +
        SettingsScreen(Settings.ACTION_WIFI_SETTINGS, "WiFi settings")
)

@SuppressLint("InlinedApi")
internal fun toggleMobileDataAction(
    controllerProvider: (Context) -> RadioController? = ::mobileDataController,
    waitForState: suspend (RadioController, Boolean) -> Boolean = ::waitForRadioState
): Action = RadioToggleAction(
    name = "TOGGLE_MOBILE_DATA",
    label = "mobile data",
    controllerProvider = controllerProvider,
    waitForState = waitForState,
    settingsScreens = panelIfAvailable(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "the internet panel") +
        SettingsScreen(Settings.ACTION_DATA_ROAMING_SETTINGS, "mobile network settings")
)

internal fun toggleHotspotAction(
    controllerProvider: (Context) -> RadioController? = ::hotspotController,
    waitForState: suspend (RadioController, Boolean) -> Boolean = ::waitForRadioState
): Action = RadioToggleAction(
    name = "TOGGLE_HOTSPOT",
    label = "hotspot",
    controllerProvider = controllerProvider,
    waitForState = waitForState,
    settingsScreens = listOf(
        SettingsScreen("android.settings.TETHER_SETTINGS", "hotspot settings"),
        SettingsScreen(Settings.ACTION_WIRELESS_SETTINGS, "network settings")
    )
)
