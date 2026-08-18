package com.opendroid.ai.core.memory.graph

import org.junit.Assert.*
import org.junit.Test

class PersonalKnowledgeGraphTest {

    @Test
    fun `add and filter nodes by tier`() {
        var graph = PersonalKnowledgeGraph()
        val tempNode = KnowledgeNode(
            id = "temp_1",
            label = "Current Task",
            category = KnowledgeCategory.TASK_ROUTINE,
            tier = MemoryTier.TEMPORARY,
            summary = "Booking a cab to airport"
        )
        val longTermNode = KnowledgeNode(
            id = "long_1",
            label = "Home Address",
            category = KnowledgeCategory.USER_PREFERENCE,
            tier = MemoryTier.LONG_TERM,
            summary = "123 Main St, Springfield"
        )
        val patternNode = KnowledgeNode(
            id = "pat_1",
            label = "Spotify",
            category = KnowledgeCategory.APP_PREFERENCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Preferred music app",
            confidence = 0.9f
        )
        val sensitiveNode = KnowledgeNode(
            id = "sens_1",
            label = "Locker Pin",
            category = KnowledgeCategory.USER_PREFERENCE,
            tier = MemoryTier.SENSITIVE,
            summary = "Hardware-encrypted secret"
        )

        graph = graph.addOrUpdateNode(tempNode)
            .addOrUpdateNode(longTermNode)
            .addOrUpdateNode(patternNode)
            .addOrUpdateNode(sensitiveNode)

        assertEquals(4, graph.nodes.size)
        assertEquals(1, graph.getNodesByTier(MemoryTier.TEMPORARY).size)
        assertEquals(1, graph.getNodesByTier(MemoryTier.LONG_TERM).size)
        assertEquals(1, graph.getNodesByTier(MemoryTier.LEARNED_PATTERN).size)
        assertEquals(1, graph.getNodesByTier(MemoryTier.SENSITIVE).size)
    }

    @Test
    fun `filter nodes by category`() {
        var graph = PersonalKnowledgeGraph()
        val contactNode = KnowledgeNode(
            id = "contact_1",
            label = "Alice",
            category = KnowledgeCategory.CONTACT,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Frequently messaged on WhatsApp"
        )
        val appNode = KnowledgeNode(
            id = "app_1",
            label = "YouTube",
            category = KnowledgeCategory.APP_PREFERENCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Preferred video app"
        )

        graph = graph.addOrUpdateNode(contactNode).addOrUpdateNode(appNode)
        val contacts = graph.getNodesByCategory(KnowledgeCategory.CONTACT)
        assertEquals(1, contacts.size)
        assertEquals("Alice", contacts[0].label)
    }

    @Test
    fun `find nodes matches label, summary, and properties`() {
        var graph = PersonalKnowledgeGraph()
        val projectNode = KnowledgeNode(
            id = "proj_1",
            label = "Apollo Initiative",
            category = KnowledgeCategory.PROJECT,
            tier = MemoryTier.LONG_TERM,
            summary = "Q3 marketing roadmap",
            properties = mapOf("lead" to "Sarah", "deadline" to "September 30")
        )
        graph = graph.addOrUpdateNode(projectNode)

        assertEquals(1, graph.findNodes("Apollo").size)
        assertEquals(1, graph.findNodes("marketing").size)
        assertEquals(1, graph.findNodes("Sarah").size)
        assertEquals(0, graph.findNodes("nonexistent").size)
    }

    @Test
    fun `remove node purges node and related relations`() {
        var graph = PersonalKnowledgeGraph()
        val nodeA = KnowledgeNode(
            id = "a",
            label = "Alice",
            category = KnowledgeCategory.CONTACT,
            tier = MemoryTier.LONG_TERM,
            summary = "Colleague"
        )
        val nodeB = KnowledgeNode(
            id = "b",
            label = "Project Alpha",
            category = KnowledgeCategory.PROJECT,
            tier = MemoryTier.LONG_TERM,
            summary = "Autonomous agent"
        )
        val relation = KnowledgeRelation(
            sourceId = "a",
            targetId = "b",
            relationType = "WORKS_ON"
        )

        graph = graph.addOrUpdateNode(nodeA)
            .addOrUpdateNode(nodeB)
            .addRelation(relation)

        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.relations.size)

        graph = graph.removeNode("a")
        assertEquals(1, graph.nodes.size)
        assertEquals(0, graph.relations.size)
    }
}
