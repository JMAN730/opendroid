package com.opendroid.ai.core.memory

import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.Memory
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryExtractor @Inject constructor(
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private val MEMORY_BLACKLIST = listOf(
            "is not registered",
            "invalid_action",
            "actiondispatcher",
            "do not use this action",
            "whitelisted actions",
            "not registered in",
            "execution error",
            "action module",
            "fallback routine",
            "plan sequence",
            "stepid",
            "dependson",
            "canparallelize",
            "estimatedduration",
            "planid",
            "unknown action",
            "not a valid action",
            "action failed"
        )

        // Words that can open "<word> is my wife" without being a name.
        private val NON_NAME_WORDS = setOf(
            "what", "who", "which", "where", "when", "why", "how", "whose",
            "this", "that", "it", "he", "she", "they", "there", "here",
            "today", "tomorrow", "tonight", "yesterday", "now", "then"
        )
    }

    fun shouldStoreInSemanticMemory(content: String): Boolean {
        val lower = content.lowercase().trim()
        if (lower.isEmpty()) return false
        if (MEMORY_BLACKLIST.any { lower.contains(it) }) return false
        if (lower.startsWith("invalid_")) return false
        return true
    }

    private val patterns = listOf(
        Regex("my name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "user_name",
        Regex("my wife's name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "wife_name",
        Regex("my husband's name is ([a-zA-Z\\s]+)", RegexOption.IGNORE_CASE) to "husband_name",
        Regex("I live in ([a-zA-Z\\s,]+)", RegexOption.IGNORE_CASE) to "user_location",
        Regex("my office address is ([a-zA-Z0-9\\s,]+)", RegexOption.IGNORE_CASE) to "office_address",
        Regex("my home address is ([a-zA-Z0-9\\s,]+)", RegexOption.IGNORE_CASE) to "home_address",
        Regex("my phone number is ([0-9+\\-]+)", RegexOption.IGNORE_CASE) to "user_phone"
    )

    suspend fun extractFacts(messages: List<ChatMessage>) {
        messages.forEach { msg ->
            if (msg.sender == ChatMessage.Sender.USER) {
                // Heuristic pattern checking
                val explicitKeys = mutableSetOf<String>()
                patterns.forEach { (regex, key) ->
                    val match = regex.find(msg.text)
                    if (match != null) {
                        val value = match.groupValues[1].trim()
                        if (shouldStoreInSemanticMemory(key) && shouldStoreInSemanticMemory(value)) {
                            memoryRepository.saveMemory(
                                Memory(key = key, value = value, type = MemoryType.SEMANTIC)
                            )
                            explicitKeys += key
                        }
                    }
                }

                // "Maria is my wife": only fills an empty slot. An explicit "my wife's name is"
                // statement is the only way to change a stored spouse name.
                spousePatterns.forEach { (regex, key) ->
                    if (key in explicitKeys) return@forEach
                    val value = regex.find(msg.text)?.groupValues?.get(1)?.trim() ?: return@forEach
                    if (value.lowercase() in NON_NAME_WORDS) return@forEach
                    if (!shouldStoreInSemanticMemory(key) || !shouldStoreInSemanticMemory(value)) return@forEach
                    if (memoryRepository.getMemory(key) != null) return@forEach
                    memoryRepository.saveMemory(
                        Memory(key = key, value = value, type = MemoryType.SEMANTIC)
                    )
                }
            }
        }
    }

    // The name must open the sentence or clause, and "wife's"/"husband's" (someone else's
    // relation, or a question about the spouse) never counts.
    private val spousePatterns = listOf(
        Regex("(?:^|[.!?,;:]\\s*)([a-zA-Z]+) is my wife\\b(?!\\s*['’]s)", RegexOption.IGNORE_CASE) to "wife_name",
        Regex("(?:^|[.!?,;:]\\s*)([a-zA-Z]+) is my husband\\b(?!\\s*['’]s)", RegexOption.IGNORE_CASE) to "husband_name"
    )
}
