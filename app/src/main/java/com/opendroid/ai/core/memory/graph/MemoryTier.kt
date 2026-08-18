package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * Tiered Memory Levels for Personal Growth Memory:
 * - Level 1 TEMPORARY: Volatile / current task context
 * - Level 2 LONG_TERM: Explicitly remembered facts, notes, projects, preferences
 * - Level 3 LEARNED_PATTERN: Inferred behaviors (frequently contacted people, preferred apps, routines)
 * - Level 4 SENSITIVE: Encrypted at rest via AndroidKeyStore AES-256-GCM with strict access controls
 */
@Serializable
enum class MemoryTier {
    TEMPORARY,
    LONG_TERM,
    LEARNED_PATTERN,
    SENSITIVE
}
