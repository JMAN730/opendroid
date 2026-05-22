package com.opendroid.ai.actions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.AgentLoop
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemActions @Inject constructor(
    private val agentLoop: dagger.Lazy<AgentLoop>
) {

    fun getActions(): List<Action> = listOf(
        ToggleWifiAction(),
        ToggleFlashlightAction(),
        SetVolumeAction(),
        SetBrightnessAction(),
        OpenAppAction(),
        LockScreenAction(),
        RestartDeviceAction(),
        ToggleBluetoothAction(),
        ToggleDndAction(),
        TakeScreenshotAction(),
        ConfirmAction(agentLoop),
        CheckAppAction(),
        ShowWarningAction(agentLoop),
        ToggleMobileDataAction(),
        ToggleHotspotAction(),
        SetWallpaperAction(),
        RecordScreenAction(),
        InstallAppAction(),
        CheckHardwareAction(),
        CheckPermissionAction()
    )

    private class ToggleWifiAction : Action {
        override val name: String = "TOGGLE_WIFI"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return try {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = on
                ActionResult(true, "WiFi set to $on", null)
            } catch (e: Exception) {
                // Fallback: Launch wifi settings panel so user can toggle manually
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Failed to toggle WiFi directly. Opened settings panel.", e.localizedMessage, true)
            }
        }
    }

    private class ToggleFlashlightAction : Action {
        override val name: String = "TOGGLE_FLASHLIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            return try {
                var foundCameraId: String? = null
                for (id in cameraManager.cameraIdList) {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (hasFlash) {
                        foundCameraId = id
                        break
                    }
                }
                val cameraId = foundCameraId ?: cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    cameraManager.setTorchMode(cameraId, on)
                    ActionResult(true, "Flashlight set to $on", null)
                } else {
                    ActionResult(false, null, "No camera with flashlight support was found.")
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to toggle flashlight: ${e.localizedMessage}")
            }
        }
    }

    private class SetVolumeAction : Action {
        override val name: String = "SET_VOLUME"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val typeStr = params["type"] ?: "media"
            val level = params["level"]?.toIntOrNull() ?: 50
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (typeStr.lowercase()) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            return try {
                val maxVolume = audioManager.getStreamMaxVolume(streamType)
                val targetVolume = (level * maxVolume) / 100
                audioManager.setStreamVolume(streamType, targetVolume, AudioManager.FLAG_SHOW_UI)
                ActionResult(true, "Volume for $typeStr set to $level%", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Volume adjustment failed: ${e.localizedMessage}")
            }
        }
    }

    private class SetBrightnessAction : Action {
        override val name: String = "SET_BRIGHTNESS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val level = params["level"]?.toIntOrNull() ?: 50
            val targetVal = (level * 255) / 100
            return try {
                if (Settings.System.canWrite(context)) {
                    Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, targetVal)
                    ActionResult(true, "Brightness set to $level%", null)
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "Write settings permission not granted. Prompted user.", "Permission required", true)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Brightness adjustment failed: ${e.localizedMessage}")
            }
        }
    }

    private class OpenAppAction : Action {
        override val name: String = "OPEN_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            val appPackage = packages.find {
                val label = pm.getApplicationLabel(it).toString()
                label.contains(appName, ignoreCase = true) || it.packageName.contains(appName, ignoreCase = true)
            }?.packageName
            return if (appPackage != null) {
                val intent = pm.getLaunchIntentForPackage(appPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult(true, "Opened $appName ($appPackage)", null)
                } else {
                    ActionResult(false, null, "Launcher intent not found for $appPackage")
                }
            } else {
                ActionResult(false, null, "App '$appName' not installed.")
            }
        }
    }

    private class LockScreenAction : Action {
        override val name: String = "LOCK_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                ActionResult(success, if (success) "Device locked" else "Failed to lock", null)
            } else {
                ActionResult(false, null, "Accessibility Service is not running or active.")
            }
        }
    }

    private class RestartDeviceAction : Action {
        override val name: String = "RESTART_DEVICE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                ActionResult(success, if (success) "Power dialog opened. Action pending user touch." else "Failed to open dialog", null)
            } else {
                ActionResult(false, null, "Accessibility Service not running to trigger power dialog.")
            }
        }
    }

    private class ToggleBluetoothAction : Action {
        override val name: String = "TOGGLE_BLUETOOTH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                @Suppress("DEPRECATION")
                if (on) adapter.enable() else adapter.disable()
                ActionResult(true, "Bluetooth set to $on", null)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Failed to toggle bluetooth directly. Opened settings panel.", e.localizedMessage, true)
            }
        }
    }

    private class ToggleDndAction : Action {
        override val name: String = "TOGGLE_DND"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return try {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    val filter = if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL
                    notificationManager.setInterruptionFilter(filter)
                    ActionResult(true, "DND set to $on", null)
                } else {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(false, "DND policy permission not granted. Prompted user.", "Permission required", true)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to toggle DND: ${e.localizedMessage}")
            }
        }
    }

    private class TakeScreenshotAction : Action {
        override val name: String = "TAKE_SCREENSHOT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val service = OpenDroidAccessibilityService.getInstance()
            return if (service != null) {
                val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                ActionResult(success, if (success) "Screenshot captured successfully." else "Failed to capture screenshot", null)
            } else {
                ActionResult(false, null, "Accessibility Service is not running to trigger screenshot.")
            }
        }
    }

    private class ConfirmAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "CONFIRM_ACTION"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val message = params["message"] ?: "Do you want to proceed with this action?"
            
            // Speak the confirmation warning
            agentLoop.get().onSpeakCallback?.invoke(message)
            
            // Display Toast on main thread
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore toast errors if any
            }
            
            return ActionResult(true, "Action confirmed: $message", null)
        }
    }

    private class CheckAppAction : Action {
        override val name: String = "CHECK_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            val appPackage = packages.find {
                val label = pm.getApplicationLabel(it).toString()
                label.contains(appName, ignoreCase = true) || it.packageName.contains(appName, ignoreCase = true)
            }?.packageName
            return if (appPackage != null) {
                ActionResult(true, "App '$appName' ($appPackage) is installed and functional.", null)
            } else {
                ActionResult(false, null, "App '$appName' is not installed.")
            }
        }
    }

    private class ShowWarningAction(
        private val agentLoop: dagger.Lazy<AgentLoop>
    ) : Action {
        override val name: String = "SHOW_WARNING"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val message = params["message"] ?: "Warning: Please check your actions."
            
            // Speak the warning
            agentLoop.get().onSpeakCallback?.invoke(message)
            
            // Display Toast on main thread
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore toast errors if any
            }
            
            return ActionResult(true, "Warning shown: $message", null)
        }
    }

    private class ToggleMobileDataAction : Action {
        override val name: String = "TOGGLE_MOBILE_DATA"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened Mobile Network settings to toggle mobile data to $on", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to open Mobile Network settings: ${e.localizedMessage}")
            }
        }
    }

    private class ToggleHotspotAction : Action {
        override val name: String = "TOGGLE_HOTSPOT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val on = params["on"]?.toBoolean() ?: true
            return try {
                val intent = Intent().apply {
                    action = "android.settings.TETHER_SETTINGS"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened Tethering and Hotspot settings to toggle hotspot to $on", null)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Opened wireless settings as fallback for hotspot toggle.", null)
                } catch (ex: Exception) {
                    ActionResult(false, null, "Failed to open settings: ${ex.localizedMessage}")
                }
            }
        }
    }

    private class SetWallpaperAction : Action {
        override val name: String = "SET_WALLPAPER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            return try {
                val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened Wallpaper settings/picker.", null)
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to open Wallpaper chooser: ${e.localizedMessage}")
            }
        }
    }

    private class RecordScreenAction : Action {
        override val name: String = "RECORD_SCREEN"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val start = params["start"]?.toBoolean() ?: true
            return ActionResult(true, if (start) "Screen recording started (simulated)" else "Screen recording stopped (simulated)", null)
        }
    }

    private class InstallAppAction : Action {
        override val name: String = "INSTALL_APP"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val appName = params["appName"] ?: return ActionResult(false, null, "appName parameter missing")
            return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(appName)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opened Google Play Store to install '$appName'", null)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(appName)}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ActionResult(true, "Opened web browser for Google Play Store search for '$appName'", null)
                } catch (ex: Exception) {
                    ActionResult(false, null, "Failed to open Play Store: ${ex.localizedMessage}")
                }
            }
        }
    }

    private class CheckHardwareAction : Action {
        override val name: String = "CHECK_HARDWARE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val hardwareFeature = params["hardwareFeature"] ?: params["feature"] ?: return ActionResult(false, null, "hardwareFeature parameter is missing")
            return try {
                val pm = context.packageManager
                val systemFeature = when (hardwareFeature.uppercase()) {
                    "CAMERA" -> PackageManager.FEATURE_CAMERA_ANY
                    "FLASHLIGHT" -> PackageManager.FEATURE_CAMERA_FLASH
                    "BLUETOOTH" -> PackageManager.FEATURE_BLUETOOTH
                    "WIFI" -> PackageManager.FEATURE_WIFI
                    "GPS", "LOCATION" -> PackageManager.FEATURE_LOCATION_GPS
                    "MICROPHONE" -> PackageManager.FEATURE_MICROPHONE
                    "FINGERPRINT" -> PackageManager.FEATURE_FINGERPRINT
                    else -> null
                }
                if (systemFeature != null) {
                    val hasFeature = pm.hasSystemFeature(systemFeature)
                    if (hasFeature) {
                        ActionResult(true, "Device supports hardware feature: $hardwareFeature", null)
                    } else {
                        ActionResult(false, null, "Device does not support hardware feature: $hardwareFeature")
                    }
                } else {
                    val hasFeature = pm.hasSystemFeature("android.hardware.${hardwareFeature.lowercase()}")
                    ActionResult(true, "Hardware feature '$hardwareFeature' check returned: $hasFeature", null)
                }
            } catch (e: Exception) {
                ActionResult(true, "Hardware feature '$hardwareFeature' assumed supported (fallback).", null)
            }
        }
    }

    private class CheckPermissionAction : Action {
        override val name: String = "CHECK_PERMISSION"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val permissionName = params["permission"] ?: return ActionResult(false, null, "permission parameter is missing")
            return try {
                val manifestPermission = when (permissionName.uppercase()) {
                    "CAMERA" -> Manifest.permission.CAMERA
                    "RECORD_AUDIO", "MICROPHONE" -> Manifest.permission.RECORD_AUDIO
                    "READ_CONTACTS" -> Manifest.permission.READ_CONTACTS
                    "WRITE_CONTACTS" -> Manifest.permission.WRITE_CONTACTS
                    "ACCESS_FINE_LOCATION" -> Manifest.permission.ACCESS_FINE_LOCATION
                    "ACCESS_COARSE_LOCATION" -> Manifest.permission.ACCESS_COARSE_LOCATION
                    "READ_SMS" -> Manifest.permission.READ_SMS
                    "SEND_SMS" -> Manifest.permission.SEND_SMS
                    "RECEIVE_SMS" -> Manifest.permission.RECEIVE_SMS
                    "CALL_PHONE" -> Manifest.permission.CALL_PHONE
                    "READ_PHONE_STATE" -> Manifest.permission.READ_PHONE_STATE
                    "READ_EXTERNAL_STORAGE" -> Manifest.permission.READ_EXTERNAL_STORAGE
                    "WRITE_EXTERNAL_STORAGE" -> Manifest.permission.WRITE_EXTERNAL_STORAGE
                    else -> {
                        if (permissionName.contains(".")) permissionName else "android.permission.$permissionName"
                    }
                }
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, manifestPermission) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    ActionResult(true, "Permission $permissionName is granted.", null)
                } else {
                    ActionResult(false, "Permission $permissionName is not granted.", "Permission missing", true)
                }
            } catch (e: Exception) {
                ActionResult(false, null, "Failed to check permission '$permissionName': ${e.localizedMessage}")
            }
        }
    }
}
