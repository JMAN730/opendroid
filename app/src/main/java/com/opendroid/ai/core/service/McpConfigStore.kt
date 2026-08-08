package com.opendroid.ai.core.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class McpEndpointConfig(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: Map<String, String>
)

@Singleton
class McpConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun accessToken(): String {
        val existing = preferences.getString(TOKEN_KEY, null)
        if (existing != null) return existing
        return UUID.randomUUID().toString().replace("-", "").also {
            preferences.edit().putString(TOKEN_KEY, it).apply()
        }
    }

    @Synchronized
    fun list(): List<McpEndpointConfig> {
        val values = JSONArray(preferences.getString(ENDPOINTS_KEY, "[]"))
        return (0 until values.length()).mapNotNull { index ->
            values.optJSONObject(index)?.let { json ->
                val headers = json.optJSONObject("headers")
                McpEndpointConfig(
                    name = json.optString("name"),
                    url = json.optString("url"),
                    enabled = json.optBoolean("enabled", true),
                    headers = headers?.keys()?.asSequence()
                        ?.associateWith { key -> headers.optString(key) }
                        .orEmpty()
                )
            }
        }
    }

    @Synchronized
    fun upsert(config: McpEndpointConfig) {
        require(config.name.isNotBlank()) { "name is required" }
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "url must use http or https"
        }
        val values = JSONArray()
        list().filterNot { it.name == config.name }.plus(config).forEach { values.put(it.toJson()) }
        preferences.edit().putString(ENDPOINTS_KEY, values.toString()).apply()
    }

    @Synchronized
    fun remove(name: String) {
        val values = JSONArray()
        list().filterNot { it.name == name }.forEach { values.put(it.toJson()) }
        preferences.edit().putString(ENDPOINTS_KEY, values.toString()).apply()
    }

    private fun McpEndpointConfig.toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("url", url)
        .put("enabled", enabled)
        .put("headers", JSONObject(headers))

    private companion object {
        const val PREFERENCES = "opendroid_mcp"
        const val TOKEN_KEY = "access_token"
        const val ENDPOINTS_KEY = "endpoints"
    }
}
