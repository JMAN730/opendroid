package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyProfileTest {
    @Test
    fun `first real observation has no invented budget`() {
        val observation = LatencyProfileRecorder.observe(
            previous = null,
            provider = ProviderCatalog.ON_DEVICE,
            model = "qwen-2.5-0.5b-it-litert",
            modelTier = "0.5B",
            referenceHardware = "Test Device (Android 35)",
            latencyMs = 812L,
            measuredAtMillis = 10L
        )

        assertEquals(LatencyClassification.UNMEASURED, observation.classification)
        assertEquals(812L, observation.profile.p50Ms)
        assertEquals(812L, observation.profile.p95Ms)
}
    @Test
    fun `profile classification uses only measured p95 observations`() {
        val baseline = LatencyProfile(
            provider = ProviderCatalog.ON_DEVICE,
            model = "qwen-2.5-0.5b-it-litert",
            modelTier = "0.5B",
            referenceHardware = "Pixel reference (Android 35)",
            samplesMs = listOf(100L, 120L, 140L, 200L),
            measuredAtMillis = 1L
        )

        assertEquals(LatencyClassification.WITHIN_MEASURED_PROFILE, baseline.classify(200L))
        assertEquals(LatencyClassification.EXCEEDS_MEASURED_PROFILE, baseline.classify(201L))
        assertNull(LatencyProfile("p", "m", "tier", "hw").p95Ms)
    }

    @Test
    fun `profile keeps bounded device observations and includes hardware in identity`() {
        val first = LatencyProfile(
            provider = ProviderCatalog.ON_DEVICE,
            model = "gemma-4-e2b-it-litert",
            modelTier = "2B",
            referenceHardware = "Pixel 9 (Android 35)",
            samplesMs = (1L..32L).toList()
        )
        val observation = LatencyProfileRecorder.observe(
            previous = first,
            provider = first.provider,
            model = first.model,
            modelTier = first.modelTier,
            referenceHardware = first.referenceHardware,
            latencyMs = 33L,
            measuredAtMillis = 2L
        )

        assertEquals(32, observation.profile.sampleCount)
        assertEquals(33L, observation.profile.samplesMs.last())
        assertEquals(
            "On-Device AI|gemma-4-e2b-it-litert|Pixel 9 (Android 35)",
            LatencyProfileRecorder.key(first.provider, first.model, first.referenceHardware)
        )
    }
}
