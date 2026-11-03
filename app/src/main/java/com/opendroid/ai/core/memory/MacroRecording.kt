package com.opendroid.ai.core.memory

import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import com.opendroid.ai.data.models.Plan
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

    /**
     * A longer pause between two rows of one planId means they came from separate
     * requests, not one run.
     */
    private const val RUN_GAP_MS = 10 * 60 * 1000L

    /**
     * Splits [history] into runs: one execution of one plan each. An app-minted planId
     * is one run however long it paused, and may log a step twice when a failed step is
     * repaired or retried. Before #174 the model wrote the planId, and tasks from
     * different days could share one, so for those ids a run also ends where a stepId
     * repeats (a new request starts again at s1), unless that step failed in this run,
     * or after a gap of more than [RUN_GAP_MS].
     */
    fun runs(history: List<TaskHistoryEntity>): List<List<TaskHistoryEntity>> =
        history.groupBy { it.planId }.flatMap { (planId, entries) ->
            val ordered = entries.sortedWith(compareBy<TaskHistoryEntity> { it.timestamp }.thenBy { it.id })
            if (Plan.isAppMinted(planId)) return@flatMap listOf(ordered)
            val runs = mutableListOf<MutableList<TaskHistoryEntity>>()
            ordered.forEach { entry ->
                val current = runs.lastOrNull()
                val startsNewRun = current == null ||
                    (current.any { it.stepId == entry.stepId } && current.none { it.stepId == entry.stepId && !it.success }) ||
                    entry.timestamp - current.last().timestamp > RUN_GAP_MS
                if (startsNewRun) runs.add(mutableListOf(entry)) else current.add(entry)
            }
            runs
        }

    /** The run in [history] that holds the row [logId], or empty if none does. */
    fun runContaining(history: List<TaskHistoryEntity>, logId: Long): List<TaskHistoryEntity> =
        runs(history).firstOrNull { run -> run.any { it.id == logId } }.orEmpty()

    /** Runs a user may save as a macro: real plans whose every step succeeded. */
    fun saveableRuns(history: List<TaskHistoryEntity>): List<List<TaskHistoryEntity>> =
        runs(history).filter { run ->
            val planId = run.first().planId
            planId.isNotBlank() && planId != "n/a" && run.all { it.success }
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
