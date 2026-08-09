package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSequenceExecutor
import com.opendroid.ai.data.db.dao.MacroDao
import com.opendroid.ai.data.db.entities.MacroEntity
import com.opendroid.ai.data.models.PlanStep
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MacroActions @Inject constructor(
    private val macroDao: MacroDao,
    private val actionSequenceExecutor: ActionSequenceExecutor
) {

    fun getActions(): List<Action> = listOf(
        RunMacroAction(macroDao, actionSequenceExecutor),
        CreateMacroAction(macroDao),
        ScheduleMacroAction(macroDao)
    )

    private class RunMacroAction(
        private val macroDao: MacroDao,
        private val actionSequenceExecutor: ActionSequenceExecutor
    ) : Action {
        override val name: String = "RUN_MACRO"
        private val json = Json { ignoreUnknownKeys = true }

        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val macroName = params["macroName"] ?: return ActionResult(false, null, "macroName parameter missing")
            return try {
                val macro = macroDao.getMacroByName(macroName)
                    ?: return ActionResult(false, null, "Macro with name '$macroName' not found.")
                if (macro.stepsJson.isBlank()) {
                    return ActionResult(false, null, "Macro '$macroName' has no step data.")
                }
                val steps = try {
                    json.decodeFromString<List<PlanStep>>(macro.stepsJson)
                } catch (e: SerializationException) {
                    Log.e("RunMacro", "Invalid steps for '$macroName': ${e.localizedMessage}")
                    return ActionResult(false, null, "Macro '$macroName' has invalid step data.")
                }
                actionSequenceExecutor.execute(steps, context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("RunMacro", "Macro failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't run macro '$macroName' right now.")
            }
        }
    }

    private class CreateMacroAction(private val macroDao: MacroDao) : Action {
        override val name: String = "CREATE_MACRO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val name = params["name"] ?: return ActionResult(false, null, "name parameter missing")
            val steps = params["steps"] ?: return ActionResult(false, null, "steps parameter missing")
            return try {
                val entity = MacroEntity(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    trigger = "manual",
                    stepsJson = steps,
                    isSystem = false,
                    isEnabled = true
                )
                macroDao.insertMacro(entity)
                ActionResult(true, "Macro '$name' is ready to go!", null)
            } catch (e: Exception) {
                Log.e("CreateMacro", "Create failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't create that macro.")
            }
        }
    }

    private class ScheduleMacroAction(private val macroDao: MacroDao) : Action {
        override val name: String = "SCHEDULE_MACRO"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val macroName = params["macroName"] ?: return ActionResult(false, null, "macroName parameter missing")
            val cronExpression = params["cronExpression"] ?: return ActionResult(false, null, "cronExpression parameter missing")
            return try {
                val macro = macroDao.getMacroByName(macroName)
                if (macro != null) {
                    val updated = macro.copy(trigger = "cron:$cronExpression")
                    macroDao.insertMacro(updated)
                    ActionResult(true, "Macro '$macroName' is scheduled!", null)
                } else {
                    // Try creating a new empty macro with schedule
                    val entity = MacroEntity(
                        id = UUID.randomUUID().toString(),
                        name = macroName,
                        trigger = "cron:$cronExpression",
                        stepsJson = "[]",
                        isSystem = false,
                        isEnabled = true
                    )
                    macroDao.insertMacro(entity)
                    ActionResult(true, "Created and scheduled macro '$macroName'!", null, true)
                }
            } catch (e: Exception) {
                Log.e("ScheduleMacro", "Schedule failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't schedule that macro.")
            }
        }
    }
}
