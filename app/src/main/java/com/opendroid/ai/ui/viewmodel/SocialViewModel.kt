package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.core.SocialManager
import com.opendroid.ai.social.core.ai.*
import com.opendroid.ai.social.core.analytics.AnalyticsSummary
import com.opendroid.ai.social.core.analytics.SocialAnalyticsEngine
import com.opendroid.ai.social.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val socialManager: SocialManager,
    private val socialRepository: SocialRepository,
    private val contentComposer: SocialContentComposer,
    private val campaignGenerator: SocialCampaignGenerator,
    private val insightsEngine: SocialInsightsEngine,
    private val analyticsEngine: SocialAnalyticsEngine,
    private val commentReplyEngine: SocialCommentReplyEngine
) : ViewModel() {

    val accounts: StateFlow<List<SocialAccount>> = socialRepository.getAllAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val connectedAccounts: StateFlow<List<SocialAccount>> = socialRepository.getConnectedAccountsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val posts: StateFlow<List<SocialPost>> = socialRepository.getAllPostsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val comments: StateFlow<List<SocialComment>> = socialRepository.getAllCommentsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unansweredComments: StateFlow<List<SocialComment>> = socialRepository.getUnansweredCommentsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val interactions: StateFlow<List<SocialInteraction>> = socialRepository.getAllInteractionsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val campaigns: StateFlow<List<SocialCampaign>> = socialRepository.getAllCampaignsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<SocialAutomationRule>> = socialRepository.getAllRulesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val auditLogs: StateFlow<List<SocialAuditEntry>> = socialRepository.getRecentAuditLogsFlow(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val automationLevel: StateFlow<AutomationLevel> = socialManager.automationLevel

    private val _analyticsSummary = MutableStateFlow<AnalyticsSummary?>(null)
    val analyticsSummary = _analyticsSummary.asStateFlow()

    private val _insights = MutableStateFlow<List<SocialInsight>>(emptyList())
    val insights = _insights.asStateFlow()

    private val _weeklyReport = MutableStateFlow<SocialReport?>(null)
    val weeklyReport = _weeklyReport.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating = _isGenerating.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    init {
        loadAnalyticsAndInsights()
    }

    fun loadAnalyticsAndInsights() {
        viewModelScope.launch {
            _analyticsSummary.value = analyticsEngine.getSummary(7)
            _insights.value = insightsEngine.generateInsights()
            _weeklyReport.value = analyticsEngine.generateWeeklyReport()
        }
    }

    fun setAutomationLevel(level: AutomationLevel) {
        socialManager.setAutomationLevel(level)
    }

    fun connectAccount(
        platform: SocialPlatform,
        credentials: SocialCredentials,
        permissions: List<SocialPermission>
    ) {
        viewModelScope.launch {
            _isGenerating.value = true
            val result = socialManager.connectAccount(platform, credentials, permissions)
            _isGenerating.value = false
            if (result.isSuccess) {
                _statusMessage.value = "Connected to ${platform.displayName}!"
                loadAnalyticsAndInsights()
            } else {
                _statusMessage.value = "Failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun disconnectAccount(accountId: String, platform: SocialPlatform) {
        viewModelScope.launch {
            socialManager.disconnectAccount(accountId, platform)
            _statusMessage.value = "Disconnected from ${platform.displayName}"
        }
    }

    fun revokeAccount(accountId: String, platform: SocialPlatform) {
        viewModelScope.launch {
            socialManager.revokeAccess(accountId, platform)
            _statusMessage.value = "Revoked access and deleted tokens for ${platform.displayName}"
        }
    }

    fun generatePost(
        topic: String,
        platform: SocialPlatform,
        tone: Tone = Tone.PROFESSIONAL,
        contentType: ContentType = ContentType.ANNOUNCEMENT
    ) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val generated = contentComposer.generateContentForPlatform(topic, platform, tone, contentType)
                val account = socialRepository.getAccountByPlatform(platform)
                socialManager.createDraft(
                    accountId = account?.id ?: "pending",
                    platform = platform,
                    content = generated.content,
                    contentType = contentType
                )
                _statusMessage.value = "Drafted post for ${platform.displayName}!"
            } catch (e: Exception) {
                _statusMessage.value = "Error generating post: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun createDraft(
        platform: SocialPlatform,
        content: String,
        contentType: ContentType = ContentType.ANNOUNCEMENT,
        scheduledTime: Long? = null
    ) {
        viewModelScope.launch {
            val account = socialRepository.getAccountByPlatform(platform)
            val draft = socialManager.createDraft(
                accountId = account?.id ?: "pending",
                platform = platform,
                content = content,
                contentType = contentType
            )
            if (scheduledTime != null) {
                socialManager.schedulePost(draft.id, scheduledTime)
                _statusMessage.value = "Post scheduled for ${platform.displayName}!"
            } else {
                _statusMessage.value = "Draft saved for ${platform.displayName}!"
            }
        }
    }

    fun shortenContent(content: String, platform: SocialPlatform, onResult: (String) -> Unit) {
        viewModelScope.launch {
            _isGenerating.value = true
            val shortened = contentComposer.shorten(content, platform)
            _isGenerating.value = false
            onResult(shortened)
        }
    }

    fun expandContent(content: String, platform: SocialPlatform, onResult: (String) -> Unit) {
        viewModelScope.launch {
            _isGenerating.value = true
            val expanded = contentComposer.expand(content, platform)
            _isGenerating.value = false
            onResult(expanded)
        }
    }

    fun changeToneContent(content: String, tone: Tone, platform: SocialPlatform, onResult: (String) -> Unit) {
        viewModelScope.launch {
            _isGenerating.value = true
            val modified = contentComposer.changeTone(content, tone, platform)
            _isGenerating.value = false
            onResult(modified)
        }
    }

    fun addCtaContent(content: String, ctaType: String, platform: SocialPlatform, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val withCta = contentComposer.addCta(content, ctaType, platform)
            onResult(withCta)
        }
    }

    fun removeHashtagsContent(content: String): String {
        return contentComposer.removeHashtags(content)
    }

    fun generateReplyForComment(comment: SocialComment, onResult: (SuggestedReply) -> Unit) {
        viewModelScope.launch {
            _isGenerating.value = true
            val reply = commentReplyEngine.generateReply(comment)
            _isGenerating.value = false
            onResult(reply)
        }
    }

    fun approvePost(postId: String, immediatePublish: Boolean = false) {
        viewModelScope.launch {
            val result = socialManager.approvePost(postId, immediatePublish)
            if (result.isSuccess) {
                _statusMessage.value = if (immediatePublish) "Post published successfully!" else "Post approved for scheduling."
            } else {
                _statusMessage.value = "Failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun schedulePost(postId: String, scheduledTime: Long) {
        viewModelScope.launch {
            val result = socialManager.schedulePost(postId, scheduledTime)
            if (result.isSuccess) {
                _statusMessage.value = "Post scheduled successfully."
            } else {
                _statusMessage.value = "Scheduling failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun publishPostNow(postId: String) {
        viewModelScope.launch {
            _isGenerating.value = true
            val result = socialManager.publishPost(postId)
            _isGenerating.value = false
            if (result.isSuccess) {
                _statusMessage.value = "Published successfully!"
            } else {
                _statusMessage.value = "Publication failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun cancelPost(postId: String) {
        viewModelScope.launch {
            socialRepository.cancelPost(postId)
            _statusMessage.value = "Post cancelled."
        }
    }

    fun deletePost(postId: String) {
        viewModelScope.launch {
            socialRepository.deletePost(postId)
            _statusMessage.value = "Post deleted."
        }
    }

    fun replyToComment(commentId: String, text: String) {
        viewModelScope.launch {
            val result = socialManager.replyToComment(commentId, text, actor = "USER")
            if (result.isSuccess) {
                _statusMessage.value = "Reply sent!"
            } else {
                _statusMessage.value = "Reply failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun ignoreComment(commentId: String) {
        viewModelScope.launch {
            socialRepository.ignoreComment(commentId)
            _statusMessage.value = "Comment dismissed."
        }
    }

    fun resolveInteraction(interactionId: String) {
        viewModelScope.launch {
            socialRepository.updateInteractionStatus(interactionId, "RESOLVED")
            _statusMessage.value = "Interaction marked as resolved."
        }
    }

    fun dismissInteraction(interactionId: String) {
        viewModelScope.launch {
            socialRepository.updateInteractionStatus(interactionId, "DISMISSED")
            _statusMessage.value = "Interaction dismissed."
        }
    }

    fun createCampaign(name: String, objective: String, durationDays: Int, platforms: List<SocialPlatform>) {
        viewModelScope.launch {
            _isGenerating.value = true
            try {
                val plan = campaignGenerator.generateCampaignPlan(name, objective, durationDays, platforms)
                socialRepository.saveCampaign(plan.campaign)
                plan.plannedPosts.forEach { socialRepository.savePost(it) }
                _statusMessage.value = "Created campaign '${plan.campaign.name}' with ${plan.plannedPosts.size} posts!"
            } catch (e: Exception) {
                _statusMessage.value = "Campaign generation failed: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun saveRule(rule: SocialAutomationRule) {
        viewModelScope.launch {
            socialRepository.saveRule(rule)
            _statusMessage.value = "Automation rule saved."
        }
    }

    fun toggleRule(ruleId: String, enabled: Boolean) {
        viewModelScope.launch {
            socialRepository.setRuleEnabled(ruleId, enabled)
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            socialRepository.deleteRule(ruleId)
            _statusMessage.value = "Rule deleted."
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun refreshAll() {
        viewModelScope.launch {
            _isGenerating.value = true
            socialManager.syncAllAccounts()
            loadAnalyticsAndInsights()
            _isGenerating.value = false
            _statusMessage.value = "Refreshed social data."
        }
    }
}
