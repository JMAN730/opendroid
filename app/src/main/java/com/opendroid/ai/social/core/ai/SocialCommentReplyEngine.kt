package com.opendroid.ai.social.core.ai

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.social.domain.model.SocialComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class SuggestedReply(
    val replyText: String,
    val isConfident: Boolean,
    val confidenceScore: Float,
    val reason: String
)

@Singleton
open class SocialCommentReplyEngine {
    private val llmProviderFactory: LLMProviderFactory?
    private val memoryRepository: MemoryRepository?

    @Inject
    constructor(
        llmProviderFactory: LLMProviderFactory,
        memoryRepository: MemoryRepository
    ) {
        this.llmProviderFactory = llmProviderFactory
        this.memoryRepository = memoryRepository
    }

    constructor() {
        this.llmProviderFactory = null
        this.memoryRepository = null
    }

    open suspend fun generateReply(comment: SocialComment, postContext: String? = null): SuggestedReply = withContext(Dispatchers.Default) {
        val memories = memoryRepository?.getMemoriesByType(MemoryType.SEMANTIC) ?: emptyList()
        val styleMemory = memories.find { it.key == "social_approved_communication_style" }?.value
        val knowledgeMemory = memories.filter { it.key.startsWith("social_brand_") || it.key.startsWith("knowledge_") }
            .joinToString("\n") { "${it.key}: ${it.value}" }

        val prompt = """
            You are OpenDroid's AI social media assistant.
            Generate a concise, friendly, and grounded reply to the following comment.
            
            Platform: ${comment.platform.displayName}
            Author: ${comment.authorName}
            Comment: "${comment.content}"
            ${if (!postContext.isNullOrBlank()) "Original Post Context: \"$postContext\"" else ""}
            ${if (!styleMemory.isNullOrBlank()) "Preferred Style: $styleMemory" else ""}
            ${if (knowledgeMemory.isNotBlank()) "Verified Knowledge Base:\n$knowledgeMemory" else ""}
            
            CRITICAL RULES:
            1. NEVER invent facts, fake release dates, or unauthorized commitments.
            2. If the answer to a question is unknown or not in the knowledge base, say:
               "Thank you for reaching out! We are verifying this detail and our team will get back to you shortly."
            3. Keep the reply under 2 sentences. Use 1 appropriate emoji.
            
            Output strictly the reply text only.
        """.trimIndent()

        try {
            val provider = llmProviderFactory?.getActiveProvider()
                ?: return@withContext fallbackReply(comment)
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an honest, helpful social media community manager.",
                    messages = listOf(
                        ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    )
                )
            )
            val reply = response.content.trim().trim('"', '`')
            val isKnown = !reply.contains("verifying this detail")
            val confidence = if (isKnown) 0.92f else 0.40f
            SuggestedReply(
                replyText = reply,
                isConfident = isKnown,
                confidenceScore = confidence,
                reason = if (isKnown) "Grounded in OpenDroid features" else "Answer unknown; flagged for human review"
            )
        } catch (e: Exception) {
            fallbackReply(comment)
        }
    }

    private fun fallbackReply(comment: SocialComment): SuggestedReply {
        val text = comment.content.lowercase()
        return when {
            text.contains("when") && (text.contains("release") || text.contains("v1.1") || text.contains("update")) -> {
                SuggestedReply(
                    replyText = "OpenDroid v1.1 is coming soon! We'll announce the official release date right here 🚀",
                    isConfident = true,
                    confidenceScore = 0.94f,
                    reason = "Standard release inquiry"
                )
            }
            text.contains("thank") || text.contains("love") || text.contains("awesome") || text.contains("great") -> {
                SuggestedReply(
                    replyText = "Thanks for the amazing support, ${comment.authorName}! Stay tuned for more updates ✨",
                    isConfident = true,
                    confidenceScore = 0.96f,
                    reason = "Positive engagement acknowledgment"
                )
            }
            text.contains("offline") || text.contains("privacy") -> {
                SuggestedReply(
                    replyText = "Yes! OpenDroid runs local LiteRT on-device models with complete offline privacy 🛡️",
                    isConfident = true,
                    confidenceScore = 0.95f,
                    reason = "Privacy & on-device core architecture"
                )
            }
            else -> {
                SuggestedReply(
                    replyText = "Thanks for your comment! Our team is reviewing this and will follow up shortly.",
                    isConfident = false,
                    confidenceScore = 0.50f,
                    reason = "Requires human review to ensure factual accuracy"
                )
            }
        }
    }
}
