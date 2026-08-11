package com.opendroid.ai.core.llm

import com.opendroid.ai.data.models.LLMConfig
import com.opendroid.ai.data.models.effectiveContextWindow
import com.opendroid.ai.data.models.withOnDeviceContextWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceContextWindowTest {

    private val gemma = OnDeviceModelRegistry.findById("gemma-4-e2b-it-litert")!!

    @Test
    fun `clamp confines values to the supported range`() {
        assertEquals(OnDeviceContextWindow.MIN_TOKENS, OnDeviceContextWindow.clamp(0))
        assertEquals(OnDeviceContextWindow.MIN_TOKENS, OnDeviceContextWindow.clamp(-4096))
        assertEquals(OnDeviceContextWindow.MAX_TOKENS, OnDeviceContextWindow.clamp(1_000_000))
        assertEquals(8192, OnDeviceContextWindow.clamp(8192))
    }

    @Test
    fun `options include the model default and top out at 32K`() {
        val options = OnDeviceContextWindow.optionsFor(gemma)
        assertTrue(options.contains(gemma.contextWindow))
        assertEquals(options.sorted(), options)
        assertEquals(options.distinct(), options)
        assertEquals(OnDeviceContextWindow.MAX_TOKENS, options.last())
    }

    @Test
    fun `options for a 1280-token model keep that size selectable`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        assertTrue(OnDeviceContextWindow.optionsFor(qwen).contains(1280))
    }

    @Test
    fun `label renders whole-K sizes compactly`() {
        assertEquals("32K", OnDeviceContextWindow.label(32768))
        assertEquals("4K", OnDeviceContextWindow.label(4096))
        assertEquals("1280", OnDeviceContextWindow.label(1280))
    }

    @Test
    fun `effective window falls back to the catalog size when unset`() {
        assertEquals(gemma.contextWindow, LLMConfig().effectiveContextWindow(gemma))
    }

    @Test
    fun `effective window uses the user override`() {
        val config = LLMConfig().withOnDeviceContextWindow(gemma.id, 16384)
        assertEquals(16384, config.effectiveContextWindow(gemma))
    }

    @Test
    fun `override is clamped on write and on read`() {
        val config = LLMConfig().withOnDeviceContextWindow(gemma.id, 999_999)
        assertEquals(OnDeviceContextWindow.MAX_TOKENS, config.onDeviceContextWindows[gemma.id])

        val corrupt = LLMConfig(onDeviceContextWindows = mapOf(gemma.id to 999_999))
        assertEquals(OnDeviceContextWindow.MAX_TOKENS, corrupt.effectiveContextWindow(gemma))
    }

    @Test
    fun `null override clears the entry and restores the catalog size`() {
        val config = LLMConfig()
            .withOnDeviceContextWindow(gemma.id, 8192)
            .withOnDeviceContextWindow(gemma.id, null)
        assertTrue(config.onDeviceContextWindows.isEmpty())
        assertEquals(gemma.contextWindow, config.effectiveContextWindow(gemma))
    }

    @Test
    fun `override applies per model`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        val config = LLMConfig().withOnDeviceContextWindow(gemma.id, 32768)
        assertEquals(32768, config.effectiveContextWindow(gemma))
        assertEquals(qwen.contextWindow, config.effectiveContextWindow(qwen))
    }
}
