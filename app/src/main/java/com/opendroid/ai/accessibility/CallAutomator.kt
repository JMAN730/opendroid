package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object CallAutomator {

    suspend fun automateCall(): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        
        // Wait for screen transition
        delay(1500)
        
        val dialButtonIds = listOf(
            "com.google.android.dialer:id/dialpad_floating_action_button",
            "com.samsung.android.dialer:id/dialButton",
            "com.android.dialer:id/dialpad_floating_action_button",
            "com.android.contacts:id/dialpad_floating_action_button",
            "com.android.phone:id/dialpad_floating_action_button",
            "com.android.dialer:id/dialButton"
        )
        
        for (id in dialButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d("CallAutomator", "Successfully clicked Dialer call button by ID: $id")
                return true
            }
        }
        
        val clicked = service.findAndClick("Call") || 
                      service.findAndClick("call") || 
                      service.findAndClick("CALL") ||
                      service.findAndClick("Dial") ||
                      service.findAndClick("dial")
                      
        if (clicked) {
            Log.d("CallAutomator", "Successfully clicked Dialer call button by text label")
            return true
        }
        
        Log.w("CallAutomator", "Could not click Dialer call button automatically")
        return false
    }
}
