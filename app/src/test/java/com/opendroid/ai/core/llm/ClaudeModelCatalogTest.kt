package com.opendroid.ai.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeModelCatalog] — the single source of truth for the
 * Anthropic Claude models OpenDroid supports.
 *
 * Pure logic, no Android dependencies. Expectations are stated as literals so a
 * wrong or empty catalog fails rather than passing vacuously.
 */
class ClaudeModelCatalogTest {

    // ── Supported IDs resolve to themselves ─────────────────────────────

    @Test
    fun `each supported model id resolves to itself`() {
        assertEquals("claude-opus-5", ClaudeModelCatalog.resolve("claude-opus-5"))
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.resolve("claude-sonnet-5"))
        assertEquals("claude-haiku-4-5", ClaudeModelCatalog.resolve("claude-haiku-4-5"))
        assertEquals("claude-opus-4-8", ClaudeModelCatalog.resolve("claude-opus-4-8"))
        assertEquals("claude-sonnet-4-6", ClaudeModelCatalog.resolve("claude-sonnet-4-6"))
    }

    @Test
    fun `catalog lists exactly the supported models`() {
        assertEquals(
            listOf(
                "claude-opus-5",
                "claude-sonnet-5",
                "claude-haiku-4-5",
                "claude-opus-4-8",
                "claude-sonnet-4-6"
            ).sorted(),
            ClaudeModelCatalog.models.map { it.id }.sorted()
        )
    }

    // ── Legacy aliases migrate to their documented replacement ──────────

    @Test
    fun `unversioned family aliases resolve to the current family member`() {
        assertEquals("claude-opus-4-8", ClaudeModelCatalog.resolve("claude-opus-4"))
        assertEquals("claude-sonnet-4-6", ClaudeModelCatalog.resolve("claude-sonnet-4"))
        assertEquals("claude-haiku-4-5", ClaudeModelCatalog.resolve("claude-haiku-4"))
    }

    @Test
    fun `date-suffixed haiku id resolves to the canonical unversioned id`() {
        assertEquals("claude-haiku-4-5", ClaudeModelCatalog.resolve("claude-haiku-4-5-20251001"))
    }

    @Test
    fun `retired 3x model ids resolve to their replacements`() {
        assertEquals("claude-opus-4-8", ClaudeModelCatalog.resolve("claude-3-opus-20240229"))
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.resolve("claude-3-7-sonnet-20250219"))
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.resolve("claude-3-5-sonnet-20241022"))
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.resolve("claude-3-5-sonnet-20240620"))
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.resolve("claude-3-sonnet-20240229"))
        assertEquals("claude-haiku-4-5", ClaudeModelCatalog.resolve("claude-3-5-haiku-20241022"))
        assertEquals("claude-haiku-4-5", ClaudeModelCatalog.resolve("claude-3-haiku-20240307"))
    }

    @Test
    fun `every alias replacement is itself a supported model`() {
        val supported = ClaudeModelCatalog.models.map { it.id }.toSet()
        val aliases = listOf(
            "claude-opus-4",
            "claude-sonnet-4",
            "claude-haiku-4",
            "claude-haiku-4-5-20251001",
            "claude-3-opus-20240229",
            "claude-3-7-sonnet-20250219",
            "claude-3-5-sonnet-20241022",
            "claude-3-5-sonnet-20240620",
            "claude-3-sonnet-20240229",
            "claude-3-5-haiku-20241022",
            "claude-3-haiku-20240307"
        )
        aliases.forEach { alias ->
            val resolved = ClaudeModelCatalog.resolve(alias)
            assertNotNull("alias $alias should resolve", resolved)
            assertTrue("alias $alias resolved to unsupported $resolved", resolved in supported)
        }
    }

    // ── Models Anthropic serves but OpenDroid doesn't know yet ──────────

    @Test
    fun `an unknown but well-formed claude id resolves to itself`() {
        // A model Anthropic releases before OpenDroid ships a catalog update
        // must stay selectable rather than failing at request time.
        assertEquals("claude-opus-9", ClaudeModelCatalog.resolve("claude-opus-9"))
        assertEquals(
            "claude-sonnet-5-20260615",
            ClaudeModelCatalog.resolve("claude-sonnet-5-20260615")
        )
    }

    @Test
    fun `an unknown model is not decorated with catalog badges`() {
        assertNull(ClaudeModelCatalog.specFor("claude-opus-9"))
    }

    // ── Unsupported input ───────────────────────────────────────────────

    @Test
    fun `retired models with no replacement resolve to null`() {
        assertNull(ClaudeModelCatalog.resolve("claude-2.1"))
        assertNull(ClaudeModelCatalog.resolve("claude-2.0"))
        assertNull(ClaudeModelCatalog.resolve("claude-instant-1.2"))
    }

    @Test
    fun `ids from other providers resolve to null`() {
        assertNull(ClaudeModelCatalog.resolve("gpt-4o"))
        assertNull(ClaudeModelCatalog.resolve("gemini-2.0-flash"))
        assertNull(ClaudeModelCatalog.resolve("meta-llama/Llama-3-70b-chat-hf"))
    }

    @Test
    fun `garbage input resolves to null`() {
        assertNull(ClaudeModelCatalog.resolve(""))
        assertNull(ClaudeModelCatalog.resolve("   "))
        assertNull(ClaudeModelCatalog.resolve("'; DROP TABLE models; --"))
        assertNull(ClaudeModelCatalog.resolve("claude-opus-5 && rm -rf /"))
        assertNull(ClaudeModelCatalog.resolve("claude-"))
    }

    // ── Default and badges ──────────────────────────────────────────────

    @Test
    fun `default model is sonnet 5 and is supported`() {
        assertEquals("claude-sonnet-5", ClaudeModelCatalog.defaultModelId)
        assertTrue(ClaudeModelCatalog.models.any { it.id == ClaudeModelCatalog.defaultModelId })
    }

    @Test
    fun `exactly one model is recommended and it is the default`() {
        val recommended = ClaudeModelCatalog.models.filter { it.isRecommended }
        assertEquals(1, recommended.size)
        assertEquals(ClaudeModelCatalog.defaultModelId, recommended.single().id)
    }

    @Test
    fun `opus 5 is the premium model`() {
        val premium = ClaudeModelCatalog.models.filter { it.isPremium }
        assertEquals(1, premium.size)
        assertEquals("claude-opus-5", premium.single().id)
    }

    @Test
    fun `haiku 4 5 is flagged as the cheap option`() {
        val cheap = ClaudeModelCatalog.models.filter { it.isFree }
        assertEquals(1, cheap.size)
        assertEquals("claude-haiku-4-5", cheap.single().id)
    }

    @Test
    fun `every model has a non-blank display name`() {
        ClaudeModelCatalog.models.forEach { spec ->
            assertTrue("blank display name for ${spec.id}", spec.displayName.isNotBlank())
        }
    }

    @Test
    fun `display names use the marketing names`() {
        assertEquals("Claude Opus 5", ClaudeModelCatalog.specFor("claude-opus-5")?.displayName)
        assertEquals("Claude Sonnet 5", ClaudeModelCatalog.specFor("claude-sonnet-5")?.displayName)
        assertEquals("Claude Haiku 4.5", ClaudeModelCatalog.specFor("claude-haiku-4-5")?.displayName)
        assertEquals("Claude Opus 4.8", ClaudeModelCatalog.specFor("claude-opus-4-8")?.displayName)
        assertEquals("Claude Sonnet 4.6", ClaudeModelCatalog.specFor("claude-sonnet-4-6")?.displayName)
    }

    @Test
    fun `specFor returns null for an unknown id`() {
        assertNull(ClaudeModelCatalog.specFor("claude-2.1"))
    }

    // ── Request-shape facts ─────────────────────────────────────────────

    @Test
    fun `current models reject sampling parameters`() {
        assertFalse(ClaudeModelCatalog.acceptsSamplingParameters("claude-opus-5"))
        assertFalse(ClaudeModelCatalog.acceptsSamplingParameters("claude-sonnet-5"))
        assertFalse(ClaudeModelCatalog.acceptsSamplingParameters("claude-opus-4-8"))
    }

    @Test
    fun `older models still accept sampling parameters`() {
        assertTrue(ClaudeModelCatalog.acceptsSamplingParameters("claude-sonnet-4-6"))
        assertTrue(ClaudeModelCatalog.acceptsSamplingParameters("claude-haiku-4-5"))
    }

    @Test
    fun `unknown models are treated as rejecting sampling parameters`() {
        assertFalse(ClaudeModelCatalog.acceptsSamplingParameters("claude-future-9"))
    }
}
