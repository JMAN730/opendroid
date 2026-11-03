package com.opendroid.ai.core.service

import android.content.Context
import android.content.SharedPreferences
import com.opendroid.ai.core.security.AndroidKeyStoreAeadCipher
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretEnvelope
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.SharedPreferencesSecretRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** Recovery signal for callers that need to tell the user the MCP config must be set up again. */
sealed interface McpConfigRecoveryState {
    data object Ready : McpConfigRecoveryState
    data object SecretsMustBeReentered : McpConfigRecoveryState
}

/**
 * A stored MCP secret is an envelope that no longer decrypts. The store refuses to replace it
 * until [McpConfigStore.resetForReentry] is called.
 */
class McpSecretsMustBeReenteredException :
    IllegalStateException("Stored MCP secrets cannot be decrypted; reset the MCP configuration to continue")

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

    private val mutableRecoveryState = MutableStateFlow<McpConfigRecoveryState>(McpConfigRecoveryState.Ready)

    val recoveryState: StateFlow<McpConfigRecoveryState> = mutableRecoveryState

    // Storage keys whose envelope failed to decrypt on the last read.
    private val unreadableKeys = mutableSetOf<String>()

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
        requireReady()
        val updated = list().filterNot { it.name == config.name }.plus(config)
        writeSecret(ENDPOINTS_KEY, ENDPOINTS_AAD, encode(updated))
    }

    @Synchronized
    fun remove(name: String) {
        requireReady()
        writeSecret(ENDPOINTS_KEY, ENDPOINTS_AAD, encode(list().filterNot { it.name == name }))
    }

    /**
     * Drops the MCP key material, the access token and every endpoint so the user can pair and
     * configure again. Only the user should trigger this; it discards the stored endpoints.
     */
    @Synchronized
    fun resetForReentry() {
        if (records.resetKeyMaterial() !is SecretRecordResult.Success) {
            error("MCP secret key material could not be reset")
        }
        for (storageKey in listOf(TOKEN_KEY, ENDPOINTS_KEY)) {
            if (records.removeRecord(storageKey) !is SecretRecordResult.Success) {
                error("MCP secret storage is unavailable for $storageKey")
            }
        }
        unreadableKeys.clear()
        mutableRecoveryState.value = McpConfigRecoveryState.Ready
    }

    /**
     * Reads every stored secret once so [recoveryState] reflects the records as they are now,
     * without minting a token. Lets the settings screen offer the reset before any MCP request.
     */
    @Synchronized
    fun refreshRecoveryState(): McpConfigRecoveryState {
        for ((storageKey, aad) in SECRETS) {
            try {
                readSecret(storageKey, aad)
            } catch (_: McpSecretsMustBeReenteredException) {
                // Recorded in unreadableKeys; keep checking the other record.
            }
        }
        return mutableRecoveryState.value
    }

    // Once a stored envelope failed to decrypt, writing over it would discard what it held.
    // Check again rather than trusting the last result, so a Keystore that came back is usable.
    private fun requireReady() {
        if (mutableRecoveryState.value != McpConfigRecoveryState.Ready &&
            refreshRecoveryState() != McpConfigRecoveryState.Ready
        ) {
            throw McpSecretsMustBeReenteredException()
        }
    }

    private fun markReadable(storageKey: String, readable: Boolean) {
        if (readable) unreadableKeys.remove(storageKey) else unreadableKeys.add(storageKey)
        mutableRecoveryState.value = if (unreadableKeys.isEmpty()) {
            McpConfigRecoveryState.Ready
        } else {
            McpConfigRecoveryState.SecretsMustBeReentered
        }
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
        var attempt = 1
        while (true) {
            when (val result = records.read(storageKey, aad)) {
                is SecretRecordResult.Success -> {
                    markReadable(storageKey, readable = true)
                    val value = result.value ?: return migratePlaintext(storageKey, aad)
                    return String(value, StandardCharsets.UTF_8)
                }
                SecretRecordResult.Unrecoverable -> {
                    // A value that is not an envelope was written by the initial plaintext build.
                    val raw = rawPreference(storageKey)
                    if (raw != null && SecretEnvelope.decode(raw) == null) {
                        markReadable(storageKey, readable = true)
                        return migratePlaintext(storageKey, aad)
                    }
                    // An envelope that will not decrypt. The Keystore reports a busy or restarting
                    // provider the same way as a lost key, so retry before giving up on it.
                    if (attempt >= DECRYPT_ATTEMPTS) {
                        markReadable(storageKey, readable = false)
                        throw McpSecretsMustBeReenteredException()
                    }
                    attempt++
                    Thread.sleep(DECRYPT_RETRY_DELAY_MS)
                }
                SecretRecordResult.StorageUnavailable ->
                    error("MCP secret storage is unavailable for $storageKey")
            }
        }
    }

    private fun rawPreference(storageKey: String): String? = try {
        preferences.getString(storageKey, null)
    } catch (_: ClassCastException) {
        null
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
        val raw = rawPreference(storageKey) ?: return null
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
        private const val DECRYPT_ATTEMPTS = 3
        private const val DECRYPT_RETRY_DELAY_MS = 50L
        private val SECRETS = listOf(TOKEN_KEY to TOKEN_AAD, ENDPOINTS_KEY to ENDPOINTS_AAD)
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
