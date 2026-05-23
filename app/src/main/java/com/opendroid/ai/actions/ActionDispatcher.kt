package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.data.db.dao.UnknownActionDao
import com.opendroid.ai.data.db.entities.UnknownActionEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActionDispatcher @Inject constructor(
    private val systemActions: SystemActions,
    private val communicationActions: CommunicationActions,
    private val calendarActions: CalendarActions,
    private val transportActions: TransportActions,
    private val informationActions: InformationActions,
    private val mediaActions: MediaActions,
    private val foodShoppingActions: FoodShoppingActions,
    private val smartHomeActions: SmartHomeActions,
    private val financeActions: FinanceActions,
    private val macroActions: MacroActions,
    private val advancedControlActions: AdvancedControlActions,
    private val autoMapper: ActionAutoMapper,
    private val unknownActionDao: UnknownActionDao
) {

    companion object {
        private const val TAG = "ActionDispatcher"
    }

    private val actionsMap: Map<String, Action> = buildMap {
        putAll(systemActions.getActions().associateBy { it.name })
        putAll(communicationActions.getActions().associateBy { it.name })
        putAll(calendarActions.getActions().associateBy { it.name })
        putAll(transportActions.getActions().associateBy { it.name })
        putAll(informationActions.getActions().associateBy { it.name })
        putAll(mediaActions.getActions().associateBy { it.name })
        putAll(foodShoppingActions.getActions().associateBy { it.name })
        putAll(smartHomeActions.getActions().associateBy { it.name })
        putAll(financeActions.getActions().associateBy { it.name })
        putAll(macroActions.getActions().associateBy { it.name })
        putAll(advancedControlActions.getActions().associateBy { it.name })
    }

    fun hasAction(actionName: String): Boolean = actionsMap.containsKey(actionName)

    fun isRegistered(actionName: String): Boolean = hasAction(actionName)

    fun getAllRegisteredActions(): List<String> = actionsMap.keys.toList()

    fun getActionCount(): Int = actionsMap.size

    suspend fun execute(actionName: String, params: Map<String, String>, context: Context): ActionResult {

        // ── LAYER 2: Auto-map unknown actions before dispatch ──
        val mapping = autoMapper.mapAction(
            action = actionName,
            params = params,
            registeredActions = actionsMap.keys
        )

        // If action should be skipped (security/privacy hallucination)
        if (mapping.mappedAction == null && mapping.wasMapped) {
            Log.d(TAG, "Skipping hallucinated step: $actionName (mapped to SKIP)")
            logUnknownAction(actionName, "AUTO_FIXED", wasAutoFixed = true, fixedWith = "SKIP")
            return ActionResult.Success(
                dataMap = mapOf(
                    "message" to "Step skipped (unnecessary)",
                    "skipped" to "true"
                )
            )
        }

        // If truly unknown after mapping — no mapping found
        if (mapping.mappedAction == null && !mapping.wasMapped) {
            Log.e(TAG, "Unknown action after mapping: $actionName")
            logUnknownAction(actionName, "FAILED", wasAutoFixed = false, fixedWith = null)
            return ActionResult.UnknownAction(
                attemptedAction = actionName,
                availableActions = getAllRegisteredActions()
            )
        }

        val finalAction = mapping.mappedAction!!
        val finalParams = mapping.mappedParams

        // Log if mapping occurred
        if (mapping.wasMapped) {
            Log.d(TAG, "Auto-mapped: $actionName → $finalAction")
            logUnknownAction(actionName, "AUTO_FIXED", wasAutoFixed = true, fixedWith = finalAction)
        }

        // ── Execute the (possibly mapped) action ──
        val handler = actionsMap[finalAction]
        if (handler == null) {
            // Mapper pointed to an action that isn't registered — shouldn't happen but safe
            Log.e(TAG, "Mapped action $finalAction is also not registered!")
            logUnknownAction(actionName, "FAILED", wasAutoFixed = false, fixedWith = finalAction)
            return ActionResult.UnknownAction(
                attemptedAction = finalAction,
                availableActions = getAllRegisteredActions()
            )
        }

        return try {
            handler.execute(finalParams, context)
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed for $finalAction: ${e.message}")
            ActionResult.Failure(
                errorMsg = e.message ?: "Execution failed",
                fallback = "Try alternative approach"
            )
        }
    }

    private suspend fun logUnknownAction(
        attemptedAction: String,
        fixStatus: String,
        wasAutoFixed: Boolean,
        fixedWith: String?
    ) {
        try {
            unknownActionDao.insertUnknownAction(
                UnknownActionEntity(
                    attemptedAction = attemptedAction,
                    goal = "",
                    timestamp = System.currentTimeMillis(),
                    fixStatus = fixStatus,
                    wasAutoFixed = wasAutoFixed,
                    fixedWith = fixedWith
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log unknown action: ${e.message}")
        }
    }
}
