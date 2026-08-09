package com.opendroid.ai.core.llm

import com.opendroid.ai.core.agent.ActionRisk
import com.opendroid.ai.core.agent.ActionSchema
import com.opendroid.ai.data.models.LLMConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRoutingTest {
    @Test
    fun `advanced and irreversible actions are high risk without changing approval policy`() {
        assertEquals(ActionRisk.HIGH, ActionSchema.riskFor("READ_FILE"))
        assertEquals(ActionRisk.HIGH, ActionSchema.riskFor("DELETE_FILE"))
        assertEquals(ActionRisk.CRITICAL, ActionSchema.riskFor("PAY_UPI"))
        assertEquals(ActionRisk.CRITICAL, ActionSchema.riskFor("not-a-real-action"))
}
    @Test
    fun `high risk local plan uses only the explicitly configured available provider`() {
        val route = ProviderRoutingPolicy.chooseProvider(
            activeProvider = ProviderCatalog.ON_DEVICE,
            configuredFallback = "OpenAI",
            actionNames = listOf("DELETE_FILE"),
            fallbackAvailable = true
        )

        assertEquals(
            ProviderRoute.ExplicitFallback(
                activeProvider = ProviderCatalog.ON_DEVICE,
                fallbackProvider = "OpenAI",
                requiredRisk = ActionRisk.HIGH
            ),
            route
        )
    }

    @Test
    fun `high risk local plan is blocked when fallback is missing, same, or unavailable`() {
        listOf(null, ProviderCatalog.ON_DEVICE).forEach { fallback ->
            val route = ProviderRoutingPolicy.chooseProvider(
                activeProvider = ProviderCatalog.ON_DEVICE,
                configuredFallback = fallback,
                actionNames = listOf("PAY_UPI"),
                fallbackAvailable = true
            )
            assertTrue(route is ProviderRoute.Blocked)
        }

        val unavailable = ProviderRoutingPolicy.chooseProvider(
            activeProvider = ProviderCatalog.ON_DEVICE,
            configuredFallback = "OpenAI",
            actionNames = listOf("PAY_UPI"),
            fallbackAvailable = false
        )
        assertTrue(unavailable is ProviderRoute.Blocked)
    }

    @Test
    fun `low risk and already remote plans keep the active provider`() {
        val local = ProviderRoutingPolicy.chooseProvider(
            activeProvider = ProviderCatalog.ON_DEVICE,
            configuredFallback = "OpenAI",
            actionNames = listOf("GET_SYSTEM_INFO"),
            fallbackAvailable = true
        )
        val remote = ProviderRoutingPolicy.chooseProvider(
            activeProvider = "OpenAI",
            configuredFallback = ProviderCatalog.ON_DEVICE,
            actionNames = listOf("PAY_UPI"),
            fallbackAvailable = true
        )

        assertEquals(ProviderRoute.Active(ProviderCatalog.ON_DEVICE), local)
        assertEquals(ProviderRoute.Active("OpenAI"), remote)
    }

    @Test
    fun `authorization rejects empty credentials and incomplete custom endpoints`() {
        val missingKey = LLMConfig(
            activeProvider = ProviderCatalog.ON_DEVICE,
            fallbackProvider = "OpenAI"
        )
        assertFalse(ProviderAuthorization.isConfigured(missingKey, "OpenAI"))

        val missingEndpoint = LLMConfig(
            activeProvider = ProviderCatalog.ON_DEVICE,
            fallbackProvider = "Custom OpenAI Compatible",
            apiKeys = mapOf("Custom OpenAI Compatible" to "configured-key")
        )
        assertFalse(ProviderAuthorization.isConfigured(missingEndpoint, "Custom OpenAI Compatible"))

        val configured = missingEndpoint.copy(
            customEndpoints = mapOf("Custom OpenAI Compatible" to "https://llm.example.test")
        )
        assertTrue(ProviderAuthorization.isConfigured(configured, "Custom OpenAI Compatible"))
    }

    @Test
    fun `natural language inference catches advanced and financial requests`() {
        assertTrue(ActionSchema.inferRequestedActions("delete the file in Downloads").contains("DELETE_FILE"))
        assertTrue(ActionSchema.inferRequestedActions("pay 500 to John").contains("PAY_UPI"))
    }
}
