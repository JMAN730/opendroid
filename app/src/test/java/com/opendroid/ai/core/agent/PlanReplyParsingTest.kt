package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PlanReplyParsingTest {

    @Test
    fun `a JSON null param is dropped, not turned into the text null`() {
        val plan = parsePlanFromLlmResponse(
            """{"action":"SEND_SMS","params":{"contact":"Mom","message":null}}""",
            userGoal = "text mom"
        )

        val params = plan.steps.single().params
        assertEquals("Mom", params["contact"])
        assertFalse(params.containsKey("message"))
    }

    @Test
    fun `a clarify reply never becomes an action step`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePlanFromLlmResponse(
                """{"type":"CLARIFY","action":"SEND_WHATSAPP","params":{"contact":"mom","message":"..."},
                   "needsClarification":true,"clarificationQuestion":"What should I say?"}""",
                userGoal = "message mom on whatsapp"
            )
        }
    }

    @Test
    fun `a plain action reply still becomes a single step`() {
        val plan = parsePlanFromLlmResponse(
            """{"type":"PLAN","action":"SEND_SMS","params":{"contact":"Mom","message":"On my way"}}""",
            userGoal = "text mom on my way"
        )

        val step = plan.steps.single()
        assertEquals("SEND_SMS", step.action)
        assertEquals(mapOf("contact" to "Mom", "message" to "On my way"), step.params)
    }
}
