package com.opendroid.ai.actions

import android.content.Context
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
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
    private val advancedControlActions: AdvancedControlActions
) {

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
        val action = actionsMap[actionName] ?: return ActionResult.UnknownAction(
            attemptedAction = actionName,
            availableActions = getAllRegisteredActions()
        )
        return action.execute(params, context)
    }
}
