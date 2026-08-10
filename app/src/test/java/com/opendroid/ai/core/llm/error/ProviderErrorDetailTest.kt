package com.opendroid.ai.core.llm.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderErrorDetailTest {

    @Test
    fun `promotes only safe vendor tokens from an OpenAI error body`() {
        val knownKey = "sk-invalid-secret-value-6789"
        val rawBody = """
            {
              "error": {
                "message": "Prompt PROMPT_CANARY failed at https://secret.example/v1 for sk-inval**********6789",
                "type": "invalid_request_error",
                "code": "invalid_api_key"
              }
            }
        """.trimIndent()

        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            httpStatus = 401,
            rawBody = rawBody,
            knownSecrets = listOf(knownKey),
            forbiddenText = listOf("PROMPT_CANARY", "https://secret.example/v1")
        )

        assertEquals(ProviderErrorDetail.Provider.OPENAI, detail.provider)
        assertEquals(401, detail.httpStatus)
        assertEquals("invalid_request_error", detail.vendorType)
        assertEquals("invalid_api_key", detail.vendorCode)
        assertEquals(
            "OpenAI request failed with HTTP 401 (type=invalid_request_error, code=invalid_api_key).",
            detail.message
        )

        val observableText = detail.toString() + detail.message
        listOf(
            rawBody,
            "PROMPT_CANARY",
            "https://secret.example/v1",
            knownKey,
            "sk-inval",
            "6789"
        ).forEach { forbidden ->
            assertFalse("$forbidden survived in $observableText", observableText.contains(forbidden))
        }
    }

    @Test
    fun `rejects prompt and known key fragments disguised as vendor tokens`() {
        val promptCanary = "PROMPT_CANARY"
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.OPENAI,
            httpStatus = 401,
            rawBody = """
                {
                  "error": {
                    "type": "$promptCanary",
                    "code": "tail6789"
                  }
                }
            """.trimIndent(),
            knownSecrets = listOf("sk-invalid-secret-value-6789"),
            forbiddenText = listOf(promptCanary)
        )

        assertEquals(null, detail.vendorType)
        assertEquals(null, detail.vendorCode)
        assertEquals("OpenAI request failed with HTTP 401.", detail.message)
    }

    @Test
    fun `rejects four character fragments of an unprefixed known key`() {
        val detail = ProviderErrorDetail.fromHttpFailure(
            provider = ProviderErrorDetail.Provider.COHERE,
            httpStatus = 401,
            rawBody = """{"type":"Ab1C","code":"6789"}""",
            knownSecrets = listOf("Ab1Cd2Ef3Gh4Ij5Kl6Mn6789"),
            forbiddenText = emptyList()
        )

        assertEquals(null, detail.vendorType)
        assertEquals(null, detail.vendorCode)
        assertEquals("Cohere request failed with HTTP 401.", detail.message)
    }
}
