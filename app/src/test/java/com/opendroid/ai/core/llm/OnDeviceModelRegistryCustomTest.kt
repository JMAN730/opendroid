package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceModelRegistryCustomTest {

    @Test
    fun `custom ids are recognized`() {
        assertTrue(OnDeviceModelRegistry.isCustomId("custom-my-model-abc12345"))
        assertFalse(OnDeviceModelRegistry.isCustomId("qwen-2.5-0.5b-it-litert"))
        assertFalse(OnDeviceModelRegistry.isCustomId("custom"))
    }

    @Test
    fun `customSpec is local import only never gated or managed`() {
        val spec = OnDeviceModelRegistry.customSpec(
            id = "custom-phi-deadbeef",
            displayName = "phi.litertlm",
            modelFilename = "phi.litertlm",
            expectedSize = 500_000_000L
        )
        assertEquals(OnDeviceBackend.LITERT_LM, spec.backend)
        assertEquals("Custom", spec.family)
        assertFalse(spec.authRequired)
        assertFalse(spec.isManagedDownloadAvailable)
        assertEquals(OnDeviceModelRegistry.CUSTOM_DEFAULT_CONTEXT_WINDOW, spec.contextWindow)
    }

    @Test
    fun `public Qwen is recommended litert catalog entry and is not gated`() {
        val qwen = requireNotNull(OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert"))
        assertTrue(qwen.isRecommended)
        assertFalse(qwen.authRequired)
        assertTrue(qwen.isManagedDownloadAvailable)

        val recommended = requireNotNull(OnDeviceModelRegistry.recommendedFor(OnDeviceBackend.LITERT_LM))
        assertEquals("qwen-2.5-0.5b-it-litert", recommended.id)
    }
}
