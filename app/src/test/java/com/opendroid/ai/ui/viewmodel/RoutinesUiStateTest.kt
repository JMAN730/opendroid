package com.opendroid.ai.ui.viewmodel

import com.opendroid.ai.data.models.HabitRoutine
import com.opendroid.ai.data.models.RoutineStatus
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutinesUiStateTest {

    @Test
    fun `approving a routine never shows it in both sections`() = runBlocking {
        // What the all-routines query emits before and after approveRoutine.
        val states = flowOf(
            listOf(routine("r1", RoutineStatus.SUGGESTED)),
            listOf(routine("r1", RoutineStatus.APPROVED))
        ).map(::partitionRoutines).toList()

        states.forEach { state ->
            val shared = state.suggested.map { it.id }.intersect(state.active.map { it.id }.toSet())
            assertTrue("routine in both sections: $shared", shared.isEmpty())
        }
        assertEquals(listOf("r1"), states.first().suggested.map { it.id })
        assertEquals(listOf("r1"), states.last().active.map { it.id })
    }

    @Test
    fun `sections keep the statuses and order of the old queries`() {
        val state = partitionRoutines(
            listOf(
                routine("old-active", RoutineStatus.ACTIVE, lastDetectedAt = 100),
                routine("paused", RoutineStatus.PAUSED, lastDetectedAt = 300),
                routine("dismissed", RoutineStatus.DISMISSED, lastDetectedAt = 400),
                routine("weak", RoutineStatus.SUGGESTED, confidence = 0.4f, lastDetectedAt = 500),
                routine("strong-old", RoutineStatus.SUGGESTED, confidence = 0.9f, lastDetectedAt = 50),
                routine("strong-new", RoutineStatus.SUGGESTED, confidence = 0.9f, lastDetectedAt = 60),
                routine("approved", RoutineStatus.APPROVED, lastDetectedAt = 200)
            )
        )

        assertEquals(listOf("strong-new", "strong-old", "weak"), state.suggested.map { it.id })
        assertEquals(listOf("paused", "approved", "old-active"), state.active.map { it.id })
    }

    private fun routine(
        id: String,
        status: RoutineStatus,
        confidence: Float = 0.5f,
        lastDetectedAt: Long = 0L
    ) = HabitRoutine(
        id = id,
        name = id,
        description = "",
        triggerLabel = "",
        confidence = confidence,
        status = status,
        lastDetectedAt = lastDetectedAt
    )
}
