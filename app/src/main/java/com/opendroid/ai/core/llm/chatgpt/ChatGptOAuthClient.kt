package com.opendroid.ai.core.llm.chatgpt

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A ChatGPT sign-in failure. [message] is always written here, never copied from a response,
 * so it is safe to show and log. [httpStatus] is the authorization server's status, if any.
 */
class ChatGptAuthException(message: String, val httpStatus: Int? = null) : IOException(message)

/** One poll of a pending device login. */
sealed interface DevicePollResult {
    data object Pending : DevicePollResult
    data class Approved(val session: ChatGptSession) : DevicePollResult
    data class Failed(val message: String) : DevicePollResult
}

/**
 * The OAuth device-code flow against auth.openai.com: request a user code, poll until the user
 * approves it in a browser, exchange the resulting authorization code, and refresh tokens later.
 */
@Singleton
class ChatGptOAuthClient internal constructor(
    private val client: OkHttpClient,
    private val issuer: String,
    private val nowMillis: () -> Long
) {
    @Inject
    constructor(client: OkHttpClient) : this(client, ChatGptProtocol.ISSUER, System::currentTimeMillis)

    private val gson = Gson()
    private val jsonType = "application/json".toMediaType()

    suspend fun startDeviceLogin(): DeviceLoginStart {
        val expiresAt = nowMillis() + ChatGptProtocol.FLOW_TTL_MILLIS
        val response = post(
            ChatGptProtocol.USER_CODE_PATH,
            json(mapOf("client_id" to ChatGptProtocol.CLIENT_ID))
        )
        if (!response.ok) {
            throw ChatGptAuthException(
                "OpenAI device authorization is unavailable (HTTP ${response.status}).",
                response.status
            )
        }
        val data = response.data
        val deviceAuthId = data?.string("device_auth_id")
        val userCode = data?.string("user_code") ?: data?.string("usercode")
        if (deviceAuthId == null || userCode == null) {
            throw ChatGptAuthException("OpenAI returned an unexpected device authorization response.")
        }
        val interval = data?.get("interval")?.let(::numberOrNull)
            ?: ChatGptProtocol.DEFAULT_POLL_INTERVAL_SECONDS.toDouble()
        val intervalSeconds = interval.coerceIn(
            ChatGptProtocol.MIN_POLL_INTERVAL_SECONDS.toDouble(),
            ChatGptProtocol.MAX_POLL_INTERVAL_SECONDS.toDouble()
        )
        return DeviceLoginStart(
            userCode = userCode,
            verificationUrl = issuer + ChatGptProtocol.VERIFICATION_PATH,
            intervalMillis = (intervalSeconds * 1_000).toLong(),
            expiresAtMillis = expiresAt,
            deviceAuthId = deviceAuthId
        )
    }

    /**
     * Checks once whether the user approved [start]. A network failure, or the server's
     * 403/404 "not yet approved" answer, is [DevicePollResult.Pending]; the caller polls again.
     */
    suspend fun pollDeviceLogin(start: DeviceLoginStart): DevicePollResult {
        if (nowMillis() >= start.expiresAtMillis) return EXPIRED
        val response = try {
            post(
                ChatGptProtocol.DEVICE_TOKEN_PATH,
                json(mapOf("device_auth_id" to start.deviceAuthId, "user_code" to start.userCode))
            )
        } catch (_: ChatGptAuthException) {
            return DevicePollResult.Pending
        }
        if (response.status == 403 || response.status == 404) return DevicePollResult.Pending
        if (!response.ok) {
            return DevicePollResult.Failed("Device authorization failed (HTTP ${response.status}).")
        }
        val code = response.data?.string("authorization_code")
        val verifier = response.data?.string("code_verifier")
        if (code == null || verifier == null) {
            return DevicePollResult.Failed("OpenAI returned an unexpected device token response.")
        }
        val session = try {
            requestTokens(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("code_verifier", verifier)
                    .add("redirect_uri", issuer + ChatGptProtocol.DEVICE_CALLBACK_PATH)
                    .add("client_id", ChatGptProtocol.CLIENT_ID)
                    .build(),
                previous = null
            )
        } catch (_: ChatGptAuthException) {
            return DevicePollResult.Failed("Token exchange failed. Start again.")
        }
        if (nowMillis() >= start.expiresAtMillis) return EXPIRED
        return DevicePollResult.Approved(session)
    }

    /** Exchanges [current]'s refresh token. Account details the response omits are kept. */
    suspend fun refresh(current: ChatGptSession): ChatGptSession = requestTokens(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", current.refresh)
            .add("client_id", ChatGptProtocol.CLIENT_ID)
            .build(),
        previous = current
    )

    private suspend fun requestTokens(body: RequestBody, previous: ChatGptSession?): ChatGptSession {
        val response = post(ChatGptProtocol.OAUTH_TOKEN_PATH, body)
        if (!response.ok) {
            throw ChatGptAuthException(
                "OpenAI token request failed (HTTP ${response.status}). Reconnect ChatGPT in Settings.",
                response.status
            )
        }
        val data = response.data
        val access = data?.string("access_token")
        // A refresh response may omit the refresh token, meaning the current one stays valid.
        // The initial code exchange must return one.
        val refresh = data?.string("refresh_token") ?: previous?.refresh
        if (access == null || refresh == null) {
            throw ChatGptAuthException("OpenAI token response was missing tokens.")
        }
        val idClaims = ChatGptTokenClaims.parse(data?.string("id_token"))
        val accessClaims = ChatGptTokenClaims.parse(access)
        val lifetimeSeconds = data?.get("expires_in")?.let(::numberOrNull)
            ?.takeIf { it >= 0 }
            ?.toLong()
        val expiresAt = when {
            lifetimeSeconds != null -> nowMillis() + lifetimeSeconds.coerceAtMost(MAX_LIFETIME_SECONDS) * 1_000
            accessClaims.expiresAtMillis != null -> accessClaims.expiresAtMillis
            else -> nowMillis() + ChatGptProtocol.DEFAULT_TOKEN_LIFETIME_SECONDS * 1_000
        }
        return ChatGptSession(
            access = access,
            refresh = refresh,
            expiresAt = expiresAt,
            accountId = idClaims.accountId ?: accessClaims.accountId ?: previous?.accountId,
            planType = idClaims.planType ?: accessClaims.planType ?: previous?.planType
        )
    }

    private suspend fun post(path: String, body: RequestBody): AuthResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(issuer + path)
            .header("User-Agent", ChatGptProtocol.AUTH_USER_AGENT)
            .header("originator", ChatGptProtocol.AUTH_ORIGINATOR)
            .header("Accept", "application/json")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                // Upstream error bodies are never read: they are not shown, logged, or parsed.
                val data = if (response.isSuccessful) parseObject(response.peekBody(MAX_BODY_BYTES).string()) else null
                AuthResponse(response.code, response.isSuccessful, data)
            }
        } catch (_: IOException) {
            throw ChatGptAuthException("OpenAI authorization request failed or timed out. Try again.")
        }
    }

    private fun json(values: Map<String, String>): RequestBody = gson.toJson(values).toRequestBody(jsonType)

    private class AuthResponse(val status: Int, val ok: Boolean, val data: JsonObject?)

    private companion object {
        val EXPIRED = DevicePollResult.Failed("This login request expired. Start again.")
        const val MAX_BODY_BYTES = 256L * 1024
        /** Bounds a nonsensical expires_in so expiresAt cannot overflow. */
        const val MAX_LIFETIME_SECONDS = 365L * 24 * 3_600

        fun parseObject(body: String): JsonObject? = try {
            JsonParser.parseString(body).takeIf(JsonElement::isJsonObject)?.asJsonObject
        } catch (_: Exception) {
            null
        }

        fun JsonObject.string(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.takeIf(String::isNotBlank)

        /** Accepts a JSON number or a numeric string, as `interval` has been sent both ways. */
        fun numberOrNull(element: JsonElement): Double? {
            if (!element.isJsonPrimitive) return null
            val primitive = element.asJsonPrimitive
            val value = when {
                primitive.isNumber -> runCatching { primitive.asDouble }.getOrNull()
                primitive.isString -> primitive.asString.trim().toDoubleOrNull()
                else -> null
            }
            return value?.takeIf(Double::isFinite)
        }
    }
}
