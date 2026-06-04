package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object WhatsAppAutomator {

    suspend fun automateSend(message: String): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false
        
        // Wait for screen transition
        delay(1500)
        
        // Try to type the message (in case it wasn't pre-filled by the URI)
        val typed = service.findAndTypeById("com.whatsapp:id/entry", message)
        if (!typed) {
            service.findAndType("Type a message", message)
        }
        
        delay(800)
        
        // WhatsApp send button id is usually "com.whatsapp:id/send" or "com.whatsapp:id/send_button"
        val sendButtonIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/button_send"
        )
        
        for (id in sendButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d("WhatsAppAutomator", "Successfully clicked send button by ID: $id")
                return true
            }
        }
        
        // Fallback to clicking Send by text / content description
        val clicked = service.findAndClick("Send") || 
                      service.findAndClick("send") || 
                      service.findAndClick("SEND")
                      
        if (clicked) {
            Log.d("WhatsAppAutomator", "Successfully clicked send button by text label")
            return true
        }
        
        Log.w("WhatsAppAutomator", "Could not click send button automatically")
        return false
    }
}
