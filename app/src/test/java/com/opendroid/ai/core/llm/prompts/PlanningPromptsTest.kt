package com.opendroid.ai.core.llm.prompts

import com.opendroid.ai.core.agent.ActionSchema
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningPromptsTest {

    @Test
    fun `on-device prompt gives CREATE_CALENDAR_EVENT the date and time params its handler reads`() {
        val line = PlanningPrompts.buildOnDevicePlanningPrompt().lines()
            .single { it.contains("CREATE_CALENDAR_EVENT") }
        val promptParams = Regex("""(\w+):""").findAll(line).map { it.groupValues[1] }.toList()

        assertEquals(listOf("title", "date", "time"), promptParams)
        val schemaParams = ActionSchema.getAction("CREATE_CALENDAR_EVENT")!!.params.map { it.name }
        assertEquals(emptyList<String>(), promptParams - schemaParams.toSet())
    }
}
