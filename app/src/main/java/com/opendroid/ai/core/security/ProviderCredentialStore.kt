package com.opendroid.ai.core.security

import android.content.Context
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Identifies a provider credential without exposing a persistence implementation to callers.
 *
 * The logical ID is authenticated as AES-GCM additional authenticated data, so ciphertext for
 * one credential cannot be substituted for another credential.
 */
sealed class ProviderCredentialId protected constructor(
    internal val logicalId: String,
    internal val legacyPreferenceKey: String
) {
    internal val storageKey: String
        get() = STORAGE_KEY_PREFIX + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(logicalId.toByteArray(StandardCharsets.UTF_8))

    class ApiKey private constructor(val providerName: String) : ProviderCredentialId(
        logicalId = "$API_KEY_LOGICAL_PREFIX$providerName",
        legacyPreferenceKey = "llm_api_key_$providerName"
    ) {
        companion object {
            operator fun invoke(providerName: String): ApiKey {
                require(providerName.isNotBlank()) { "Provider name must not be blank." }
                require(providerName == providerName.trim()) { "Provider name must not have surrounding whitespace." }
                require(providerName.length <= MAX_PROVIDER_NAME_LENGTH) { "Provider name is too long." }
                require(providerName.none { it.code == 0 || it == '\n' || it == '\r' }) {
                    "Provider name contains an unsupported character."
                }
                return ApiKey(providerName)
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ApiKey && providerName == other.providerName

        override fun hashCode(): Int = providerName.hashCode()
    }

    data object ElevenLabsApiKey : ProviderCredentialId(
        logicalId = "elevenlabs-api-key",
        legacyPreferenceKey = "elevenlabs_api_key"
    )

    data object HuggingFaceToken : ProviderCredentialId(
        logicalId = "huggingface-token",
        legacyPreferenceKey = "huggingface_token"
    )

    internal companion object {
        private const val STORAGE_KEY_PREFIX = "credential."
        private const val API_KEY_LOGICAL_PREFIX = "provider-api-key:"
        private const val MAX_PROVIDER_NAME_LENGTH = 256

        fun fromStorageKey(storageKey: String): ProviderCredentialId? {
            if (!storageKey.startsWith(STORAGE_KEY_PREFIX)) return null
            val encodedId = storageKey.removePrefix(STORAGE_KEY_PREFIX)
            val logicalId = try {
                String(Base64.getUrlDecoder().decode(encodedId), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return fromLogicalId(logicalId)
        }

        fun fromLegacyPreferenceKey(key: String): ProviderCredentialId? = when {
            key == ElevenLabsApiKey.legacyPreferenceKey -> ElevenLabsApiKey
            key == HuggingFaceToken.legacyPreferenceKey -> HuggingFaceToken
            key.startsWith("llm_api_key_") -> {
                val provider = key.removePrefix("llm_api_key_")
                runCatching { ApiKey(provider) }.getOrNull()
            }
            else -> null
        }

        private fun fromLogicalId(logicalId: String): ProviderCredentialId? = when {
            logicalId == ElevenLabsApiKey.logicalId -> ElevenLabsApiKey
            logicalId == HuggingFaceToken.logicalId -> HuggingFaceToken
            logicalId.startsWith(API_KEY_LOGICAL_PREFIX) -> runCatching {
                ApiKey(logicalId.removePrefix(API_KEY_LOGICAL_PREFIX))
            }.getOrNull()
            else -> null
        }
    }
}

/** Result of a credential operation. No failure case includes secret material. */
sealed interface CredentialStoreResult<out T> {
    data class Success<T>(val value: T) : CredentialStoreResult<T>

    /** The authenticated ciphertext or Keystore key cannot safely be used. */
    data object CredentialsMustBeReentered : CredentialStoreResult<Nothing>

    /** App-private storage could not durably persist the requested operation. */
    data object StorageUnavailable : CredentialStoreResult<Nothing>
}

/** Public recovery signal for UI and callers that need to prompt for provider credentials again. */
sealed interface ProviderCredentialRecoveryState {
    data object Ready : ProviderCredentialRecoveryState
    data object CredentialsMustBeReentered : ProviderCredentialRecoveryState
}

/**
 * Direct Android-Keystore backed storage for provider credentials.
 *
 * Crypto, envelope format, and the record boundary come from [KeystoreSecretRecords], which is
 * shared with [UserProfileStore]. Its only legacy dependency is a one-time importer for the three
 * provider credential families covered by this API.
 */
interface ProviderCredentialStore {
    val recoveryState: StateFlow<ProviderCredentialRecoveryState>

    fun read(credential: ProviderCredentialId): CredentialStoreResult<String?>

    fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>>

    fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit>

    fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit>

    /** Attempts an idempotent, write-before-delete import from legacy preferences. */
    fun migrateLegacyCredentials(): CredentialStoreResult<Unit>

    /**
     * Explicitly clears unrecoverable provider credentials so the user can enter them again.
     * It never clears the legacy preference file or any non-provider preferences.
     */
    fun resetForReentry(): CredentialStoreResult<Unit>
}

/** Production Android implementation. The backing SharedPreferences file contains envelopes only. */
class AndroidProviderCredentialStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) : ProviderCredentialStore {
    private val delegate = ProviderCredentialStoreImpl(
        records = KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(
                context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            ),
            cipher = AndroidKeyStoreAeadCipher(keyAlias)
        ),
        // Both legacy files, so a credential stranded in the older plaintext file is imported and
        // erased rather than left readable on disk forever.
        legacyCredentials = legacyPreferenceSources(context)
    )

    override val recoveryState: StateFlow<ProviderCredentialRecoveryState>
        get() = delegate.recoveryState

    override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> = delegate.read(credential)

    override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> =
        delegate.readProviderApiKeys()

    override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
        delegate.write(credential, value)

    override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> =
        delegate.remove(credential)

    override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> =
        delegate.migrateLegacyCredentials()

    override fun resetForReentry(): CredentialStoreResult<Unit> = delegate.resetForReentry()

    companion object {
        const val PREFERENCES_NAME = "opendroid_provider_credentials"
        const val KEY_ALIAS = "opendroid.provider_credentials.aes_gcm.v1"
    }
}

/** JVM-testable implementation seam. Android wiring is limited to its two collaborators. */
internal class ProviderCredentialStoreImpl(
    private val records: KeystoreSecretRecords,
    private val legacyCredentials: LegacySecretSource
) : ProviderCredentialStore {
    private val lock = Any()
    private val mutableRecoveryState = MutableStateFlow<ProviderCredentialRecoveryState>(
        ProviderCredentialRecoveryState.Ready
    )

    override val recoveryState: StateFlow<ProviderCredentialRecoveryState> = mutableRecoveryState

    override fun read(credential: ProviderCredentialId): CredentialStoreResult<String?> = synchronized(lock) {
        when (val result = readStored(credential)) {
            is CredentialStoreResult.Success -> CredentialStoreResult.Success(result.value.value)
            CredentialStoreResult.CredentialsMustBeReentered -> CredentialStoreResult.CredentialsMustBeReentered
            CredentialStoreResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }
    }

    override fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>> = synchronized(lock) {
        val keys = when (val result = records.keys()) {
            is SecretRecordResult.Success -> result.value
            SecretRecordResult.Unrecoverable -> return@synchronized requireCredentialReentry()
            SecretRecordResult.StorageUnavailable -> return@synchronized CredentialStoreResult.StorageUnavailable
        }
        val providerCredentials = linkedMapOf<String, String>()
        for (storageKey in keys) {
            if (!storageKey.startsWith(STORAGE_KEY_PREFIX)) continue
            val credential = ProviderCredentialId.fromStorageKey(storageKey)
                ?: return@synchronized requireCredentialReentry()
            if (credential !is ProviderCredentialId.ApiKey) continue

            when (val result = readStored(credential)) {
                is CredentialStoreResult.Success -> result.value.value?.let {
                    providerCredentials[credential.providerName] = it
                }
                CredentialStoreResult.CredentialsMustBeReentered ->
                    return@synchronized CredentialStoreResult.CredentialsMustBeReentered
                CredentialStoreResult.StorageUnavailable ->
                    return@synchronized CredentialStoreResult.StorageUnavailable
            }
        }
        CredentialStoreResult.Success(providerCredentials)
    }

    override fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit> =
        synchronized(lock) {
            writeStored(credential, value.takeUnless(String::isBlank))
        }

    override fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit> = synchronized(lock) {
        // A tombstone is authenticated ciphertext. It prevents a future legacy import from
        // resurrecting a credential the user intentionally removed.
        writeStored(credential, null)
    }

    override fun migrateLegacyCredentials(): CredentialStoreResult<Unit> = synchronized(lock) {
        migrateLegacyCredentialsLocked()
    }

    override fun resetForReentry(): CredentialStoreResult<Unit> = synchronized(lock) {
        when (records.resetKeyMaterial()) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable -> return@synchronized requireCredentialReentry()
            SecretRecordResult.StorageUnavailable -> return@synchronized CredentialStoreResult.StorageUnavailable
        }

        val directRecordKeys = when (val result = records.keys()) {
            is SecretRecordResult.Success -> result.value.filter { it.startsWith(STORAGE_KEY_PREFIX) }
            SecretRecordResult.Unrecoverable -> return@synchronized requireCredentialReentry()
            SecretRecordResult.StorageUnavailable -> return@synchronized CredentialStoreResult.StorageUnavailable
        }
        for (storageKey in directRecordKeys) {
            val credential = ProviderCredentialId.fromStorageKey(storageKey)
            if (credential == null) {
                // Target only the malformed provider record. This dedicated store must not use a
                // broad clear that could erase future non-credential preferences.
                if (records.removeRecord(storageKey) !is SecretRecordResult.Success) {
                    return@synchronized CredentialStoreResult.StorageUnavailable
                }
                continue
            }
            when (val result = writeStored(credential, null)) {
                is CredentialStoreResult.Success -> Unit
                CredentialStoreResult.CredentialsMustBeReentered ->
                    return@synchronized CredentialStoreResult.CredentialsMustBeReentered
                CredentialStoreResult.StorageUnavailable ->
                    return@synchronized CredentialStoreResult.StorageUnavailable
            }
        }
        mutableRecoveryState.value = ProviderCredentialRecoveryState.Ready
        CredentialStoreResult.Success(Unit)
    }

    private fun migrateLegacyCredentialsLocked(): CredentialStoreResult<Unit> {
        val legacyKeys = when (val result = legacyCredentials.keys()) {
            is SecretRecordResult.Success -> result.value
            SecretRecordResult.Unrecoverable -> return requireCredentialReentry()
            SecretRecordResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
        }

        val credentials = legacyKeys
            .mapNotNull(ProviderCredentialId::fromLegacyPreferenceKey)
            .toSet()

        for (credential in credentials) {
            val legacyValue = when (val result = legacyCredentials.readString(credential.legacyPreferenceKey)) {
                is SecretRecordResult.Success -> result.value
                SecretRecordResult.Unrecoverable -> return requireCredentialReentry()
                SecretRecordResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
            }

            // A current direct value (including a tombstone) wins over legacy state. The direct
            // record is already durably committed, so deleting the legacy duplicate is safe.
            val destination = when (val result = readStored(credential)) {
                is CredentialStoreResult.Success -> result.value
                CredentialStoreResult.CredentialsMustBeReentered -> return CredentialStoreResult.CredentialsMustBeReentered
                CredentialStoreResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
            }
            if (!destination.exists) {
                when (val result = writeStored(credential, legacyValue?.takeUnless(String::isBlank))) {
                    is CredentialStoreResult.Success -> Unit
                    CredentialStoreResult.CredentialsMustBeReentered ->
                        return CredentialStoreResult.CredentialsMustBeReentered
                    CredentialStoreResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
                }
            }

            when (legacyCredentials.remove(credential.legacyPreferenceKey)) {
                is SecretRecordResult.Success -> Unit
                SecretRecordResult.Unrecoverable -> return requireCredentialReentry()
                SecretRecordResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
            }
        }

        return CredentialStoreResult.Success(Unit)
    }

    private fun readStored(credential: ProviderCredentialId): CredentialStoreResult<StoredCredential> =
        when (val result = records.read(credential.storageKey, credential.logicalId)) {
            is SecretRecordResult.Success -> {
                val plaintext = result.value
                if (plaintext == null) {
                    CredentialStoreResult.Success(StoredCredential(exists = false, value = null))
                } else {
                    when (val decoded = decodeStoredValue(plaintext)) {
                        DecodedStoredValue.Tombstone ->
                            CredentialStoreResult.Success(StoredCredential(exists = true, value = null))
                        is DecodedStoredValue.Secret ->
                            CredentialStoreResult.Success(StoredCredential(exists = true, value = decoded.value))
                        DecodedStoredValue.Malformed -> requireCredentialReentry()
                    }
                }
            }
            SecretRecordResult.Unrecoverable -> requireCredentialReentry()
            SecretRecordResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }

    private fun writeStored(
        credential: ProviderCredentialId,
        value: String?
    ): CredentialStoreResult<Unit> = when (
        records.write(credential.storageKey, credential.logicalId, encodeStoredValue(value))
    ) {
        is SecretRecordResult.Success -> CredentialStoreResult.Success(Unit)
        SecretRecordResult.Unrecoverable -> requireCredentialReentry()
        SecretRecordResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
    }

    private fun requireCredentialReentry(): CredentialStoreResult.CredentialsMustBeReentered {
        mutableRecoveryState.value = ProviderCredentialRecoveryState.CredentialsMustBeReentered
        return CredentialStoreResult.CredentialsMustBeReentered
    }

    private data class StoredCredential(val exists: Boolean, val value: String?)

    private companion object {
        const val STORAGE_KEY_PREFIX = "credential."
        const val VALUE_TYPE_TOMBSTONE: Byte = 0
        const val VALUE_TYPE_SECRET: Byte = 1

        fun encodeStoredValue(value: String?): ByteArray {
            if (value == null) return byteArrayOf(VALUE_TYPE_TOMBSTONE)
            return byteArrayOf(VALUE_TYPE_SECRET) + value.toByteArray(StandardCharsets.UTF_8)
        }

        fun decodeStoredValue(plaintext: ByteArray): DecodedStoredValue = when {
            plaintext.contentEquals(byteArrayOf(VALUE_TYPE_TOMBSTONE)) -> DecodedStoredValue.Tombstone
            plaintext.firstOrNull() == VALUE_TYPE_SECRET ->
                DecodedStoredValue.Secret(
                    String(plaintext.copyOfRange(1, plaintext.size), StandardCharsets.UTF_8)
                )
            else -> DecodedStoredValue.Malformed
        }
    }
}

private sealed interface DecodedStoredValue {
    data object Tombstone : DecodedStoredValue
    data class Secret(val value: String) : DecodedStoredValue
    data object Malformed : DecodedStoredValue
}
