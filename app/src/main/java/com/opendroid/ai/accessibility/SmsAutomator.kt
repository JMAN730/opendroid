package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object SmsAutomator {

    suspend fun automateSend(): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        
        // Wait for screen transition
        delay(1500)
        
        // Common SMS app send button IDs
        val sendButtonIds = listOf(
            "com.google.android.apps.messaging:id/send_message_button",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.samsung.android.messaging:id/send_button",
            "com.android.mms:id/send_button",
            "com.google.android.apps.messaging:id/send_button",
            "com.android.messaging:id/send_message_button"
        )
        
        for (id in sendButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d("SmsAutomator", "Successfully clicked SMS send button by ID: $id")
                return true
            }
        }
        
        // Try clicking by text/content description "Send", "SMS" or similar
        val clicked = service.findAndClick("Send") || 
                      service.findAndClick("send") || 
                      service.findAndClick("SEND") ||
                      service.findAndClick("SMS")
                      
        if (clicked) {
            Log.d("SmsAutomator", "Successfully clicked SMS send button by text label")
            return true
        }
        
        Log.w("SmsAutomator", "Could not click SMS send button automatically")
        return false
    }
}
