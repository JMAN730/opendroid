package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IntentClassifier.classifyComplexity] is pure keyword logic; the LLM provider
 * is only reached by [IntentClassifier.requiresAction].
 */
class IntentClassifierComplexityTest {

    private val classifier = IntentClassifier(dagger.Lazy { error("LLM provider is not used by classifyComplexity") })

    @Test
    fun `remember this followed by a second task is not simple`() {
        assertNotEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("remember this and then text mom"))
        assertNotEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("remember this screen and then text mom"))
    }

    @Test
    fun `conditional sends are never simple`() {
        listOf(
            "If it's going to rain tomorrow, text my wife that I'll pick up the kids",
            "Text my wife that I'll pick up the kids if it rains tomorrow",
            "If my battery is below 15% text Mom that my phone is dying",
            "If I have no meetings this afternoon tell John I can meet",
            "Tell John I can meet unless I have meetings this afternoon",
            "Text Mom I'm home only if it's after 9",
            "Message my wife I'll be late in case the train is delayed",
            "Turn on the flashlight when it gets dark"
        ).forEach { query ->
            assertNotEquals(query, QueryComplexity.SIMPLE, classifier.classifyComplexity(query))
        }
    }

    @Test
    fun `conditional with several punctuated actions stays complex`() {
        assertEquals(
            QueryComplexity.COMPLEX,
            classifier.classifyComplexity(
                "If my battery is below 15%, open settings; set brightness to 10; turn off wifi; mute phone"
            )
        )
    }

    @Test
    fun `words that merely contain a conditional marker stay simple`() {
        assertEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("open spotify"))
        assertEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("call the gift shop"))
    }

    @Test
    fun `hasConditionalMarker matches whole words only`() {
        assertTrue(IntentClassifier.hasConditionalMarker("If it rains, text my wife"))
        assertTrue(IntentClassifier.hasConditionalMarker("tell John I can meet unless I'm busy"))
        assertFalse(IntentClassifier.hasConditionalMarker("open spotify"))
        assertFalse(IntentClassifier.hasConditionalMarker("call the gift shop"))
        assertFalse(IntentClassifier.hasConditionalMarker("when is my next meeting"))
    }

    @Test
    fun `single read and remember request stays simple`() {
        assertEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("remember this screen"))
        assertEquals(
            QueryComplexity.SIMPLE,
            classifier.classifyComplexity("read this screen and save the important information to my notes")
        )
    }
}
