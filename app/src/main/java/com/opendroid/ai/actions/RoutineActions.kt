package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.routine.HabitRoutineEngine
import com.opendroid.ai.data.db.dao.HabitDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutineActions @Inject constructor(
    private val habitRoutineEngine: HabitRoutineEngine,
    private val habitDao: HabitDao
) {
    companion object {
        private const val TAG = "RoutineActions"
    }

    fun getActions(): List<Action> = listOf(
        RunRoutineAction(habitRoutineEngine, habitDao),
        GetMorningBriefingAction(habitRoutineEngine),
        DetectRoutinesAction(habitRoutineEngine),
        ApproveRoutineAction(habitRoutineEngine, habitDao)
    )

    private class RunRoutineAction(
        private val habitRoutineEngine: HabitRoutineEngine,
        private val habitDao: HabitDao
    ) : Action {
        override val name: String = "RUN_ROUTINE"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val routineId = params["routineId"]
            val routineName = params["routineName"] ?: params["name"]

            val targetId = if (!routineId.isNullOrBlank()) {
                routineId
            } else if (!routineName.isNullOrBlank()) {
                val all = habitDao.getAllRoutines()
                val match = all.firstOrNull { it.name.equals(routineName, ignoreCase = true) }
                    ?: all.firstOrNull { it.name.contains(routineName, ignoreCase = true) }
                match?.id ?: return ActionResult.Failure("Routine '$routineName' not found.")
            } else {
                return ActionResult.Failure("routineId or routineName parameter missing")
            }

            return try {
                habitRoutineEngine.executeRoutine(targetId, context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute routine $targetId: ${e.message}", e)
                ActionResult.Failure("Failed to run routine: ${e.localizedMessage}")
            }
        }
    }

    private class GetMorningBriefingAction(
        private val habitRoutineEngine: HabitRoutineEngine
    ) : Action {
        override val name: String = "GET_MORNING_BRIEFING"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val section = params["section"] ?: "full"
            return try {
                val briefing = habitRoutineEngine.generateMorningBriefing(section)
                ActionResult.Success(mapOf("message" to briefing))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate morning briefing: ${e.message}", e)
                ActionResult.Failure("Could not generate morning briefing.")
            }
        }
    }

    private class DetectRoutinesAction(
        private val habitRoutineEngine: HabitRoutineEngine
    ) : Action {
        override val name: String = "DETECT_ROUTINES"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val lookbackDays = params["lookbackDays"]?.toIntOrNull() ?: 14
            return try {
                val routines = habitRoutineEngine.detectRoutines(lookbackDays = lookbackDays)
                if (routines.isEmpty()) {
                    ActionResult.Success(mapOf("message" to "No new recurring routines detected yet. Continue using your device normally to build habit patterns."))
                } else {
                    val summary = routines.joinToString("\n") { "• ${it.name} (${it.triggerLabel}): ${it.description}" }
                    ActionResult.Success(mapOf("message" to "Detected ${routines.size} routines:\n$summary"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection failed: ${e.message}", e)
                ActionResult.Failure("Failed to detect routines.")
            }
        }
    }

    private class ApproveRoutineAction(
        private val habitRoutineEngine: HabitRoutineEngine,
        private val habitDao: HabitDao
    ) : Action {
        override val name: String = "APPROVE_ROUTINE"

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val routineId = params["routineId"]
            val routineName = params["routineName"]

            val targetId = if (!routineId.isNullOrBlank()) {
                routineId
            } else if (!routineName.isNullOrBlank()) {
                val all = habitDao.getAllRoutines()
                val match = all.firstOrNull { it.name.equals(routineName, ignoreCase = true) }
                match?.id ?: return ActionResult.Failure("Routine '$routineName' not found.")
            } else {
                return ActionResult.Failure("routineId or routineName parameter missing")
            }

            return try {
                val macro = habitRoutineEngine.approveRoutine(targetId)
                if (macro != null) {
                    ActionResult.Success(mapOf("message" to "Routine '${macro.name}' has been approved and automated!"))
                } else {
                    ActionResult.Failure("Could not activate routine.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Approval failed: ${e.message}", e)
                ActionResult.Failure("Failed to approve routine.")
            }
        }
    }
}
