package com.opendroid.ai.social.core.ai

import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.AutomationLevel
import com.opendroid.ai.social.domain.model.Sentiment
import com.opendroid.ai.social.domain.model.SocialAutomationRule
import com.opendroid.ai.social.domain.model.SocialComment
import javax.inject.Inject
import javax.inject.Singleton

sealed class RuleEvaluationResult {
    data class AutoReply(val rule: SocialAutomationRule, val replyText: String, val confidence: Float) : RuleEvaluationResult()
    data class NeedsHumanApproval(val rule: SocialAutomationRule?, val suggestedReply: String, val reason: String) : RuleEvaluationResult()
    data object NoMatch : RuleEvaluationResult()
}

@Singleton
class SocialRuleEngine @Inject constructor(
    private val socialRepository: SocialRepository,
    private val replyEngine: SocialCommentReplyEngine
) {

    private val sensitiveKeywords = setOf(
        "lawsuit", "legal", "lawyer", "sue", "refund", "scam", "fraud",
        "angry", "furious", "terrible", "stole", "password", "exploit",
        "vulnerability", "hack", "leak", "security issue", "breach"
    )

    suspend fun evaluateComment(
        comment: SocialComment,
        automationLevel: AutomationLevel,
        postContext: String? = null
    ): RuleEvaluationResult {
        // 1. Strict Security Guardrail: Check for sensitive/controversial/legal/financial keywords
        val lowerContent = comment.content.lowercase()
        val isSensitive = sensitiveKeywords.any { lowerContent.contains(it) } || comment.sentiment == Sentiment.NEGATIVE

        if (isSensitive) {
            val suggested = replyEngine.generateReply(comment, postContext)
            return RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = "Sensitive, negative, or security-related conversation. Human approval strictly required."
            )
        }

        // 2. Fetch Active Rules from Database
        val activeRules = socialRepository.getActiveRules().filter { rule ->
            rule.platform == null || rule.platform == comment.platform
        }

        for (rule in activeRules) {
            val matches = when (rule.triggerType) {
                "KEYWORD_MATCH" -> rule.keywords.any { lowerContent.contains(it.lowercase().trim()) }
                "INTENT_QUESTION" -> lowerContent.contains("?") || lowerContent.contains("how") || lowerContent.contains("what")
                "SENTIMENT_POSITIVE" -> comment.sentiment == Sentiment.POSITIVE
                else -> false
            }

            if (matches) {
                val replyText = if (!rule.replyTemplate.isNullOrBlank()) {
                    rule.replyTemplate.replace("{author}", comment.authorName)
                } else {
                    replyEngine.generateReply(comment, postContext).replyText
                }

                // Autonomous reply allowed ONLY IF:
                // 1. Global AutomationLevel is AUTONOMOUS
                // 2. Rule does not require human approval
                // 3. Rule confidence threshold is met
                // 4. Topic is not sensitive
                val canAutoReply = automationLevel == AutomationLevel.AUTONOMOUS &&
                        !rule.requireHumanApproval &&
                        rule.confidenceThreshold >= 0.90f

                return if (canAutoReply) {
                    RuleEvaluationResult.AutoReply(rule, replyText, rule.confidenceThreshold)
                } else {
                    RuleEvaluationResult.NeedsHumanApproval(
                        rule = rule,
                        suggestedReply = replyText,
                        reason = if (automationLevel != AutomationLevel.AUTONOMOUS) {
                            "Automation level is ${automationLevel.name} (Requires approval)"
                        } else {
                            "Rule '${rule.name}' requires explicit human verification"
                        }
                    )
                }
            }
        }

        // 3. Fallback evaluation via reply engine
        val suggested = replyEngine.generateReply(comment, postContext)
        return if (suggested.isConfident && suggested.confidenceScore >= 0.90f && automationLevel == AutomationLevel.AUTONOMOUS) {
            RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = "Standard AI suggestion ready for review"
            )
        } else {
            RuleEvaluationResult.NeedsHumanApproval(
                rule = null,
                suggestedReply = suggested.replyText,
                reason = suggested.reason
            )
        }
    }
}
