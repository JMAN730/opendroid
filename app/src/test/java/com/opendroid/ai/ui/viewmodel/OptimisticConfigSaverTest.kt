package com.opendroid.ai.ui.viewmodel

import com.opendroid.ai.data.models.AutoMode
import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.repository.ProviderCredentialPersistenceState
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticConfigSaverTest {

    @Test
    fun `failed save puts the optimistic value back to the stored one`() = runBlocking {
        val ui = MutableStateFlow(LLMConfig(autoMode = AutoMode.OFF))
        val saver = OptimisticConfigSaver(
            uiConfig = ui,
            persist = { ProviderCredentialPersistenceState.StorageUnavailable },
            readStored = { LLMConfig(autoMode = AutoMode.YOLO, autoConfirmPlans = true) }
        )

        val saved = saver.save(
            revert = { stored -> copy(autoMode = stored.autoMode, autoConfirmPlans = stored.autoConfirmPlans) }
        ) { it.copy(autoMode = AutoMode.OFF) }

        assertFalse(saved)
        assertEquals(AutoMode.YOLO, ui.value.autoMode)
        assertTrue(ui.value.autoConfirmPlans)
    }

    @Test
    fun `failed save reverts only the fields of that edit`() = runBlocking {
        val ui = MutableStateFlow(LLMConfig(isDarkMode = false, ollamaUrl = "http://typing-in-progress"))
        val saver = OptimisticConfigSaver(
            uiConfig = ui,
            persist = { ProviderCredentialPersistenceState.CredentialsMustBeReentered },
            readStored = { LLMConfig(isDarkMode = true, ollamaUrl = "") }
        )

        saver.save(revert = { stored -> copy(isDarkMode = stored.isDarkMode) }) { it.copy(isDarkMode = false) }

        assertTrue(ui.value.isDarkMode)
        assertEquals("http://typing-in-progress", ui.value.ollamaUrl)
    }

    @Test
    fun `successful save keeps the optimistic value without reading the store`() = runBlocking {
        val ui = MutableStateFlow(LLMConfig(autoMode = AutoMode.OFF))
        var applied: LLMConfig? = null
        val saver = OptimisticConfigSaver(
            uiConfig = ui,
            persist = { update ->
                applied = update(LLMConfig(autoMode = AutoMode.YOLO))
                ProviderCredentialPersistenceState.Ready
            },
            readStored = { throw AssertionError("store must not be re-read after a successful save") }
        )

        val saved = saver.save(
            revert = { stored -> copy(autoMode = stored.autoMode) }
        ) { it.copy(autoMode = AutoMode.OFF) }

        assertTrue(saved)
        assertEquals(AutoMode.OFF, applied?.autoMode)
        assertEquals(AutoMode.OFF, ui.value.autoMode)
    }

    @Test
    fun `unreadable store after a failed save reports failure without throwing`() = runBlocking {
        val ui = MutableStateFlow(LLMConfig(autoMode = AutoMode.OFF))
        val saver = OptimisticConfigSaver(
            uiConfig = ui,
            persist = { ProviderCredentialPersistenceState.StorageUnavailable },
            readStored = { throw IOException("datastore unavailable") }
        )

        val saved = saver.save(
            revert = { stored -> copy(autoMode = stored.autoMode) }
        ) { it.copy(autoMode = AutoMode.OFF) }

        assertFalse(saved)
    }

    @Test
    fun `withEntryFrom restores a stored entry and drops one the store does not have`() {
        val shown = mapOf("OpenAI" to "unsaved", "Groq" to "unsaved", "Mistral" to "typing")
        val stored = mapOf("OpenAI" to "saved")

        assertEquals(
            mapOf("OpenAI" to "saved", "Groq" to "unsaved", "Mistral" to "typing"),
            shown.withEntryFrom(stored, "OpenAI")
        )
        assertEquals(
            mapOf("OpenAI" to "unsaved", "Mistral" to "typing"),
            shown.withEntryFrom(stored, "Groq")
        )
    }
}
