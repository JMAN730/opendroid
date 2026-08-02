package com.opendroid.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
                require(providerName.none { it == '\u0000' || it == '\n' || it == '\r' }) {
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
 * This is deliberately separate from [SecurePrefs]. Its only legacy dependency is a deprecated,
 * one-time importer for the three provider credential families covered by this API.
 */
interface ProviderCredentialStore {
    val recoveryState: StateFlow<ProviderCredentialRecoveryState>

    fun read(credential: ProviderCredentialId): CredentialStoreResult<String?>

    fun readProviderApiKeys(): CredentialStoreResult<Map<String, String>>

    fun write(credential: ProviderCredentialId, value: String): CredentialStoreResult<Unit>

    fun remove(credential: ProviderCredentialId): CredentialStoreResult<Unit>

    /** Attempts an idempotent, write-before-delete import from legacy encrypted preferences. */
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
    @Suppress("DEPRECATION")
    private val delegate = ProviderCredentialStoreImpl(
        records = SharedPreferencesCredentialRecordStorage(
            context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreCredentialCipher(keyAlias),
        legacyCredentials = LegacyEncryptedSharedPreferencesCredentialSource(context.applicationContext)
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

/** JVM-testable implementation seam. Android wiring is limited to its three collaborators. */
internal class ProviderCredentialStoreImpl(
    private val records: CredentialRecordStorage,
    private val cipher: CredentialAeadCipher,
    private val legacyCredentials: LegacyCredentialSource
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
        val keys = try {
            records.keys()
        } catch (_: RuntimeException) {
            return@synchronized CredentialStoreResult.StorageUnavailable
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
        try {
            cipher.resetForReentry()
        } catch (_: CredentialKeyUnavailableException) {
            return@synchronized requireCredentialReentry()
        } catch (_: GeneralSecurityException) {
            return@synchronized requireCredentialReentry()
        }

        val directRecordKeys = try {
            records.keys().filter { it.startsWith(STORAGE_KEY_PREFIX) }
        } catch (_: RuntimeException) {
            return@synchronized CredentialStoreResult.StorageUnavailable
        }
        for (storageKey in directRecordKeys) {
            val credential = ProviderCredentialId.fromStorageKey(storageKey)
            if (credential == null) {
                // Target only the malformed provider record. This dedicated store must not use a
                // broad clear that could erase future non-credential preferences.
                val removed = try {
                    records.remove(storageKey)
                } catch (_: RuntimeException) {
                    false
                }
                if (!removed) return@synchronized CredentialStoreResult.StorageUnavailable
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
            is CredentialStoreResult.Success -> result.value
            CredentialStoreResult.CredentialsMustBeReentered -> return requireCredentialReentry()
            CredentialStoreResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
        }

        val credentials = legacyKeys
            .mapNotNull(ProviderCredentialId::fromLegacyPreferenceKey)
            .toSet()

        for (credential in credentials) {
            val legacyValue = when (val result = legacyCredentials.read(credential.legacyPreferenceKey)) {
                is CredentialStoreResult.Success -> result.value
                CredentialStoreResult.CredentialsMustBeReentered -> return requireCredentialReentry()
                CredentialStoreResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
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

            when (val result = legacyCredentials.remove(credential.legacyPreferenceKey)) {
                is CredentialStoreResult.Success -> Unit
                CredentialStoreResult.CredentialsMustBeReentered -> return requireCredentialReentry()
                CredentialStoreResult.StorageUnavailable -> return CredentialStoreResult.StorageUnavailable
            }
        }

        return CredentialStoreResult.Success(Unit)
    }

    private fun readStored(credential: ProviderCredentialId): CredentialStoreResult<StoredCredential> {
        val rawRecord = try {
            records.read(credential.storageKey)
        } catch (_: CredentialRecordMalformedException) {
            return requireCredentialReentry()
        } catch (_: RuntimeException) {
            return CredentialStoreResult.StorageUnavailable
        } ?: return CredentialStoreResult.Success(StoredCredential(exists = false, value = null))

        val envelope = CredentialEnvelope.decode(rawRecord) ?: return requireCredentialReentry()
        return try {
            val plaintext = cipher.decrypt(
                iv = envelope.iv,
                ciphertext = envelope.ciphertext,
                aad = credential.logicalId.toByteArray(StandardCharsets.UTF_8)
            )
            when (val decoded = decodeStoredValue(plaintext)) {
                DecodedStoredValue.Tombstone ->
                    CredentialStoreResult.Success(StoredCredential(exists = true, value = null))
                is DecodedStoredValue.Secret ->
                    CredentialStoreResult.Success(StoredCredential(exists = true, value = decoded.value))
                DecodedStoredValue.Malformed -> requireCredentialReentry()
            }
        } catch (_: CredentialKeyUnavailableException) {
            requireCredentialReentry()
        } catch (_: GeneralSecurityException) {
            // Includes AES-GCM authentication failure. Do not distinguish tampering from a
            // lost key; both require credentials to be entered again.
            requireCredentialReentry()
        } catch (_: IllegalArgumentException) {
            requireCredentialReentry()
        }
    }

    private fun writeStored(
        credential: ProviderCredentialId,
        value: String?
    ): CredentialStoreResult<Unit> = try {
        val encrypted = cipher.encrypt(
            plaintext = encodeStoredValue(value),
            aad = credential.logicalId.toByteArray(StandardCharsets.UTF_8)
        )
        if (records.write(credential.storageKey, CredentialEnvelope.encode(encrypted))) {
            CredentialStoreResult.Success(Unit)
        } else {
            CredentialStoreResult.StorageUnavailable
        }
    } catch (_: CredentialKeyUnavailableException) {
        requireCredentialReentry()
    } catch (_: GeneralSecurityException) {
        requireCredentialReentry()
    } catch (_: IllegalArgumentException) {
        requireCredentialReentry()
    } catch (_: RuntimeException) {
        CredentialStoreResult.StorageUnavailable
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

/** Minimal app-private persistence boundary; production values are ciphertext envelopes only. */
internal interface CredentialRecordStorage {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun keys(): Set<String>
}

private class SharedPreferencesCredentialRecordStorage(
    private val preferences: SharedPreferences
) : CredentialRecordStorage {
    override fun read(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        // A non-string record cannot be a valid versioned envelope. It is a recovery case, not a
        // transient storage outage, so callers can reach the explicit re-entry flow.
        throw CredentialRecordMalformedException()
    }

    @Suppress("UseKtx") // The Boolean return from commit() is the durability boundary.
    override fun write(key: String, value: String): Boolean = preferences.edit().putString(key, value).commit()

    @Suppress("UseKtx") // The Boolean return from commit() is the durability boundary.
    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    override fun keys(): Set<String> = preferences.all.keys
}

/** The encrypted legacy store is read only by the one-time credential importer. */
internal interface LegacyCredentialSource {
    fun keys(): CredentialStoreResult<Set<String>>
    fun read(key: String): CredentialStoreResult<String?>
    fun remove(key: String): CredentialStoreResult<Unit>
}

@Deprecated(
    message = "Only use this encrypted preference source to import provider credentials into AndroidProviderCredentialStore.",
    level = DeprecationLevel.WARNING
)
private class LegacyEncryptedSharedPreferencesCredentialSource(
    private val context: Context
) : LegacyCredentialSource {
    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            LEGACY_PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun keys(): CredentialStoreResult<Set<String>> = withPreferences { it.all.keys }

    override fun read(key: String): CredentialStoreResult<String?> = withPreferences {
        it.getString(key, null)
    }

    @Suppress("UseKtx") // Migration must observe whether the legacy deletion committed.
    override fun remove(key: String): CredentialStoreResult<Unit> = withPreferences { preferences ->
        if (preferences.edit().remove(key).commit()) Unit else throw CredentialStorageCommitException()
    }

    private fun <T> withPreferences(block: (SharedPreferences) -> T): CredentialStoreResult<T> = try {
        CredentialStoreResult.Success(block(preferences))
    } catch (_: CredentialStorageCommitException) {
        CredentialStoreResult.StorageUnavailable
    } catch (_: java.io.IOException) {
        CredentialStoreResult.CredentialsMustBeReentered
    } catch (_: GeneralSecurityException) {
        CredentialStoreResult.CredentialsMustBeReentered
    } catch (_: SecurityException) {
        CredentialStoreResult.CredentialsMustBeReentered
    } catch (_: IllegalStateException) {
        CredentialStoreResult.CredentialsMustBeReentered
    } catch (_: ClassCastException) {
        CredentialStoreResult.CredentialsMustBeReentered
    } catch (_: RuntimeException) {
        CredentialStoreResult.CredentialsMustBeReentered
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "opendroid_secure_prefs"
    }
}

private class CredentialStorageCommitException : RuntimeException()

/** A direct credential record exists but cannot be decoded as its required String envelope. */
internal class CredentialRecordMalformedException : RuntimeException()

internal data class EncryptedCredential(val iv: ByteArray, val ciphertext: ByteArray)

internal interface CredentialAeadCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential

    @Throws(GeneralSecurityException::class)
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    @Throws(GeneralSecurityException::class)
    fun resetForReentry()
}

internal class CredentialKeyUnavailableException : GeneralSecurityException()

/** AndroidKeyStore-only AES-256/GCM implementation. */
private class AndroidKeyStoreCredentialCipher(
    private val keyAlias: String,
    private val secureRandom: SecureRandom = SecureRandom()
) : CredentialAeadCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedCredential = withKey { key ->
        val iv = ByteArray(GCM_IV_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        EncryptedCredential(iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray = withKey { key ->
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext)
    }

    override fun resetForReentry() {
        try {
            keyStore().deleteEntry(keyAlias)
        } catch (_: GeneralSecurityException) {
            throw CredentialKeyUnavailableException()
        } catch (_: ProviderException) {
            throw CredentialKeyUnavailableException()
        }
    }

    private fun <T> withKey(block: (SecretKey) -> T): T = try {
        block(loadOrCreateKey())
    } catch (exception: CredentialKeyUnavailableException) {
        throw exception
    } catch (_: GeneralSecurityException) {
        throw CredentialKeyUnavailableException()
    } catch (_: ProviderException) {
        throw CredentialKeyUnavailableException()
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw CredentialKeyUnavailableException()
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE_PROVIDER)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }
            .generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

/** Compact, strictly versioned envelope. AES-GCM returns ciphertext followed by its tag. */
private data class CredentialEnvelope(val iv: ByteArray, val ciphertext: ByteArray) {
    companion object {
        private const val VERSION = "v1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16

        fun encode(encrypted: EncryptedCredential): String = listOf(
            VERSION,
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted.iv),
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted.ciphertext)
        ).joinToString(".")

        fun decode(serialized: String): CredentialEnvelope? {
            val parts = serialized.split('.')
            if (parts.size != 3 || parts[0] != VERSION || parts[1].isEmpty() || parts[2].isEmpty()) return null
            val iv = try {
                Base64.getUrlDecoder().decode(parts[1])
            } catch (_: IllegalArgumentException) {
                return null
            }
            val ciphertext = try {
                Base64.getUrlDecoder().decode(parts[2])
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) return null
            return CredentialEnvelope(iv, ciphertext)
        }
    }
}
