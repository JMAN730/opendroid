package com.opendroid.ai.social.core.ai

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.social.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class PlannedCampaignResult(
    val campaign: SocialCampaign,
    val plannedPosts: List<SocialPost>,
    val targetGoals: List<String>
)

@Singleton
class SocialCampaignGenerator @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory,
    private val composer: SocialContentComposer
) {

    suspend fun generateCampaignPlan(
        name: String,
        objective: String,
        durationDays: Int = 7,
        platforms: List<SocialPlatform> = listOf(SocialPlatform.X, SocialPlatform.LINKEDIN, SocialPlatform.INSTAGRAM, SocialPlatform.TELEGRAM)
    ): PlannedCampaignResult = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val dayMillis = 86400_000L
        val endDate = now + (durationDays * dayMillis)

        val prompt = """
            You are an elite AI Chief Marketing Officer creating a cross-platform social media campaign.
            
            Campaign Name: "$name"
            Objective: "$objective"
            Duration: $durationDays days
            Target Platforms: ${platforms.joinToString { it.displayName }}
            
            Generate a comprehensive strategy and 4-6 scheduled posts distributed across the duration.
            
            Output strictly valid JSON with this format:
            {
              "strategySummary": "A crisp, compelling 2-sentence campaign strategy.",
              "targetAudience": "Developers, AI enthusiasts, power Android users",
              "goals": ["Reach 50K impressions", "1,000 GitHub stars", "500 community members"],
              "posts": [
                {
                  "dayOffset": 0,
                  "platform": "X",
                  "contentType": "ANNOUNCEMENT",
                  "topic": "Campaign Kickoff & Teaser",
                  "content": "Full post copy here"
                },
                {
                  "dayOffset": 2,
                  "platform": "LINKEDIN",
                  "contentType": "PRODUCT_UPDATE",
                  "topic": "Technical deep dive on local on-device inference",
                  "content": "Full post copy here"
                }
              ]
            }
        """.trimIndent()

        try {
            val provider = llmProviderFactory.getActiveProvider()
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are an expert tech campaign strategist.",
                    messages = listOf(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    )
                )
            )
            val jsonStr = response.content.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val json = JSONObject(jsonStr)

            val campaignId = UUID.randomUUID().toString()
            val strategy = json.optString("strategySummary", "Multi-platform rollout driving awareness and community engagement.")
            val targetAudience = json.optString("targetAudience", "Tech enthusiasts, Android power users, AI developers")

            val goalsList = mutableListOf<String>()
            val goalsJson = json.optJSONArray("goals")
            if (goalsJson != null) {
                for (i in 0 until goalsJson.length()) goalsList.add(goalsJson.getString(i))
            } else {
                goalsList.addAll(listOf("Drive 25K impressions", "Acquire 500 new users", "Foster community engagement"))
            }

            val postsList = mutableListOf<SocialPost>()
            val postsJson = json.optJSONArray("posts") ?: JSONArray()
            for (i in 0 until postsJson.length()) {
                val p = postsJson.getJSONObject(i)
                val dayOffset = p.optInt("dayOffset", i)
                val plat = SocialPlatform.fromId(p.optString("platform", "X"))
                val cType = runCatching { ContentType.valueOf(p.optString("contentType", "ANNOUNCEMENT")) }.getOrDefault(ContentType.ANNOUNCEMENT)
                val postContent = p.optString("content", "Announcement for $name")
                val scheduledTime = now + (dayOffset * dayMillis) + (10 * 3600_000L) // 10:00 AM each day

                postsList.add(
                    SocialPost(
                        id = UUID.randomUUID().toString(),
                        accountId = "pending",
                        platform = plat,
                        content = postContent,
                        status = PostStatus.DRAFT,
                        scheduledPublishTime = scheduledTime,
                        campaignId = campaignId,
                        contentType = cType,
                        requiresApproval = true
                    )
                )
            }

            val campaign = SocialCampaign(
                id = campaignId,
                name = name,
                objective = objective,
                startDate = now,
                endDate = endDate,
                platforms = platforms,
                targetAudience = targetAudience,
                strategySummary = strategy,
                status = CampaignStatus.PLANNING,
                notes = "Target KPIs: ${goalsList.joinToString(", ")}"
            )

            PlannedCampaignResult(campaign, postsList, goalsList)
        } catch (e: Exception) {
            fallbackCampaignPlan(name, objective, now, endDate, platforms)
        }
    }

    private fun fallbackCampaignPlan(
        name: String,
        objective: String,
        startDate: Long,
        endDate: Long,
        platforms: List<SocialPlatform>
    ): PlannedCampaignResult {
        val campaignId = UUID.randomUUID().toString()
        val dayMillis = 86400_000L
        val posts = listOf(
            SocialPost(
                id = UUID.randomUUID().toString(),
                accountId = "pending",
                platform = SocialPlatform.X,
                content = "🚀 Introducing $name! $objective. Experience autonomous AI device workflows right from your phone. #OpenDroid #AndroidDev",
                status = PostStatus.DRAFT,
                scheduledPublishTime = startDate + 3600_000L * 2,
                campaignId = campaignId,
                contentType = ContentType.ANNOUNCEMENT,
                requiresApproval = true
            ),
            SocialPost(
                id = UUID.randomUUID().toString(),
                accountId = "pending",
                platform = SocialPlatform.LINKEDIN,
                content = "We are proud to unveil our new initiative: $name.\n\n$objective.\n\nOpenDroid sets a new standard for on-device autonomy with local LiteRT AI models and zero tracking.\n\n#AI #Engineering #Android",
                status = PostStatus.DRAFT,
                scheduledPublishTime = startDate + dayMillis * 2 + 3600_000L * 4,
                campaignId = campaignId,
                contentType = ContentType.PRODUCT_UPDATE,
                requiresApproval = true
            ),
            SocialPost(
                id = UUID.randomUUID().toString(),
                accountId = "pending",
                platform = SocialPlatform.TELEGRAM,
                content = "📣 **$name is officially live!**\n\n$objective.\n\nJoin the discussion and test out the new capabilities today in our community!",
                status = PostStatus.DRAFT,
                scheduledPublishTime = startDate + dayMillis * 4 + 3600_000L * 6,
                campaignId = campaignId,
                contentType = ContentType.COMMUNITY,
                requiresApproval = true
            )
        )

        val campaign = SocialCampaign(
            id = campaignId,
            name = name,
            objective = objective,
            startDate = startDate,
            endDate = endDate,
            platforms = platforms,
            targetAudience = "Android developers, power users, and open-source AI community",
            strategySummary = "Sequential announcement pipeline building momentum from X teaser to in-depth LinkedIn architecture and Telegram community feedback.",
            status = CampaignStatus.PLANNING,
            notes = "Target KPIs: 25K total reach, 5% average engagement rate."
        )

        return PlannedCampaignResult(
            campaign = campaign,
            plannedPosts = posts,
            targetGoals = listOf("25K Total Reach", "5% Average Engagement Rate", "500 Community Joins")
        )
    }
}
