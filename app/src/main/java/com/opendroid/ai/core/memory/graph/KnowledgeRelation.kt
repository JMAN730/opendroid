package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * Directed relationship connecting two nodes in the Personal Knowledge Graph.
 */
@Serializable
data class KnowledgeRelation(
    val sourceId: String,
    val targetId: String,
    val relationType: String,
    val weight: Float = 1.0f
)
