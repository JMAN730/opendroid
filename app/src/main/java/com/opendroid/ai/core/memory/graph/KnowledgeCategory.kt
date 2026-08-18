package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * Knowledge categories organized in the Personal Knowledge Graph.
 */
@Serializable
enum class KnowledgeCategory {
    CONTACT,          // Frequently contacted people, relationships, preferred channel
    TASK_ROUTINE,     // Recurring tasks, daily/weekly routines
    APP_PREFERENCE,   // Preferred apps (Spotify for music, Maps for navigation, etc.)
    SCHEDULE,         // Common schedules, typical meeting times, sleep/wake hours
    PROJECT,          // Active projects, goals, initiatives
    RESOURCE,         // Frequently visited websites, favorite links, locations
    NOTE_FACT,        // Notes, important dates (birthdays, anniversaries), facts
    USER_PREFERENCE   // Explicit preferences (speech style, dietary, privacy)
}
