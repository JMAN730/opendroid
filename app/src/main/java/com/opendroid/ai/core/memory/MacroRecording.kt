package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.PlanStep
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Converts a fully successful task-history group into stable macro step JSON. */
object MacroRecording {
    private val json = Json {
        encodeDefaults = false
        explicitNulls = false
        prettyPrint = false
    }

    fun stepsFromCompletedTask(history: List<TaskHistoryEntity>): List<PlanStep>? {
        if (history.isEmpty() || history.any { !it.success }) return null

        val ordered = history.sortedWith(compareBy<TaskHistoryEntity> { it.timestamp }.thenBy { it.id })
        return ordered.mapIndexed { index, entry ->
            val params = try {
                json.decodeFromString<Map<String, String>>(entry.paramsJson)
            } catch (_: Exception) {
                return null
            }

            PlanStep(
                stepId = "recorded-step-${index + 1}",
                order = index + 1,
                description = ExecutionHistoryPrivacy.sanitizeDescription(
                    entry.actionType,
                    entry.description
                ).ifBlank { entry.actionType },
                action = entry.actionType.trim(),
                params = ExecutionHistoryPrivacy.sanitizeParams(entry.actionType, params)
            )
        }.takeIf { steps ->
            steps.isNotEmpty() && steps.all { it.action.isNotBlank() }
        }
    }

    fun serializeCompletedTask(history: List<TaskHistoryEntity>): String? =
        stepsFromCompletedTask(history)?.let(json::encodeToString)
}
