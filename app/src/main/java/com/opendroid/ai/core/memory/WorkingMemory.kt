package com.opendroid.ai.core.memory

import android.content.Context
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Plan
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkingMemory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceStateProvider: DeviceStateProvider
) {
    private val _conversationHistory = mutableListOf<ChatMessage>()
    val conversationHistory: List<ChatMessage> get() = _conversationHistory

    var activePlan: Plan? = null
    var location: String = "Unknown"

    val batteryLevel: Int
        get() = deviceStateProvider.getBatteryLevel()

    val wifiState: String
        get() = deviceStateProvider.getWifiState()

    val connectivity: String
        get() = deviceStateProvider.getConnectivityState()

    val isInternetAvailable: Boolean
        get() = deviceStateProvider.isInternetAvailable()

    val locationContext: String
        get() {
            val loc = deviceStateProvider.getLocationContext()
            location = loc // keep location field in sync
            return loc
        }

    fun addMessage(msg: ChatMessage) {
        _conversationHistory.add(msg)
        if (_conversationHistory.size > 20) {
            _conversationHistory.removeAt(0)
        }
    }

    fun clear() {
        _conversationHistory.clear()
        activePlan = null
    }
}
