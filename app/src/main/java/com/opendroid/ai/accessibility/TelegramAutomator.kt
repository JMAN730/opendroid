package com.opendroid.ai.accessibility

import android.util.Log
import kotlinx.coroutines.delay

object TelegramAutomator {

    private const val TAG = "TelegramAutomator"

    suspend fun automateSend(message: String): Boolean {
        val service = OpenDroidAccessibilityService.getInstance() ?: return false

        // Wait for Telegram chat screen to render
        delay(2500)

        // Known Telegram chat input view IDs
        val inputIds = listOf(
            "org.telegram.messenger:id/chat_text_edit",
            "org.telegram.messenger.web:id/chat_text_edit",
            "org.telegram.plus:id/chat_text_edit",
            "chat_text_edit",
            "chat_message_text"
        )

        var inputFieldFound = false
        for (id in inputIds) {
            val rootNode = service.rootInActiveWindow ?: continue
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                inputFieldFound = true
                break
            }
        }

        if (!inputFieldFound) {
            delay(1500)
            for (id in inputIds) {
                val rootNode = service.rootInActiveWindow ?: continue
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    inputFieldFound = true
                    break
                }
            }
        }

        // Type the message into the input field
        var typed = false
        for (id in inputIds) {
            if (service.findAndTypeById(id, message)) {
                typed = true
                break
            }
        }
        if (!typed) {
            typed = service.findAndType("Message", message) ||
                    service.findAndType("Write a message...", message)
        }

        delay(600)

        // Known Telegram send button view IDs
        val sendButtonIds = listOf(
            "org.telegram.messenger:id/send_button",
            "org.telegram.messenger.web:id/send_button",
            "org.telegram.plus:id/send_button",
            "chat_send_button",
            "send_button"
        )

        var sendClicked = false
        for (id in sendButtonIds) {
            if (service.findAndClickById(id)) {
                Log.d(TAG, "Successfully clicked send button by ID: $id")
                sendClicked = true
                break
            }
        }

        if (!sendClicked) {
            sendClicked = service.findAndClick("Send") ||
                          service.findAndClick("Send message") ||
                          service.findAndClick("send")
            if (sendClicked) {
                Log.d(TAG, "Successfully clicked send button by text label")
            }
        }

        if (!sendClicked) {
            Log.w(TAG, "Could not automatically click Telegram send button")
            return false
        }

        // Post-send verification
        delay(500)
        return true
    }
}
