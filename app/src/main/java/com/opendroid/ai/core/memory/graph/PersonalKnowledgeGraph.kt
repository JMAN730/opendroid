package com.opendroid.ai.core.memory.graph

import kotlinx.serialization.Serializable

/**
 * Complete snapshot representation of the Personal Knowledge Graph.
 */
@Serializable
data class PersonalKnowledgeGraph(
    val nodes: Map<String, KnowledgeNode> = emptyMap(),
    val relations: List<KnowledgeRelation> = emptyList()
) {
    fun addOrUpdateNode(node: KnowledgeNode): PersonalKnowledgeGraph {
        return copy(nodes = nodes + (node.id to node))
    }

    fun removeNode(id: String): PersonalKnowledgeGraph {
        return copy(
            nodes = nodes - id,
            relations = relations.filter { it.sourceId != id && it.targetId != id }
        )
    }

    fun addRelation(relation: KnowledgeRelation): PersonalKnowledgeGraph {
        val filtered = relations.filterNot {
            it.sourceId == relation.sourceId &&
            it.targetId == relation.targetId &&
            it.relationType == relation.relationType
        }
        return copy(relations = filtered + relation)
    }

    fun getNodesByTier(tier: MemoryTier): List<KnowledgeNode> =
        nodes.values.filter { it.tier == tier }

    fun getNodesByCategory(category: KnowledgeCategory): List<KnowledgeNode> =
        nodes.values.filter { it.category == category }

    fun findNodes(query: String): List<KnowledgeNode> {
        val lower = query.lowercase().trim()
        if (lower.isEmpty()) return nodes.values.toList()
        return nodes.values.filter {
            it.label.contains(lower, ignoreCase = true) ||
            it.summary.contains(lower, ignoreCase = true) ||
            it.properties.values.any { v -> v.contains(lower, ignoreCase = true) }
        }
    }
}
