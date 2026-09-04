package com.opendroid.ai.social.core.adapter

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

abstract class BaseSocialAdapter(
    protected val httpClient: OkHttpClient
) {
    protected val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    protected fun isSandboxToken(token: String): Boolean =
        token.startsWith("sandbox_") || token.startsWith("mock_") || token == "test_token"

    protected fun executeRequest(request: Request): Result<String> {
        return try {
            val response: Response = httpClient.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                Result.success(body)
            } else {
                val errorMsg = when (response.code) {
                    401 -> "Authentication expired or invalid token (HTTP 401)"
                    403 -> "Insufficient permissions or scope denied (HTTP 403)"
                    429 -> "Rate limit exceeded (HTTP 429). Please retry later."
                    in 500..599 -> "Platform server error (HTTP ${response.code})"
                    else -> "API error HTTP ${response.code}: $body"
                }
                Result.failure(IOException(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    protected fun parseJsonSafe(jsonString: String): JSONObject? {
        return try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            null
        }
    }
}
