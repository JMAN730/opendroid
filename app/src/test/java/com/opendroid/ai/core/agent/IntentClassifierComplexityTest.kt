package com.opendroid.ai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `single read and remember request stays simple`() {
        assertEquals(QueryComplexity.SIMPLE, classifier.classifyComplexity("remember this screen"))
        assertEquals(
            QueryComplexity.SIMPLE,
            classifier.classifyComplexity("read this screen and save the important information to my notes")
        )
    }
}
