package com.opendroid.ai.core.service

import android.content.Context
import android.content.SharedPreferences
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretEnvelope
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class McpEndpointConfig(
    val name: String,
    val url: String,
    val enabled: Boolean,
    val headers: Map<String, String>
)

@Serializable
private data class StoredMcpEndpoint(
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Persists the MCP loopback access token and remote endpoint configs as
 * Keystore AES-GCM envelopes (same boundary as [com.opendroid.ai.core.security.ProviderCredentialStore]).
 */
@Singleton
class McpConfigStore private constructor(
    private val records: KeystoreSecretRecords,
    private val preferences: SharedPreferences
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        records = KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(
                context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            ),
            cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS)
        ),
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    )

    @Synchronized
    fun accessToken(): String {
        readSecret(TOKEN_KEY, TOKEN_AAD)?.takeIf { it.isNotEmpty() }?.let { return it }
        val token = UUID.randomUUID().toString().replace("-", "")
        writeSecret(TOKEN_KEY, TOKEN_AAD, token)
        return token
    }

    @Synchronized
    fun list(): List<McpEndpointConfig> {
        val raw = readSecret(ENDPOINTS_KEY, ENDPOINTS_AAD) ?: "[]"
        return try {
            json.decodeFromString<List<StoredMcpEndpoint>>(raw).map { stored ->
                McpEndpointConfig(
                    name = stored.name,
                    url = stored.url,
                    enabled = stored.enabled,
                    headers = stored.headers
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun upsert(config: McpEndpointConfig) {
        require(config.name.isNotBlank()) { "name is required" }
        require(config.url.startsWith("http://") || config.url.startsWith("https://")) {
            "url must use http or https"
        }
        val updated = list().filterNot { it.name == config.name }.plus(config)
        writeSecret(ENDPOINTS_KEY, ENDPOINTS_AAD, encode(updated))
    }

    @Synchronized
    fun remove(name: String) {
        writeSecret(ENDPOINTS_KEY, ENDPOINTS_AAD, encode(list().filterNot { it.name == name }))
    }

    private fun encode(configs: List<McpEndpointConfig>): String =
        json.encodeToString(
            configs.map { config ->
                StoredMcpEndpoint(
                    name = config.name,
                    url = config.url,
                    enabled = config.enabled,
                    headers = config.headers
                )
            }
        )

    private fun readSecret(storageKey: String, aad: String): String? {
        when (val result = records.read(storageKey, aad)) {
            is SecretRecordResult.Success -> {
                val value = result.value ?: return migratePlaintext(storageKey, aad)
                return String(value, StandardCharsets.UTF_8)
            }
            SecretRecordResult.Unrecoverable -> return migratePlaintext(storageKey, aad)
            SecretRecordResult.StorageUnavailable ->
                error("MCP secret storage is unavailable for $storageKey")
        }
    }

    private fun writeSecret(storageKey: String, aad: String, plaintext: String) {
        when (
            records.write(storageKey, aad, plaintext.toByteArray(StandardCharsets.UTF_8))
        ) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable ->
                error("MCP secret key material is unrecoverable for $storageKey")
            SecretRecordResult.StorageUnavailable ->
                error("MCP secret storage is unavailable for $storageKey")
        }
    }

    /**
     * Upgrade values written by the initial unencrypted build into versioned
     * Keystore envelopes. Only migrates strings that are not already envelopes.
     */
    private fun migratePlaintext(storageKey: String, aad: String): String? {
        val raw = try {
            preferences.getString(storageKey, null)
        } catch (_: ClassCastException) {
            null
        } ?: return null
        if (SecretEnvelope.decode(raw) != null) return null
        writeSecret(storageKey, aad, raw)
        return raw
    }

    companion object {
        const val PREFERENCES = "opendroid_mcp"
        const val KEY_ALIAS = "opendroid.mcp_secrets.aes_gcm.v1"
        private const val TOKEN_KEY = "access_token"
        private const val ENDPOINTS_KEY = "endpoints"
        private const val TOKEN_AAD = "mcp-access-token"
        private const val ENDPOINTS_AAD = "mcp-endpoints"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** JVM test seam that keeps the production constructor Keystore-only. */
        internal fun createForTest(
            records: KeystoreSecretRecords,
            preferences: SharedPreferences
        ): McpConfigStore = McpConfigStore(records, preferences)
    }
}
