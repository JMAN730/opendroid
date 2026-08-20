package com.opendroid.ai.core.llm

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtCompatibilityTest {

    @Test
    fun `probes GPU first and falls back to CPU`() {
        val backends = LiteRtCompatibility.backendPreference.map { it()::class.simpleName }
        assertTrue("Expected GPU then CPU, got $backends", backends == listOf("GPU", "CPU"))
    }

    @Test
    fun `device and backend failures are classified as runtime incompatibility`() {
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Failed to create GPU delegate for this device")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IOException("wrapper", IllegalStateException("OpenCL runtime unavailable"))
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                RuntimeException("failed to create XNNPACK runtime")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Main section has no compatible subgraph")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                RuntimeException("Feature is not yet supported on this hardware")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                OutOfMemoryError("Failed to allocate memory for KV cache")
            )
        )
        assertTrue(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalStateException("Failed to compile compute shader on device driver")
            )
        )
    }

    @Test
    fun `malformed artifact failures are not classified as runtime incompatibility`() {
        assertFalse(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalArgumentException("Failed to parse model file: unexpected section header")
            )
        )
        assertFalse(
            LiteRtCompatibility.isBackendIncompatibility(
                IllegalArgumentException("Input is not valid flatbuffer model")
            )
        )
        assertFalse(LiteRtCompatibility.isBackendIncompatibility(IOException(null as String?)))
    }
}
