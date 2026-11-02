package com.opendroid.ai.ui.viewmodel

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.db.entities.TaskHistoryEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Rows logged before #174 can share one model-written planId across tasks from different
 * days. "Save as macro" on one of them must record only the run it belongs to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HistoryViewModelSaveMacroTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var database: OpenDroidDatabase
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = HistoryViewModel(database.taskHistoryDao(), database.unknownActionDao(), database.macroDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saving the day-1 task records only the day-1 run of a shared planId`() {
        runBlocking {
            log(1, "s1", "TOGGLE_WIFI", "{\"state\":\"on\"}", DAY_1)
            log(2, "s2", "SET_BRIGHTNESS", "{\"level\":\"40\"}", DAY_1 + 2_000)
            log(3, "s1", "SEND_SMS", "{\"phoneNumber\":\"+910000000000\",\"message\":\"I'm at the hospital\"}", DAY_3)
            log(4, "s1", "MAKE_CALL", "{\"phoneNumber\":\"+910000000001\"}", DAY_4)
        }

        val error = save(logId = 1)

        assertNull(error)
        val stepsJson = runBlocking { database.macroDao().getAllMacros() }.single().stepsJson
        assertTrue(stepsJson.contains("TOGGLE_WIFI"))
        assertTrue(stepsJson.contains("SET_BRIGHTNESS"))
        assertFalse(stepsJson.contains("SEND_SMS"))
        assertFalse(stepsJson.contains("MAKE_CALL"))
        assertFalse(stepsJson.contains("hospital"))
    }

    @Test
    fun `saving a later run records only that run`() {
        runBlocking {
            log(1, "s1", "TOGGLE_WIFI", "{}", DAY_1)
            log(2, "s1", "SEND_SMS", "{\"message\":\"hi\"}", DAY_3)
        }

        assertNull(save(logId = 2))

        val stepsJson = runBlocking { database.macroDao().getAllMacros() }.single().stepsJson
        assertTrue(stepsJson.contains("SEND_SMS"))
        assertFalse(stepsJson.contains("TOGGLE_WIFI"))
    }

    @Test
    fun `a failed step in another run does not block saving this one`() {
        runBlocking {
            log(1, "s1", "TOGGLE_WIFI", "{}", DAY_1)
            log(2, "s1", "MAKE_CALL", "{}", DAY_4, success = false)
        }

        assertNull(save(logId = 1))
        assertEquals(1, runBlocking { database.macroDao().getAllMacros() }.size)
    }

    private fun save(logId: Long): String? {
        var result: String? = "not called"
        var called = false
        viewModel.saveCompletedTaskAsMacro(PLAN_ID, logId, "Turn on Wi-Fi macro") {
            result = it
            called = true
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!called) {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Timed out waiting for the save", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
        return result
    }

    private suspend fun log(
        id: Long,
        stepId: String,
        action: String,
        params: String,
        timestamp: Long,
        success: Boolean = true
    ) {
        database.taskHistoryDao().insertHistory(
            TaskHistoryEntity(
                id = id,
                stepId = stepId,
                planId = PLAN_ID,
                description = action,
                actionType = action,
                paramsJson = params,
                success = success,
                resultData = null,
                errorMessage = null,
                timestamp = timestamp
            )
        )
    }

    private companion object {
        const val PLAN_ID = "generate-a-uuid"
        const val DAY_1 = 1_700_000_000_000L
        const val DAY_3 = DAY_1 + 2 * 86_400_000L
        const val DAY_4 = DAY_1 + 3 * 86_400_000L
    }
}
