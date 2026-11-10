package com.opendroid.ai.core.llm.chatgpt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Fixed values of the ChatGPT sign-in and Codex backend contract.
 *
 * This is the same device-code flow and backend the Codex CLI uses. It is not a public OpenAI
 * API: OpenAI can change or withdraw it at any time, which is why every response shape here is
 * read defensively.
 */
object ChatGptProtocol {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ISSUER = "https://auth.openai.com"
    const val CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"

    const val USER_CODE_PATH = "/api/accounts/deviceauth/usercode"
    const val DEVICE_TOKEN_PATH = "/api/accounts/deviceauth/token"
    const val OAUTH_TOKEN_PATH = "/oauth/token"
    const val VERIFICATION_PATH = "/codex/device"
    const val DEVICE_CALLBACK_PATH = "/deviceauth/callback"

    /** Identifies OpenDroid to the authorization server. */
    const val AUTH_ORIGINATOR = "opendroid"
    const val AUTH_USER_AGENT = "opendroid/1.0"

    /** The Codex backend only serves its own client identity. */
    const val CODEX_ORIGINATOR = "codex_cli_rs"
    const val CODEX_USER_AGENT = "codex_cli_rs/1.0.0"
    const val CODEX_CLIENT_VERSION = "1.0.0"

    const val DEFAULT_MODEL = "gpt-5.4"

    /** Picked, in order, when the saved model is not in the account's live list. */
    val PREFERRED_MODELS = listOf("gpt-5.4", "gpt-5.6-sol", "gpt-5.5")

    /** A device login request is abandoned after this long. */
    const val FLOW_TTL_MILLIS = 15 * 60_000L
    const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
    const val MIN_POLL_INTERVAL_SECONDS = 1L
    const val MAX_POLL_INTERVAL_SECONDS = 60L

    /** Fallback lifetime when the token response states none. */
    const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600L

    /** Tokens are refreshed this long before they expire. */
    const val REFRESH_MARGIN_MILLIS = 60_000L
}

/**
 * The stored ChatGPT sign-in. Persisted only as an encrypted credential record; never part of
 * [com.opendroid.ai.data.models.LLMConfig], and [toString] never prints a token.
 */
@Serializable
data class ChatGptSession(
    val access: String,
    val refresh: String,
    val expiresAt: Long,
    val accountId: String? = null,
    val planType: String? = null
) {
    fun expiresWithin(marginMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= expiresAt - marginMillis

    override fun toString(): String =
        "ChatGptSession(<redacted>, expiresAt=$expiresAt, planType=$planType)"
}

/** What the user is shown and asked to enter on the verification page. */
data class DeviceLoginStart(
    val userCode: String,
    val verificationUrl: String,
    val intervalMillis: Long,
    val expiresAtMillis: Long,
    internal val deviceAuthId: String
) {
    override fun toString(): String =
        "DeviceLoginStart(userCode=$userCode, verificationUrl=$verificationUrl)"
}

/** Account claims read from an OpenAI id/access token. */
internal data class ChatGptClaims(val accountId: String?, val planType: String?, val expiresAtMillis: Long?)

internal object ChatGptTokenClaims {
    private const val AUTH_CLAIM = "https://api.openai.com/auth"

    /**
     * Reads the account id and plan from a JWT payload without verifying it: the token came
     * straight from the issuer over TLS and is only used to address the account, never to
     * authorize anything locally. Any malformed token yields empty claims, never an exception.
     */
    fun parse(token: String?): ChatGptClaims {
        val claims = payload(token) ?: return ChatGptClaims(null, null, null)
        val auth = claims.objectOrNull(AUTH_CLAIM)
        val accountId = claims.stringOrNull("chatgpt_account_id")
            ?: auth?.stringOrNull("chatgpt_account_id")
            ?: claims.arrayFirstObject("organizations")?.stringOrNull("id")
        val planType = auth?.stringOrNull("chatgpt_plan_type")
        val exp = claims.get("exp")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }
            ?.takeIf { it > 0 && it < Long.MAX_VALUE / 1_000 }
            ?.let { it * 1_000 }
        return ChatGptClaims(accountId, planType, exp)
    }

    private fun payload(token: String?): JsonObject? {
        val segment = token?.split('.')?.getOrNull(1)?.takeIf(String::isNotEmpty) ?: return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(segment.trimEnd('='))
            JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
                .takeIf(JsonElement::isJsonObject)?.asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf(String::isNotBlank)

    private fun JsonObject.arrayFirstObject(key: String): JsonObject? =
        get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
}
