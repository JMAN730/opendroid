package com.opendroid.ai.actions

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToggleDndActionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `turning DND on uses the user's priority policy, never total silence`() = runBlocking {
        for (params in listOf(mapOf("state" to "on"), mapOf("state" to "toggle"), emptyMap())) {
            val controller = FakeDndController(NotificationManager.INTERRUPTION_FILTER_ALL)

            val result = actionFor(controller).execute(params, context)

            assertTrue("params=$params", result.success)
            assertEquals("params=$params", "Do Not Disturb is on.", result.data)
            assertEquals(
                "params=$params",
                listOf(NotificationManager.INTERRUPTION_FILTER_PRIORITY),
                controller.writes
            )
        }
    }

    @Test
    fun `turning DND off from priority restores all interruptions`() = runBlocking {
        val controller = FakeDndController(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        val result = actionFor(controller).execute(mapOf("state" to "off"), context)

        assertTrue(result.success)
        assertEquals("Do Not Disturb is off.", result.data)
        assertEquals(listOf(NotificationManager.INTERRUPTION_FILTER_ALL), controller.writes)
    }

    @Test
    fun `DND already on reports so without writing the filter`() = runBlocking {
        val controller = FakeDndController(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        val result = actionFor(controller).execute(mapOf("state" to "on"), context)

        assertTrue(result.success)
        assertEquals("Do Not Disturb is already on!", result.data)
        assertTrue(controller.writes.isEmpty())
    }

    @Test
    fun `reports failure when the filter did not change after the write`() = runBlocking {
        val controller = FakeDndController(NotificationManager.INTERRUPTION_FILTER_ALL, applyWrites = false)

        val result = actionFor(controller).execute(mapOf("state" to "on"), context)

        assertFalse(result.success)
        assertEquals("Do Not Disturb didn't turn on.", result.error)
    }

    @Test
    fun `waits for a write the system applies asynchronously before reporting`() = runBlocking {
        for ((state, start) in listOf(
            "on" to NotificationManager.INTERRUPTION_FILTER_ALL,
            "off" to NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )) {
            val controller = FakeDndController(start, staleReadsAfterWrite = 3)

            val result = actionFor(controller).execute(mapOf("state" to state), context)

            assertTrue("state=$state", result.success)
            assertEquals("state=$state", "Do Not Disturb is $state.", result.data)
        }
    }

    private fun actionFor(controller: FakeDndController) = ToggleDndAction(
        controllerProvider = { controller },
        launchIntent = { _, _ -> error("settings should not be opened") },
        settleDelay = {}
    )

    private class FakeDndController(
        var filter: Int,
        private val applyWrites: Boolean = true,
        private val staleReadsAfterWrite: Int = 0
    ) : DndController {
        val writes = mutableListOf<Int>()
        private var pending: Int? = null
        private var staleReadsLeft = 0

        override fun isPolicyAccessGranted(): Boolean = true
        override fun currentInterruptionFilter(): Int {
            val queued = pending
            if (queued != null) {
                if (staleReadsLeft > 0) {
                    staleReadsLeft--
                } else {
                    filter = queued
                    pending = null
                }
            }
            return filter
        }
        override fun setInterruptionFilter(filter: Int) {
            writes += filter
            if (!applyWrites) return
            if (staleReadsAfterWrite == 0) {
                this.filter = filter
            } else {
                pending = filter
                staleReadsLeft = staleReadsAfterWrite
            }
        }
    }
}
