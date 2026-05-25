package com.opendroid.ai.actions

import android.content.Context
import android.util.Log
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.core.agent.DeviceStateProvider
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
    private val unknownActionDao: UnknownActionDao,
    private val deviceStateProvider: DeviceStateProvider
) {

    companion object {
        private const val TAG = "ActionDispatcher"

        // Actions that require internet connectivity
        private val internetRequiredActions = setOf(
            "WEB_SEARCH",
            "GET_WEATHER",
            "GET_NEWS",
            "OPEN_BROWSER",
            "BOOK_UBER",
            "BOOK_OLA",
            "GET_DIRECTIONS",
            "CURRENCY_CONVERT",
            "TRANSLATE",
            "PLAY_YOUTUBE",
            "CHECK_STOCK",
            "SUMMARIZE_URL",
            "FACT_CHECK"
        )
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

        // ── LAYER 0: Internet pre-check for web-dependent actions ──
        if (internetRequiredActions.contains(actionName)) {
            if (!deviceStateProvider.isInternetAvailable()) {
                Log.d(TAG, "Blocking $actionName — no internet")
                return ActionResult.Failure(
                    errorMsg = "No internet connection available",
                    fallback = "Connect to WiFi or mobile data to use $actionName"
                )
            }
        }

        // ── LAYER 1: Validate against ActionSchema ──
        val (schemaValidation, enrichedParams) = ActionSchema.validateParams(actionName, params)

        when (schemaValidation) {
            is ActionSchema.ValidationResult.Valid -> {
                // Schema says action is valid — execute with enriched params (defaults applied)
                val handler = actionsMap[actionName]
                if (handler != null) {
                    // Convert enrichedParams back to Map<String, String> for Action.execute
                    val stringParams = enrichedParams.mapValues { it.value.toString() }
                    return try {
                        handler.execute(stringParams, context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Execution failed for $actionName: ${e.message}")
                        ActionResult.Failure(
                            errorMsg = e.message ?: "Execution failed",
                            fallback = "Try alternative approach"
                        )
                    }
                }
                // Schema valid but no handler registered — should not happen normally
                Log.w(TAG, "Schema-valid action $actionName has no handler, falling through to mapper")
            }

            is ActionSchema.ValidationResult.MissingParams -> {
                // Check if ALL missing params have defaults — if so, apply and execute
                val definition = ActionSchema.getAction(actionName)
                val allHaveDefaults = schemaValidation.params.all { paramName ->
                    definition?.params?.find { it.name == paramName }?.defaultValue != null
                }

                if (allHaveDefaults) {
                    // Apply all defaults and execute — never ask user
                    val withDefaults = ActionSchema.applyDefaults(actionName, params)
                    val handler = actionsMap[actionName]
                    if (handler != null) {
                        val stringParams = withDefaults.mapValues { it.value.toString() }
                        return try {
                            handler.execute(stringParams, context)
                        } catch (e: Exception) {
                            Log.e(TAG, "Execution with defaults failed for $actionName: ${e.message}")
                            ActionResult.Failure(
                                errorMsg = e.message ?: "Execution failed",
                                fallback = "Try alternative approach"
                            )
                        }
                    }
                }

                // Only ask user if param truly has no default
                val firstMissing = schemaValidation.params.first()
                val paramDef = definition?.params?.find { it.name == firstMissing }
                Log.d(TAG, "Missing required params for $actionName: ${schemaValidation.params}")
                return ActionResult.NeedsInput(
                    question = "I need the $firstMissing to complete this. ${paramDef?.description ?: ""}",
                    options = paramDef?.enumValues ?: emptyList()
                )
            }

            is ActionSchema.ValidationResult.InvalidAction -> {
                // Action not in schema — try auto-mapper as fallback
                Log.d(TAG, "Action $actionName not in schema, trying auto-mapper")
            }
        }

        // ── LAYER 2: Auto-map unknown actions (fallback for hallucinations) ──
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
                availableActions = ActionSchema.getAllActionNames()
            )
        }

        val finalAction = mapping.mappedAction!!
        val finalParams = mapping.mappedParams

        // Log if mapping occurred
        if (mapping.wasMapped) {
            Log.d(TAG, "Auto-mapped: $actionName → $finalAction")
            logUnknownAction(actionName, "AUTO_FIXED", wasAutoFixed = true, fixedWith = finalAction)
        }

        // ── Execute the mapped action ──
        val handler = actionsMap[finalAction]
        if (handler == null) {
            Log.e(TAG, "Mapped action $finalAction is also not registered!")
            logUnknownAction(actionName, "FAILED", wasAutoFixed = false, fixedWith = finalAction)
            return ActionResult.UnknownAction(
                attemptedAction = finalAction,
                availableActions = ActionSchema.getAllActionNames()
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
