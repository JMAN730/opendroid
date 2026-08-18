package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * An individual entity node inside the Personal Knowledge Graph.
 */
@Serializable
data class KnowledgeNode(
    val id: String,
    val label: String,
    val category: KnowledgeCategory,
    val tier: MemoryTier,
    val summary: String,
    val properties: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f,
    val accessCount: Int = 1,
    val lastUpdated: Long = System.currentTimeMillis(),
    val ttlHours: Int = -1
)
