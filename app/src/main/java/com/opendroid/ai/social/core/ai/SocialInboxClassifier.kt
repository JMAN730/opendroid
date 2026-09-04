package com.opendroid.ai.social.core.ai

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.social.domain.model.InteractionCategory
import com.opendroid.ai.social.domain.model.InteractionPriority
import com.opendroid.ai.social.domain.model.Sentiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ClassificationResult(
    val category: InteractionCategory,
    val sentiment: Sentiment,
    val priority: InteractionPriority,
    val suggestedAction: String
)

@Singleton
class SocialInboxClassifier @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory
) {

    suspend fun classify(text: String, author: String = ""): ClassificationResult = withContext(Dispatchers.Default) {
        val prompt = """
            Analyze the following incoming social media interaction and classify it.
            Interaction: "$text" from "$author"
            
            Classify into:
            1. category: one of [QUESTION, POSITIVE_FEEDBACK, NEGATIVE_FEEDBACK, SUPPORT_REQUEST, FEATURE_REQUEST, BUG_REPORT, LEAD, COLLABORATION, SPAM, GENERAL]
            2. sentiment: one of [POSITIVE, NEUTRAL, NEGATIVE, MIXED]
            3. priority: one of [LOW, NORMAL, HIGH, URGENT]
            4. suggestedAction: short 1-sentence recommended action for the user
            
            Output strictly valid JSON with keys: "category", "sentiment", "priority", "suggestedAction".
        """.trimIndent()

        try {
            val provider = llmProviderFactory.getActiveProvider()
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a customer interaction and sentiment classification engine.",
                    messages = listOf(
                        ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    )
                )
            )
            val jsonStr = response.content.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val json = JSONObject(jsonStr)
            val cat = runCatching { InteractionCategory.valueOf(json.getString("category")) }.getOrDefault(InteractionCategory.GENERAL)
            val sent = runCatching { Sentiment.valueOf(json.getString("sentiment")) }.getOrDefault(Sentiment.NEUTRAL)
            val prio = runCatching { InteractionPriority.valueOf(json.getString("priority")) }.getOrDefault(InteractionPriority.NORMAL)
            val action = json.optString("suggestedAction", "Review interaction")
            ClassificationResult(cat, sent, prio, action)
        } catch (e: Exception) {
            deterministicFallback(text)
        }
    }

    private fun deterministicFallback(text: String): ClassificationResult {
        val lower = text.lowercase()
        val category = when {
            lower.contains("crash") || lower.contains("error") || lower.contains("bug") || lower.contains("broken") -> InteractionCategory.BUG_REPORT
            lower.contains("how to") || lower.contains("how do i") || lower.contains("help") || lower.contains("support") -> InteractionCategory.SUPPORT_REQUEST
            lower.contains("?") || lower.contains("when") || lower.contains("where") || lower.contains("what") -> InteractionCategory.QUESTION
            lower.contains("partner") || lower.contains("license") || lower.contains("business") || lower.contains("collaborate") -> InteractionCategory.COLLABORATION
            lower.contains("can you add") || lower.contains("feature") || lower.contains("request") -> InteractionCategory.FEATURE_REQUEST
            lower.contains("love") || lower.contains("awesome") || lower.contains("great") || lower.contains("good") -> InteractionCategory.POSITIVE_FEEDBACK
            lower.contains("bad") || lower.contains("hate") || lower.contains("terrible") || lower.contains("worst") -> InteractionCategory.NEGATIVE_FEEDBACK
            lower.contains("buy crypto") || lower.contains("free money") || lower.contains("click here") -> InteractionCategory.SPAM
            else -> InteractionCategory.GENERAL
        }

        val sentiment = when {
            lower.contains("love") || lower.contains("awesome") || lower.contains("great") || lower.contains("congrats") -> Sentiment.POSITIVE
            lower.contains("hate") || lower.contains("terrible") || lower.contains("angry") || lower.contains("disappointed") -> Sentiment.NEGATIVE
            else -> Sentiment.NEUTRAL
        }

        val priority = when (category) {
            InteractionCategory.BUG_REPORT, InteractionCategory.COLLABORATION -> InteractionPriority.HIGH
            InteractionCategory.SUPPORT_REQUEST, InteractionCategory.QUESTION -> InteractionPriority.NORMAL
            InteractionCategory.SPAM -> InteractionPriority.LOW
            else -> if (sentiment == Sentiment.NEGATIVE) InteractionPriority.HIGH else InteractionPriority.NORMAL
        }

        val action = when (category) {
            InteractionCategory.QUESTION -> "Answer question about OpenDroid"
            InteractionCategory.BUG_REPORT -> "Investigate potential bug report"
            InteractionCategory.COLLABORATION -> "Review business collaboration opportunity"
            InteractionCategory.SUPPORT_REQUEST -> "Provide technical support assistance"
            InteractionCategory.POSITIVE_FEEDBACK -> "Thank user for positive feedback"
            InteractionCategory.SPAM -> "Mark as spam"
            else -> "Review and reply"
        }

        return ClassificationResult(category, sentiment, priority, action)
    }
}
