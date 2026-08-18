package com.opendroid.ai.core.memory.graph

import android.util.Log
import com.opendroid.ai.core.memory.NotificationIntelligence
import com.opendroid.ai.core.memory.WorkingMemory
import com.opendroid.ai.core.security.SensitiveMemoryStore
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Personal Growth Engine maintains a structured Personal Knowledge Graph
 * across 4 security and retention tiers:
 * 1. Temporary (active task/session memory)
 * 2. Long-Term (explicitly saved facts, notes, projects, preferences)
 * 3. Learned Patterns (inferred behaviors with confidence scoring)
 * 4. Sensitive Data (encrypted at rest via AndroidKeyStore AES-256-GCM)
 */
@Singleton
class PersonalGrowthEngine @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val sensitiveMemoryStore: SensitiveMemoryStore,
    private val workingMemory: WorkingMemory,
    private val notificationIntelligence: NotificationIntelligence
) {
    companion object {
        private const val TAG = "PersonalGrowthEngine"
        private const val PKG_NODE_PREFIX = "pkg_node_"
        private const val PKG_RELATION_KEY = "pkg_relations_index"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }

    /**
     * Observe the Personal Knowledge Graph nodes as a Flow.
     */
    val graphFlow: Flow<PersonalKnowledgeGraph> = memoryRepository.allMemories.map { memories ->
        val nodes = mutableMapOf<String, KnowledgeNode>()
        for (memory in memories) {
            if (memory.key.startsWith(PKG_NODE_PREFIX)) {
                try {
                    val node = json.decodeFromString<KnowledgeNode>(memory.value)
                    nodes[node.id] = node
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode knowledge node: ${memory.key}")
                }
            }
        }
        PersonalKnowledgeGraph(nodes = nodes)
    }

    /**
     * Load the current snapshot of the Personal Knowledge Graph.
     */
    suspend fun getSnapshot(): PersonalKnowledgeGraph {
        val memories = memoryRepository.getMemoriesByKeyPrefix(PKG_NODE_PREFIX)
        val nodes = mutableMapOf<String, KnowledgeNode>()
        for (memory in memories) {
            try {
                val node = json.decodeFromString<KnowledgeNode>(memory.value)
                nodes[node.id] = node
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode knowledge node: ${memory.key}")
            }
        }

        // Add Sensitive Data metadata nodes (without leaking secrets)
        val sensitiveKeys = sensitiveMemoryStore.listKeys()
        for (key in sensitiveKeys) {
            val sensitiveNodeId = "sensitive_$key"
            if (!nodes.containsKey(sensitiveNodeId)) {
                nodes[sensitiveNodeId] = KnowledgeNode(
                    id = sensitiveNodeId,
                    label = key,
                    category = KnowledgeCategory.USER_PREFERENCE,
                    tier = MemoryTier.SENSITIVE,
                    summary = "Hardware-encrypted sensitive record ($key)",
                    confidence = 1.0f,
                    properties = mapOf("encrypted" to "true")
                )
            }
        }

        val relationsMemory = memoryRepository.getMemoriesByKeyPrefix(PKG_RELATION_KEY).firstOrNull()
        val relations = if (relationsMemory != null) {
            try {
                json.decodeFromString<List<KnowledgeRelation>>(relationsMemory.value)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        return PersonalKnowledgeGraph(nodes = nodes, relations = relations)
    }

    // ── LEVEL 1: TEMPORARY MEMORY (Current Task Context) ───────────────

    fun recordTemporaryTaskContext(key: String, value: String) {
        workingMemory.location = "$key: $value"
    }

    // ── LEVEL 2: LONG-TERM MEMORY (Explicit User Memories) ─────────────

    suspend fun recordExplicitMemory(
        label: String,
        summary: String,
        category: KnowledgeCategory = KnowledgeCategory.NOTE_FACT,
        properties: Map<String, String> = emptyMap()
    ): KnowledgeNode {
        val sanitizedId = "explicit_${label.lowercase().replace(Regex("[^a-z0-9_]"), "_").take(30)}_${System.currentTimeMillis()}"
        val node = KnowledgeNode(
            id = sanitizedId,
            label = label,
            category = category,
            tier = MemoryTier.LONG_TERM,
            summary = summary,
            properties = properties,
            confidence = 1.0f,
            accessCount = 1,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
        return node
    }

    suspend fun recordProject(
        projectName: String,
        description: String,
        status: String = "active"
    ): KnowledgeNode {
        return recordExplicitMemory(
            label = projectName,
            summary = description,
            category = KnowledgeCategory.PROJECT,
            properties = mapOf("status" to status, "type" to "project")
        )
    }

    suspend fun recordUserPreference(
        preferenceKey: String,
        preferenceValue: String
    ): KnowledgeNode {
        return recordExplicitMemory(
            label = preferenceKey,
            summary = preferenceValue,
            category = KnowledgeCategory.USER_PREFERENCE,
            properties = mapOf("preferenceKey" to preferenceKey, "preferenceValue" to preferenceValue)
        )
    }

    // ── LEVEL 3: LEARNED PATTERNS (Behavioral Inferences) ──────────────

    suspend fun recordAppUsage(appName: String, actionCategory: String) {
        val nodeId = "pattern_app_${appName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 10 -> 0.95f
            count >= 5 -> 0.85f
            count >= 3 -> 0.70f
            else -> 0.50f
        }

        val node = KnowledgeNode(
            id = nodeId,
            label = appName,
            category = KnowledgeCategory.APP_PREFERENCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Preferred app for $actionCategory: $appName (used $count times)",
            properties = mapOf("appName" to appName, "category" to actionCategory, "count" to count.toString()),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
    }

    suspend fun recordContactInteraction(contactName: String, channel: String) {
        val nodeId = "pattern_contact_${contactName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 10 -> 0.95f
            count >= 5 -> 0.85f
            count >= 3 -> 0.70f
            else -> 0.50f
        }

        val node = KnowledgeNode(
            id = nodeId,
            label = contactName,
            category = KnowledgeCategory.CONTACT,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Frequent contact: $contactName via $channel ($count interactions)",
            properties = mapOf("contactName" to contactName, "preferredChannel" to channel, "count" to count.toString()),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
    }

    suspend fun recordTaskOrRoutine(taskTitle: String, timeContext: String, frequencyHint: String = "recurring") {
        val nodeId = "pattern_routine_${taskTitle.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val confidence = when {
            count >= 5 -> 0.90f
            count >= 2 -> 0.75f
            else -> 0.60f
        }

        val node = KnowledgeNode(
            id = nodeId,
            label = taskTitle,
            category = KnowledgeCategory.TASK_ROUTINE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Routine: $taskTitle at $timeContext ($frequencyHint, triggered $count times)",
            properties = mapOf("time" to timeContext, "frequency" to frequencyHint, "count" to count.toString()),
            confidence = confidence,
            accessCount = count,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
    }

    suspend fun recordCommonSchedule(eventTitle: String, dayOrTime: String) {
        val nodeId = "pattern_schedule_${eventTitle.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1
        val node = KnowledgeNode(
            id = nodeId,
            label = eventTitle,
            category = KnowledgeCategory.SCHEDULE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Common schedule: $eventTitle on $dayOrTime",
            properties = mapOf("schedule" to dayOrTime, "count" to count.toString()),
            confidence = if (count >= 3) 0.85f else 0.60f,
            accessCount = count,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
    }

    suspend fun recordWebsiteVisit(url: String, domain: String) {
        val cleanDomain = domain.ifBlank { url.substringAfter("://").substringBefore("/") }
        val nodeId = "pattern_web_${cleanDomain.lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
        val existing = getNodeFromDb(nodeId)
        val count = (existing?.accessCount ?: 0) + 1

        val node = KnowledgeNode(
            id = nodeId,
            label = cleanDomain,
            category = KnowledgeCategory.RESOURCE,
            tier = MemoryTier.LEARNED_PATTERN,
            summary = "Frequently visited resource: $cleanDomain (accessed $count times)",
            properties = mapOf("url" to url, "domain" to cleanDomain, "count" to count.toString()),
            confidence = if (count >= 5) 0.90f else 0.65f,
            accessCount = count,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(node)
    }

    // ── LEVEL 4: SENSITIVE DATA (Encrypted Keystore Storage) ────────────

    suspend fun recordSensitiveSecret(key: String, secretValue: String, label: String): Boolean {
        val success = sensitiveMemoryStore.write(key, secretValue)
        if (success) {
            val nodeId = "sensitive_$key"
            val node = KnowledgeNode(
                id = nodeId,
                label = label.ifBlank { key },
                category = KnowledgeCategory.USER_PREFERENCE,
                tier = MemoryTier.SENSITIVE,
                summary = "Hardware-encrypted sensitive record ($label)",
                confidence = 1.0f,
                properties = mapOf("key" to key, "encrypted" to "true")
            )
            saveNodeToDb(node)
        }
        return success
    }

    fun getSensitiveSecret(key: String): String? {
        return sensitiveMemoryStore.read(key)
    }

    // ── PROMOTION & MANAGEMENT ─────────────────────────────────────────

    suspend fun promotePatternToExplicit(nodeId: String): Boolean {
        val existing = getNodeFromDb(nodeId) ?: return false
        val updated = existing.copy(
            tier = MemoryTier.LONG_TERM,
            confidence = 1.0f,
            lastUpdated = System.currentTimeMillis()
        )
        saveNodeToDb(updated)
        return true
    }

    suspend fun deleteNode(nodeId: String): Boolean {
        if (nodeId.startsWith("sensitive_")) {
            val secretKey = nodeId.removePrefix("sensitive_")
            sensitiveMemoryStore.remove(secretKey)
        }
        memoryRepository.deleteMemory("$PKG_NODE_PREFIX$nodeId")
        return true
    }

    suspend fun clearTier(tier: MemoryTier) {
        when (tier) {
            MemoryTier.TEMPORARY -> workingMemory.clear()
            MemoryTier.SENSITIVE -> sensitiveMemoryStore.clearAll()
            else -> {
                val snapshot = getSnapshot()
                val nodesInTier = snapshot.getNodesByTier(tier)
                for (node in nodesInTier) {
                    memoryRepository.deleteMemory("$PKG_NODE_PREFIX${node.id}")
                }
            }
        }
    }

    // ── SYNTHESIZED CONTEXT FOR LLM PROMPTS ────────────────────────────

    suspend fun generateSynthesizedContext(query: String = ""): String {
        val snapshot = getSnapshot()
        val longTerm = snapshot.getNodesByTier(MemoryTier.LONG_TERM)
        val patterns = snapshot.getNodesByTier(MemoryTier.LEARNED_PATTERN).filter { it.confidence >= 0.6f }

        val sb = StringBuilder()
        if (longTerm.isNotEmpty()) {
            sb.append("[Long-Term Explicit Memory]\n")
            longTerm.sortedByDescending { it.lastUpdated }.take(10).forEach { node ->
                sb.append("• ${node.label}: ${node.summary}\n")
            }
        }

        if (patterns.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[Learned Behavioral Patterns (Inferred)]\n")
            patterns.sortedByDescending { it.confidence }.take(8).forEach { node ->
                val confPercent = (node.confidence * 100).toInt()
                sb.append("• ${node.summary} (${confPercent}% confidence)\n")
            }
        }

        return sb.toString().trim()
    }

    private suspend fun saveNodeToDb(node: KnowledgeNode) {
        val key = "$PKG_NODE_PREFIX${node.id}"
        val value = json.encodeToString(node)
        memoryRepository.saveMemory(
            Memory(
                key = key,
                value = value,
                type = MemoryType.SEMANTIC,
                timestamp = node.lastUpdated
            )
        )
    }

    private suspend fun getNodeFromDb(nodeId: String): KnowledgeNode? {
        val key = "$PKG_NODE_PREFIX$nodeId"
        val memories = memoryRepository.getMemoriesByKeyPrefix(key)
        val memory = memories.firstOrNull { it.key == key } ?: return null
        return try {
            json.decodeFromString<KnowledgeNode>(memory.value)
        } catch (e: Exception) {
            null
        }
    }
}
