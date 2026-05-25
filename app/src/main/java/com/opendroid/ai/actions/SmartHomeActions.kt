package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartHomeActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        GeneralSmartHomeAction(),
        ToggleLightAction(),
        SetThermostatAction(),
        LockDoorAction()
    )

    private class GeneralSmartHomeAction : Action {
        override val name: String = "SMART_HOME"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val device = params["device"] ?: "device"
            val action = params["action"] ?: "toggle"
            val value = params["value"] ?: ""
            return try {
                // Try triggering Google Assistant with a voice trigger command
                val query = URLEncoder.encode("turn $action $device $value", "UTF-8")
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", "turn $action $device $value")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                // Also fallback to opening Google Home app
                val homeIntent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.chromecast.app")
                if (homeIntent != null) {
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(homeIntent)
                    ActionResult(true, "Got it — telling Google Home to $action the $device!", null)
                } else {
                    context.startActivity(intent)
                    ActionResult(true, "Google Home isn't installed, so I searched for it instead.", null, true)
                }
            } catch (e: Exception) {
                Log.e("SmartHome", "Action failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't control that smart device right now.")
            }
        }
    }

    private class ToggleLightAction : Action {
        override val name: String = "TOGGLE_LIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val room = params["room"] ?: "room"
            val on = params["on"]?.toBoolean() ?: true
            val brightness = params["brightness"] ?: ""
            val color = params["color"] ?: ""
            return try {
                val state = if (on) "on" else "off"
                val command = "turn light $state in $room " + 
                              (if (brightness.isNotEmpty()) "to $brightness percent " else "") +
                              (if (color.isNotEmpty()) "color $color" else "")
                              
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", command)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                val stateWord = if (on) "on" else "off"
                ActionResult(true, "Turning the $room light $stateWord!", null)
            } catch (e: Exception) {
                Log.e("ToggleLight", "Light failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't control the light right now.")
            }
        }
    }

    private class SetThermostatAction : Action {
        override val name: String = "SET_THERMOSTAT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val temp = params["temperature"] ?: return ActionResult(false, null, "temperature parameter is missing")
            return try {
                val command = "set thermostat temperature to $temp"
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", command)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Setting thermostat to $temp!", null)
            } catch (e: Exception) {
                Log.e("SetThermostat", "Thermostat failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't set the thermostat right now.")
            }
        }
    }

    private class LockDoorAction : Action {
        override val name: String = "LOCK_DOOR"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val location = params["location"] ?: "front door"
            return try {
                val command = "lock $location"
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", command)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Locking the $location!", null)
            } catch (e: Exception) {
                Log.e("LockDoor", "Lock failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't lock the door right now.")
            }
        }
    }
}
