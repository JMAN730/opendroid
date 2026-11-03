package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanContinuationTest {

    @Test
    fun `the three documented decisions keep their meaning in any case`() {
        assertEquals(PlanContinuation.Continue, PlanContinuation.afterReEvaluation("CONTINUE"))
        assertEquals(PlanContinuation.Continue, PlanContinuation.afterReEvaluation(" continue "))
        assertEquals(PlanContinuation.Modify, PlanContinuation.afterReEvaluation("modify"))
        assertEquals(PlanContinuation.Abandon, PlanContinuation.afterReEvaluation("ABANDON"))
    }

    @Test
    fun `any other decision stops the plan rather than running the next step`() {
        listOf("STOP", "ABORT", "CONTINUE | MODIFY | ABANDON", "", "PROCEED").forEach { decision ->
            assertEquals(decision, PlanContinuation.Abandon, PlanContinuation.afterReEvaluation(decision))
        }
    }
}
