package com.opendroid.ai.social.core.ai

import com.opendroid.ai.core.llm.LLMProviderFactory
import com.opendroid.ai.core.llm.LLMRequest
import com.opendroid.ai.data.models.ChatMessage
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import com.opendroid.ai.social.domain.model.ContentType
import com.opendroid.ai.social.domain.model.SocialPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class Tone {
    PROFESSIONAL,
    CASUAL,
    EXCITED,
    TECHNICAL,
    MINIMAL
}

data class GeneratedPlatformContent(
    val platform: SocialPlatform,
    val content: String,
    val hashtags: List<String>,
    val cta: String?,
    val characterCount: Int
)

@Singleton
class SocialContentComposer @Inject constructor(
    private val llmProviderFactory: LLMProviderFactory,
    private val memoryRepository: MemoryRepository
) {

    suspend fun generateContentForPlatform(
        topic: String,
        platform: SocialPlatform,
        tone: Tone = Tone.PROFESSIONAL,
        contentType: ContentType = ContentType.ANNOUNCEMENT
    ): GeneratedPlatformContent = withContext(Dispatchers.Default) {
        val styleMemory = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
            .find { it.key == "social_preferred_writing_style" }?.value
        val brandMemory = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
            .find { it.key == "social_brand_voice" }?.value
        val preferredHashtags = memoryRepository.getMemoriesByType(MemoryType.SEMANTIC)
            .find { it.key == "social_frequently_used_hashtags" }?.value

        val platformConstraints = when (platform) {
            SocialPlatform.X -> "STRICT LIMIT: Keep under 280 characters. Crisp hook, bullet points, 1-2 hashtags. If thread needed, format as 1/3, 2/3."
            SocialPlatform.INSTAGRAM -> "Format as Instagram caption: Visually appealing opening hook line, body with double-spaced paragraphs, strong Call To Action at end, followed by 5-8 relevant hashtags."
            SocialPlatform.LINKEDIN -> "Format as a LinkedIn post: Executive, professional, problem-and-solution narrative, clear technical/business value proposition, engaging question to drive comments, 3 industry hashtags."
            SocialPlatform.TELEGRAM -> "Format as Telegram channel announcement: Engaging community tone, rich markdown formatting (*bold*, _italic_), rocket and announcement emojis, direct CTA link."
            SocialPlatform.DISCORD -> "Format as Discord community update: Markdown header (# or ##), key bullet points with emojis, @here or @everyone context, call for feedback and testing."
            SocialPlatform.FACEBOOK -> "Format as Facebook post: Conversational storytelling, relatable tone, clear user benefit, question to invite comments."
            SocialPlatform.YOUTUBE -> "Format as YouTube video community post: Video teaser headline, bullet points of key moments covered, link CTA."
        }

        val prompt = """
            You are an expert Social Media AI Manager for OpenDroid, an autonomous open-source AI device assistant for Android.
            Generate a social media post for ${platform.displayName}.
            
            Topic: "$topic"
            Tone: ${tone.name}
            Content Type: ${contentType.name}
            ${if (!styleMemory.isNullOrBlank()) "User Preferred Style: $styleMemory" else ""}
            ${if (!brandMemory.isNullOrBlank()) "Brand Voice: $brandMemory" else ""}
            ${if (!preferredHashtags.isNullOrBlank()) "Frequently Used Hashtags: $preferredHashtags" else ""}
            
            Platform Requirements:
            $platformConstraints
            
            Output strictly the exact post text. Do not wrap in markdown quotes or preamble.
        """.trimIndent()

        val generatedText = try {
            val provider = llmProviderFactory.getActiveProvider()
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a professional social media content creator specializing in tech and AI.",
                    messages = listOf(
                        ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    )
                )
            )
            response.content.trim().trim('"', '`')
        } catch (e: Exception) {
            fallbackGeneration(topic, platform, tone, contentType)
        }

        val hashtags = Regex("#\\w+").findAll(generatedText).map { it.value }.toList()
        GeneratedPlatformContent(
            platform = platform,
            content = generatedText,
            hashtags = hashtags,
            cta = extractCta(generatedText),
            characterCount = generatedText.length
        )
    }

    suspend fun shorten(content: String, platform: SocialPlatform): String = withContext(Dispatchers.Default) {
        val prompt = "Shorten the following ${platform.displayName} post while preserving its key message, hook, and CTA. Make it more concise:\n\n$content"
        callLlmOrFallback(prompt) {
            content.lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
        }
    }

    suspend fun expand(content: String, platform: SocialPlatform): String = withContext(Dispatchers.Default) {
        val prompt = "Expand the following ${platform.displayName} post with more detail, context, and persuasive depth without adding fluff:\n\n$content"
        callLlmOrFallback(prompt) {
            "$content\n\nKey Highlights:\n• Autonomous execution\n• Privacy-first on-device architecture\n• Open source and customizable\n\nWhat feature would you like to see next?"
        }
    }

    suspend fun changeTone(content: String, targetTone: Tone, platform: SocialPlatform): String = withContext(Dispatchers.Default) {
        val prompt = "Rewrite the following ${platform.displayName} post to have a distinctly ${targetTone.name} tone:\n\n$content"
        callLlmOrFallback(prompt) {
            when (targetTone) {
                Tone.PROFESSIONAL -> "We are pleased to share the latest milestone: $content"
                Tone.CASUAL -> "Hey everyone! Quick update on what we've been working on: $content"
                Tone.EXCITED -> "🚀 Big news! You asked for it and it's finally here: $content 🎉"
                Tone.TECHNICAL -> "[Update Architecture] Technical changelog breakdown: $content"
                Tone.MINIMAL -> content.take(120)
            }
        }
    }

    suspend fun addCta(content: String, ctaType: String, platform: SocialPlatform): String {
        val cta = when (ctaType.lowercase()) {
            "download" -> "\n\n👉 Try OpenDroid today: https://github.com/JMAN730/opendroid"
            "feedback" -> "\n\n💬 Drop your thoughts and suggestions below!"
            "star" -> "\n\n⭐ Star our GitHub repo to support open-source AI!"
            else -> "\n\n🚀 Learn more at https://github.com/JMAN730/opendroid"
        }
        return if (content.contains(cta.trim())) content else content + cta
    }

    fun removeHashtags(content: String): String {
        return content.replace(Regex("#\\w+"), "").replace(Regex(" +"), " ").trim()
    }

    suspend fun translate(content: String, targetLanguage: String): String = withContext(Dispatchers.Default) {
        val prompt = "Translate the following social media post into $targetLanguage accurately, maintaining social media formatting and tone:\n\n$content"
        callLlmOrFallback(prompt) { content }
    }

    private suspend fun callLlmOrFallback(prompt: String, fallback: () -> String): String {
        return try {
            val provider = llmProviderFactory.getActiveProvider()
            val response = provider.complete(
                LLMRequest(
                    systemPrompt = "You are a professional social media content editor.",
                    messages = listOf(
                        ChatMessage(
                            id = java.util.UUID.randomUUID().toString(),
                            text = prompt,
                            sender = ChatMessage.Sender.USER
                        )
                    )
                )
            )
            response.content.trim().trim('"', '`')
        } catch (e: Exception) {
            fallback()
        }
    }

    private fun extractCta(text: String): String? {
        val ctaKeywords = listOf("link in bio", "tap link", "download", "check out", "comment below", "star", "learn more")
        return text.lines().lastOrNull { line ->
            ctaKeywords.any { line.contains(it, ignoreCase = true) }
        }
    }

    private fun fallbackGeneration(
        topic: String,
        platform: SocialPlatform,
        tone: Tone,
        contentType: ContentType
    ): String {
        return when (platform) {
            SocialPlatform.X -> "🚀 $topic\n\nOpenDroid brings autonomous AI agents to Android with pure OLED dark theme & local privacy.\n\n#OpenDroid #AndroidDev #AI"
            SocialPlatform.INSTAGRAM -> "✨ $topic\n\nExcited to announce our latest development with OpenDroid! Your device, your AI assistant, completely privacy-first.\n\n👇 Tap link in bio to learn more!\n\n#OpenDroid #Android #ArtificialIntelligence #TechUpdate #OpenSource"
            SocialPlatform.LINKEDIN -> "We are excited to announce: $topic.\n\nOpenDroid delivers autonomous on-device mobile AI workflows with zero compromise on user privacy and security.\n\nWhat are your thoughts on agentic workflows on mobile?\n\n#AI #Android #MobileEngineering #OpenSource"
            SocialPlatform.TELEGRAM -> "📣 *$topic*\n\nHey OpenDroid community! We've rolled out major enhancements including unified social management and classic monochrome themes.\n\n👉 Check out the full repository: https://github.com/JMAN730/opendroid"
            SocialPlatform.DISCORD -> "📢 **$topic**\n\nHey @everyone! The latest OpenDroid build is live with major autonomous improvements.\n• Privacy-first architecture\n• AI Social Manager\n\nDrop your feedback in #feedback!"
            SocialPlatform.FACEBOOK -> "Exciting news! $topic.\n\nOpenDroid continues to evolve as your personal autonomous Android assistant. Have you tried it yet?"
            SocialPlatform.YOUTUBE -> "In this update: $topic.\n\nWatch how OpenDroid automates tasks and manages social media directly from your phone. Subscribe for weekly updates!"
        }
    }
}
