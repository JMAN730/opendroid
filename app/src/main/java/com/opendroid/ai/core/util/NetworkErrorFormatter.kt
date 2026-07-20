package com.opendroid.ai.core.util

import android.util.Log

object NetworkErrorFormatter {

    private const val TAG = "NetworkErrorFormatter"

    fun toUserMessage(error: Throwable?): String {
        val message = error?.localizedMessage ?: error?.message ?: return "Something went wrong. Please try again."
        return toUserMessage(message)
    }

    fun toUserMessage(message: String): String {
        val lower = message.lowercase()
        return when {
            lower.contains("unable to resolve host") ||
                lower.contains("no address associated with hostname") ||
                lower.contains("network is unreachable") ||
                lower.contains("failed to connect") && lower.contains("enetunreach") ->
                "No internet connection. Check your network and try again."

            lower.contains("timeout") || lower.contains("timed out") ->
                "The request timed out. Check your connection and try again."

            lower.contains("10.0.2.2") || lower.contains("connection refused") ->
                "Can't reach the configured server. Check your provider settings."

            else -> {
                Log.e(TAG, "Unhandled network/provider error: $message")
                "Something went wrong. Please try again."
            }
        }
    }
}
