package com.opendroid.ai.social.core.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opendroid.ai.data.db.MigrationTestApplication
import com.opendroid.ai.data.db.OpenDroidDatabase
import com.opendroid.ai.data.repository.SocialRepository
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MigrationTestApplication::class)
class SocialRuleEngineTest {

    private lateinit var database: OpenDroidDatabase
    private lateinit var socialRepository: SocialRepository
    private lateinit var fakeReplyEngine: FakeReplyEngine
    private lateinit var ruleEngine: SocialRuleEngine

    private class FakeReplyEngine(
        var reply: SuggestedReply = SuggestedReply(
            replyText = "Default AI generated reply ✨",
            isConfident = true,
            confidenceScore = 0.95f,
            reason = "Verified features"
        )
    ) : SocialCommentReplyEngine() {
        override suspend fun generateReply(comment: SocialComment, postContext: String?): SuggestedReply = reply
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, OpenDroidDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        socialRepository = SocialRepository(
            database.socialAccountDao(),
            database.socialPostDao(),
            database.socialCommentDao(),
            database.socialInteractionDao(),
            database.socialAnalyticsDao(),
            database.socialCampaignDao(),
            database.socialAutomationRuleDao(),
            database.socialAuditLogDao()
        )

        fakeReplyEngine = FakeReplyEngine()
        ruleEngine = SocialRuleEngine(socialRepository, fakeReplyEngine)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun createComment(
        content: String,
        platform: SocialPlatform = SocialPlatform.X,
        sentiment: Sentiment = Sentiment.NEUTRAL,
        authorName: String = "Alex"
    ): SocialComment = SocialComment(
        id = UUID.randomUUID().toString(),
        platformCommentId = "pc-123",
        postId = "post-1",
        platform = platform,
        authorName = authorName,
        content = content,
        sentiment = sentiment
    )

    @Test
    fun `sensitive keywords strictly require human approval even in autonomous mode`() = runBlocking {
        val comment = createComment("I demand a refund immediately, this is a scam!")
        
        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.AUTONOMOUS
        )

        assertTrue(result is RuleEvaluationResult.NeedsHumanApproval)
        val approval = result as RuleEvaluationResult.NeedsHumanApproval
        assertNull(approval.rule)
        assertTrue(approval.reason.contains("Sensitive", ignoreCase = true))
    }

    @Test
    fun `negative sentiment comments strictly require human approval`() = runBlocking {
        val comment = createComment(
            content = "This update completely broke my daily workflow.",
            sentiment = Sentiment.NEGATIVE
        )

        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.AUTONOMOUS
        )

        assertTrue(result is RuleEvaluationResult.NeedsHumanApproval)
        val approval = result as RuleEvaluationResult.NeedsHumanApproval
        assertNull(approval.rule)
        assertTrue(approval.reason.contains("negative", ignoreCase = true))
    }

    @Test
    fun `matching autonomous rule auto-replies with template placeholders replaced`() = runBlocking {
        val rule = SocialAutomationRule(
            id = "rule-1",
            name = "Welcome Greeting",
            isEnabled = true,
            platform = SocialPlatform.X,
            triggerType = "KEYWORD_MATCH",
            keywords = listOf("hello", "hi there"),
            actionType = "AUTO_REPLY",
            replyTemplate = "Hello @{author}! Welcome to OpenDroid 🚀",
            confidenceThreshold = 0.95f,
            requireHumanApproval = false
        )
        socialRepository.saveRule(rule)

        val comment = createComment(
            content = "Hello there! Exciting to try this out",
            platform = SocialPlatform.X,
            authorName = "Jordan"
        )

        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.AUTONOMOUS
        )

        assertTrue(result is RuleEvaluationResult.AutoReply)
        val autoReply = result as RuleEvaluationResult.AutoReply
        assertEquals("rule-1", autoReply.rule.id)
        assertEquals("Hello @Jordan! Welcome to OpenDroid 🚀", autoReply.replyText)
        assertEquals(0.95f, autoReply.confidence, 0.01f)
    }

    @Test
    fun `matching rule under approval mode requires human approval`() = runBlocking {
        val rule = SocialAutomationRule(
            id = "rule-2",
            name = "Feature Inquiry",
            isEnabled = true,
            platform = null, // All platforms
            triggerType = "KEYWORD_MATCH",
            keywords = listOf("offline"),
            actionType = "AUTO_REPLY",
            replyTemplate = "OpenDroid runs fully offline using LiteRT!",
            confidenceThreshold = 0.95f,
            requireHumanApproval = false
        )
        socialRepository.saveRule(rule)

        val comment = createComment(content = "Does it work offline?")

        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.APPROVAL
        )

        assertTrue(result is RuleEvaluationResult.NeedsHumanApproval)
        val approval = result as RuleEvaluationResult.NeedsHumanApproval
        assertEquals("rule-2", approval.rule?.id)
        assertTrue(approval.reason.contains("APPROVAL", ignoreCase = true))
    }

    @Test
    fun `rule requiring human approval is not auto-replied even in autonomous mode`() = runBlocking {
        val rule = SocialAutomationRule(
            id = "rule-3",
            name = "Partnership Outreach",
            isEnabled = true,
            triggerType = "KEYWORD_MATCH",
            keywords = listOf("partner"),
            actionType = "AUTO_REPLY",
            confidenceThreshold = 0.95f,
            requireHumanApproval = true
        )
        socialRepository.saveRule(rule)

        val comment = createComment(content = "We would love to partner with you")

        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.AUTONOMOUS
        )

        assertTrue(result is RuleEvaluationResult.NeedsHumanApproval)
        val approval = result as RuleEvaluationResult.NeedsHumanApproval
        assertEquals("rule-3", approval.rule?.id)
        assertTrue(approval.reason.contains("explicit human verification", ignoreCase = true))
    }

    @Test
    fun `rule for different platform is ignored`() = runBlocking {
        val rule = SocialAutomationRule(
            id = "rule-discord",
            name = "Discord Join",
            isEnabled = true,
            platform = SocialPlatform.DISCORD,
            triggerType = "KEYWORD_MATCH",
            keywords = listOf("community"),
            actionType = "AUTO_REPLY",
            replyTemplate = "Join our Discord server!",
            confidenceThreshold = 0.95f,
            requireHumanApproval = false
        )
        socialRepository.saveRule(rule)

        // Comment is on X, not Discord
        val comment = createComment(
            content = "Where is your community hosted?",
            platform = SocialPlatform.X
        )

        val result = ruleEngine.evaluateComment(
            comment = comment,
            automationLevel = AutomationLevel.AUTONOMOUS
        )

        // Rule should not match; falls back to reply engine
        assertTrue(result is RuleEvaluationResult.NeedsHumanApproval)
        val approval = result as RuleEvaluationResult.NeedsHumanApproval
        assertNull(approval.rule)
    }
}
