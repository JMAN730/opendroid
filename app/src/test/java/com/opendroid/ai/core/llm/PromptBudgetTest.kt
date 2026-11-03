package com.opendroid.ai.core.llm

import com.opendroid.ai.core.llm.prompts.PlanningPrompts
import com.opendroid.ai.data.models.ChatMessage
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBudgetTest {

    @Test
    fun `estimateTokens uses 3 ASCII chars per token rounded up`() {
        assertEquals(0, PromptBudget.estimateTokens(""))
        assertEquals(1, PromptBudget.estimateTokens("hi"))
        assertEquals(2, PromptBudget.estimateTokens("abcd"))
        assertEquals(2, PromptBudget.estimateTokens("abcde"))
        assertEquals(34, PromptBudget.estimateTokens("x".repeat(100)))
    }

    @Test
    fun `estimateTokens counts every digit and non-ASCII char as a token`() {
        assertEquals(4, PromptBudget.estimateTokens("1234"))
        assertEquals(2, PromptBudget.estimateTokens("नम"))
        assertEquals(2, PromptBudget.estimateTokens("😀"))
        // The space between non-ASCII words is a token of its own.
        assertEquals(3, PromptBudget.estimateTokens("न म"))
    }

    @Test
    fun `estimateTokens clears the counts #110 measured at the same lengths`() {
        // #110 did not record its strings, so these same-length stand-ins were not the strings
        // measured and this is no bound on them. The next test checks each string against itself.
        assertEquals(93, HINDI_93.length)
        assertEquals(83, DIGITS_83.length)
        assertEquals(51, ZH_51.length)
        mapOf(HINDI_93 to 82, DIGITS_83 to 55, ZH_51 to 29).forEach { (text, measured) ->
            val estimate = PromptBudget.estimateTokens(text)
            assertTrue("estimate $estimate < measured $measured for: $text", estimate >= measured)
        }
    }

    @Test
    fun `estimateTokens covers the Qwen pre-tokenizer pieces of the same string`() {
        listOf(
            HINDI_93, DIGITS_83, ZH_51, RUSSIAN, EMOJI, JSON, ENGLISH,
            "1,".repeat(500), "3 ".repeat(500), "1 - ".repeat(200), "a1".repeat(300),
            "2026-11-02, ".repeat(100), "192.168.0.1 ".repeat(100), "3f9a0c7e ".repeat(100),
            "550e8400-e29b-41d4-a716-446655440000 ".repeat(30), "at 10:30 am, ".repeat(80).trimEnd()
        ).forEach { text ->
            val estimate = PromptBudget.estimateTokens(text)
            val pieces = qwenPieces(text)
            assertTrue("estimate $estimate < $pieces pieces for: ${text.take(40)}", estimate >= pieces)
        }
    }

    @Test
    fun `a guard-approved digit-heavy prompt fits the window in real tokens`() {
        val chatHeader = "System Instructions:\n" + "x".repeat(918) + "\n\n" // ~306 tokens, like the chat prompt
        listOf(chatHeader, HEADER).forEach { header ->
            listOf("1,".repeat(1500), "3 ".repeat(1500), "2026-11-02, ".repeat(250)).forEach { text ->
                val prompt = fittedPrompt(header, listOf(message("1", text)))
                val output = PromptBudget.outputBudget(prompt, 1280, 512)
                assertNotNull("guard rejected a fitted prompt", output)
                val used = qwenPieces(prompt) + 32 + output!!
                assertTrue("$used real tokens overflow 1280 for: ${text.take(12)}", used <= 1280)
            }
        }
    }

    @Test
    fun `estimateTokens covers the measured real to chars-over-4 ratios`() {
        // Real / ceil(len / 4), measured with Qwen's BPE in #110.
        mapOf(RUSSIAN to 1.4, EMOJI to 1.7, JSON to 1.2).forEach { (text, ratio) ->
            val real = (text.length + 3) / 4 * ratio
            val estimate = PromptBudget.estimateTokens(text)
            assertTrue("estimate $estimate < real $real for: $text", estimate >= real)
        }
    }

    @Test
    fun `estimateTokens stays within 1_5x of real tokens for English prose`() {
        // English prose measured at 0.9x chars/4.
        val real = (ENGLISH.length + 3) / 4 * 0.9
        val estimate = PromptBudget.estimateTokens(ENGLISH)
        assertTrue("estimate $estimate < real $real", estimate >= real)
        assertTrue("estimate $estimate over-trims against real $real", estimate <= real * 1.5)
    }

    @Test
    fun `outputBudget returns requested tokens when prompt leaves room`() {
        // 100 chars ~= 34 tokens; context 1280 leaves plenty for 500 output tokens
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(100),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertEquals(500, budget)
    }

    @Test
    fun `outputBudget clamps requested tokens to remaining context`() {
        // 3000 chars = 1000 tokens, plus 10% and 32 template tokens = 1132; 148 left for output
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(3000),
            contextWindow = 1280,
            requestedOutputTokens = 1500
        )
        assertEquals(148, budget)
    }

    @Test
    fun `outputBudget returns null when prompt fills the context window`() {
        // 6000 chars = 2000 tokens > 1280 context: nothing left for output
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(6000),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `outputBudget returns null when remaining space is below the minimum useful output`() {
        // 3321 chars = 1107 tokens, plus 111 and 32 = 1250; only 30 left, below MIN_OUTPUT_TOKENS
        val budget = PromptBudget.outputBudget(
            prompt = "x".repeat(3321),
            contextWindow = 1280,
            requestedOutputTokens = 500
        )
        assertNull(budget)
    }

    @Test
    fun `outputBudget rejects the Hindi chat messages that overflow Qwen`() {
        // #110: 1200 chars used to pass with the full 500 output tokens; 2112 overflows on prefill.
        listOf(1200, 2112).forEach { chars ->
            assertNull(
                "$chars Hindi chars must not pass",
                PromptBudget.outputBudget("ह".repeat(chars), 1280, 500)
            )
        }
    }

    @Test
    fun `outputBudget counts template tokens and a margin on top of the estimate`() {
        // 900 chars = 300 tokens; 300 + 30 + 32 = 362 of a 500 window leaves 138.
        assertEquals(138, PromptBudget.outputBudget("x".repeat(900), 500, 400))
    }

    @Test
    fun `registry qwen spec carries its ekv1280 context window`() {
        val qwen = OnDeviceModelRegistry.findById("qwen-2.5-0.5b-it-litert")!!
        assertEquals(1280, qwen.contextWindow)
    }

    @Test
    fun `fitMessagesToBudget retains latest message and prunes oldest when budget exceeded`() {
        val msg1 = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "Oldest message ".repeat(30), // 450 chars = 150 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )
        val msg2 = com.opendroid.ai.data.models.ChatMessage(
            id = "2",
            text = "Middle message ".repeat(30), // 450 chars = 150 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.AGENT
        )
        val msg3 = com.opendroid.ai.data.models.ChatMessage(
            id = "3",
            text = "Latest user query",
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        // Context window 500, overhead 100, reserved output 128 -> Available for messages = 198 tokens
        // msg3 (10 tokens) + msg2 (154 tokens) = 164 tokens -> fits!
        // msg1 cannot fit in remaining 34 tokens -> pruned!
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(msg1, msg2, msg3),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(2, fitted.size)
        assertEquals("2", fitted[0].id)
        assertEquals("3", fitted[1].id)
    }

    @Test
    fun `fitMessagesToBudget truncates latest message if it alone exceeds budget`() {
        val massiveLatest = com.opendroid.ai.data.models.ChatMessage(
            id = "1",
            text = "word ".repeat(1000), // 5000 chars ~= 1667 tokens
            sender = com.opendroid.ai.data.models.ChatMessage.Sender.USER
        )

        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(massiveLatest),
            contextWindow = 500,
            reservedOutputTokens = 128,
            overheadTokens = 100
        )

        assertEquals(1, fitted.size)
        // The latest message may use the output reservation down to MIN_OUTPUT_TOKENS: 252 tokens
        assertTrue(
            "Expected message to be truncated to fit budget",
            PromptBudget.estimateTokens(fitted[0].text) <= 252
        )
    }

    @Test
    fun `fitMessagesToBudget trims non-Latin latest messages until the prompt guard passes`() {
        listOf("ह".repeat(2112), "中".repeat(2112), "😀".repeat(1056), "7".repeat(2112))
            .forEach { text ->
                val prompt = fittedPrompt(HEADER, listOf(message("1", text)))
                assertNotNull("guard rejected a fitted prompt", PromptBudget.outputBudget(prompt, 1280, 512))
            }
    }

    @Test
    fun `fitMessagesToBudget prunes non-Latin history until the prompt guard passes`() {
        val history = (1..10).map { message("$it", "ह".repeat(300)) }
        val prompt = fittedPrompt(HEADER, history)
        assertTrue("Expected history to be pruned", prompt.length < history.sumOf { it.text.length })
        assertNotNull("guard rejected a fitted prompt", PromptBudget.outputBudget(prompt, 1280, 512))
    }

    @Test
    fun `the on-device planning prompt keeps its context and an English request on Qwen`() {
        // AgentLoop.generatePlan appends up to 250 chars of context to this prompt.
        val context = PLANNING_CONTEXT.take(250)
        val systemPrompt = "${PlanningPrompts.buildOnDevicePlanningPrompt()}\n\nContext:\n$context"
        assertEquals(systemPrompt, PromptBudget.trimSystemPrompt(systemPrompt, 1280))

        val header = "System Instructions:\n$systemPrompt\n\n"
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(message("1", ENGLISH)),
            contextWindow = 1280,
            reservedOutputTokens = 512,
            overheadTokens = PromptBudget.estimateTokens(header)
        )
        assertEquals(ENGLISH, fitted.single().text)
        assertNotNull(
            "guard rejected the planning prompt",
            PromptBudget.outputBudget(fittedPrompt(header, fitted), 1280, 512)
        )
    }

    @Test
    fun `trimSystemPrompt keeps the ASCII length the old cap allowed`() {
        assertEquals("x".repeat(2559), PromptBudget.trimSystemPrompt("x".repeat(2559), 1280))
        assertTrue(PromptBudget.trimSystemPrompt("x".repeat(2600), 1280).startsWith("x".repeat(2559)))
        assertTrue(PromptBudget.trimSystemPrompt("x".repeat(2600), 1280).endsWith("on-device context]"))
    }

    @Test
    fun `trimSystemPrompt leaves a non-Latin prompt at its cap room to pass the guard`() {
        val trimmed = PromptBudget.trimSystemPrompt("ह".repeat(2000), 1280)
        assertTrue("Expected the prompt to be cut", trimmed.endsWith("on-device context]"))
        val prompt = fittedPrompt(
            "System Instructions:\n$trimmed\n\n",
            listOf(message("1", "ह".repeat(500)))
        )
        assertNotNull("guard rejected a fitted prompt", PromptBudget.outputBudget(prompt, 1280, 512))
    }

    @Test
    fun `fitMessagesToBudget never splits a surrogate pair when truncating`() {
        val original = "a" + "😀".repeat(2000)
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = listOf(message("1", original)),
            contextWindow = 500,
            reservedOutputTokens = 128
        )
        val text = fitted.single().text
        assertTrue("Expected message to be truncated", text.length < original.length)
        assertFalse("Truncation split a surrogate pair", text.first().isLowSurrogate())
    }

    @Test
    fun `takeFirstTokens cuts a non-Latin system prompt by tokens without splitting a pair`() {
        assertEquals("abc", PromptBudget.takeFirstTokens("abc", 1))
        assertEquals("abc", PromptBudget.takeFirstTokens("abcd", 1))
        assertEquals("हह", PromptBudget.takeFirstTokens("ह".repeat(5), 2))
        assertEquals("😀", PromptBudget.takeFirstTokens("😀😀", 3))
    }

    private fun message(id: String, text: String) =
        ChatMessage(id = id, text = text, sender = ChatMessage.Sender.USER)

    /** Assembles a prompt the way the on-device providers' buildPrompt does. */
    private fun fittedPrompt(header: String, messages: List<ChatMessage>): String {
        val fitted = PromptBudget.fitMessagesToBudget(
            messages = messages,
            contextWindow = 1280,
            reservedOutputTokens = 512,
            overheadTokens = PromptBudget.estimateTokens(header)
        )
        val sb = StringBuilder(header).append("Conversation History:\n")
        fitted.forEach { sb.append("User: ").append(it.text).append("\n") }
        return sb.append("Model:").toString()
    }

    /** Pieces Qwen's pre-tokenizer cuts [text] into: a lower bound on real tokens, as BPE only splits a piece further. */
    private fun qwenPieces(text: String): Int {
        val matcher = QWEN_PRE_TOKENIZER.matcher(text)
        var pieces = 0
        while (matcher.find()) pieces++
        return pieces
    }

    private companion object {
        /** PAT_STR from Qwen's tokenization_qwen.py. */
        val QWEN_PRE_TOKENIZER: Pattern = Pattern.compile(
            """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+""",
            Pattern.UNICODE_CHARACTER_CLASS
        )
        const val PLANNING_CONTEXT =
            "[Current Date & Time]\nDate: Monday, November 2, 2026\nTime: 1:24 AM\n\n" +
                "[Facts about User]\nUser Name: Alex; User Date of Birth (DOB): 1990-01-01; " +
                "home: 12 Elm Street, Boston; work: Lakeside School, room 204; phone: 415-555-0132\n\n" +
                "[Working Memory]\nNo active plan.\n"
        const val HINDI_93 =
            "नमस्ते आप कैसे हैं? मुझे कल सुबह दिल्ली जाने के लिए ट्रेन का टिकट बुक करना है, कृपया मदद करें"
        const val DIGITS_83 =
            "Call 415-555-0132 or 212-555-0187, ref 20261102-7741, sum 1299.50, room 4471, id 88"
        const val ZH_51 = "请帮我预订明天早上去北京的火车票，并把出发时间和座位号发送到我的手机上，谢谢你的帮助。我下午还要开会。"
        const val RUSSIAN = "Здравствуйте, забронируйте, пожалуйста, билет на поезд до Москвы на завтрашнее утро."
        const val EMOJI = "😀🎉🚀❤️👍🔥"
        const val JSON = """{"toolCall":{"name":"open_app","arguments":{"package":"com.example","flags":["a","b"]}}}"""
        const val ENGLISH =
            "She sells seashells by the seashore, and the shells she sells are surely seashells. " +
                "She sells seashells by the seashore, and the shells she sells are surely seashells. " +
                "She sells seashells by the seashore, and the shells she sells are surely seashells. "
        const val HEADER = "System Instructions:\nYou are an autonomous AI agent for Android.\n\n"
    }
}
