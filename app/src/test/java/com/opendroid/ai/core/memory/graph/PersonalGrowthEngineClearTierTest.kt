package com.opendroid.ai.core.memory.graph

import android.content.Context
import android.content.ContextWrapper
import com.opendroid.ai.core.agent.DeviceStateProvider
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.routine.FakeMacroDao
import com.opendroid.ai.core.routine.FakeMemoryDao
import com.opendroid.ai.core.routine.FakeNotificationDao
import com.opendroid.ai.core.routine.FakeSensitiveMemoryStore
import com.opendroid.ai.core.routine.FakeTaskHistoryDao
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalGrowthEngineClearTierTest {

    private val context: Context = ContextWrapper(null)
    private val memoryDao = FakeMemoryDao()
    private val sensitiveStore = FakeSensitiveMemoryStore()
    private val memoryRepository = MemoryRepository(
        memoryDao = memoryDao,
        taskHistoryDao = FakeTaskHistoryDao(),
        macroDao = FakeMacroDao()
    )
    private val engine = PersonalGrowthEngine(
        memoryRepository = memoryRepository,
        sensitiveMemoryStore = sensitiveStore,
        workingMemory = WorkingMemory(context, DeviceStateProvider(context)),
        notificationIntelligence = NotificationIntelligence(FakeNotificationDao(), memoryRepository)
    )

    @Test
    fun `clearing the sensitive tier also removes the plaintext label nodes`() = runBlocking {
        engine.recordSensitiveSecret("bank_pin", "1234", "Bank PIN")
        engine.recordSensitiveSecret("door_code", "9876", "Door code")
        engine.recordExplicitMemory("Favourite colour", "Blue")

        engine.clearTier(MemoryTier.SENSITIVE)

        val snapshot = engine.getSnapshot()
        assertTrue(snapshot.getNodesByTier(MemoryTier.SENSITIVE).isEmpty())
        assertTrue(memoryDao.getMemoriesByKeyPrefix("pkg_node_sensitive_").isEmpty())
        assertTrue(sensitiveStore.listKeys().isEmpty())
        assertEquals(1, snapshot.getNodesByTier(MemoryTier.LONG_TERM).size)
    }
}
