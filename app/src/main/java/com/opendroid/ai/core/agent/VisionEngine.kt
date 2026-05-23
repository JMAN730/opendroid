package com.opendroid.ai.core.agent

import android.content.Context
import android.util.Log
import com.opendroid.ai.accessibility.OpenDroidAccessibilityService
import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.core.llm.ResponseFormat
import com.opendroid.ai.data.models.ChatMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vision engine that captures screenshots and analyzes them using a vision-capable LLM.
 * Uses the existing AccessibilityService's takeScreenshotAndEncode() for capture.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {
    companion object {
        private const val TAG = "VisionEngine"
    }

    /**
     * Capture the current screen as a base64-encoded JPEG string.
     * Uses the AccessibilityService's existing screenshot capability.
     */
    suspend fun captureScreenBase64(): String? {
        val service = OpenDroidAccessibilityService.getInstance()
        if (service == null) {
            Log.w(TAG, "Accessibility service not available for screenshot capture")
            return null
        }

        return try {
            service.takeScreenshotAndEncode()
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}")
            null
        }
    }

    /**
     * Capture the screen and analyze it with a vision-capable LLM.
     * Returns the analysis text.
     */
    suspend fun analyzeCurrentScreen(
        userQuestion: String = "What do you see on this screen?"
    ): String {
        val base64Image = captureScreenBase64()
            ?: return "Could not capture screen. Please enable Accessibility Service."

        val visionPrompt = """
            Analyze this Android screenshot. 
            User question: $userQuestion
            
            Describe:
            1. What app is open
            2. What content is visible
            3. Answer the user's specific question
            4. Any important information on screen
            
            Be concise and helpful.
        """.trimIndent()

        return try {
            val provider = llmProviderFactory.getActiveProvider()

            // Build a message with the image attached
            val imageMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                text = visionPrompt,
                sender = ChatMessage.Sender.USER,
                imageBase64 = base64Image
            )

            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a vision AI that analyzes Android screenshots. Be concise and accurate.",
                    messages = listOf(imageMessage),
                    temperature = 0.3f,
                    maxTokens = 500,
                    responseFormat = ResponseFormat.TEXT
                )
            )

            response.content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Vision analysis failed: ${e.message}")
            "I captured the screenshot but couldn't analyze it: ${e.message}"
        }
    }
}
