package com.opendroid.ai.data.models

import kotlinx.serialization.Serializable

enum class HabitEventType {
    APP_OPEN,
    AGENT_ACTION,
    URL_OPEN,
    SYSTEM_EVENT
}

@Serializable
data class HabitEvent(
    val id: String,
    val eventType: HabitEventType,
    val packageName: String,
    val actionName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val dayOfWeek: Int,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val metadata: Map<String, String> = emptyMap()
)
