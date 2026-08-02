package com.opendroid.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The single direct-Android-Keystore cipher, envelope format, and record boundary used by every
 * secret this app stores at rest.
 *
 * [ProviderCredentialStore] and [UserProfileStore] both build on the primitives declared here so
 * there is exactly one audited crypto implementation. Domain semantics (tombstones, provider
 * enumeration, profile encoding) stay in those stores.
 */

/** Outcome of a raw record operation. No failure case carries secret material. */
internal sealed interface SecretRecordResult<out T> {
    data class Success<T>(val value: T) : SecretRecordResult<T>

    /** The authenticated ciphertext or the Keystore key cannot safely be used. */
    data object Unrecoverable : SecretRecordResult<Nothing>

    /** App-private storage could not durably persist the requested operation. */
    data object StorageUnavailable : SecretRecordResult<Nothing>
}

/** Minimal app-private persistence boundary; production values are ciphertext envelopes only. */
internal interface SecretRecordStorage {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun keys(): Set<String>
}

/** A record exists but cannot be decoded as the required String envelope. */
internal class SecretRecordMalformedException : RuntimeException()

internal class SharedPreferencesSecretRecordStorage(
    private val preferences: SharedPreferences
) : SecretRecordStorage {
    override fun read(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        // A non-string record cannot be a valid versioned envelope. It is a recovery case, not a
        // transient storage outage, so callers can reach the explicit re-entry flow.
        throw SecretRecordMalformedException()
    }

    // The Boolean return from commit() is the durability boundary; edit { }
    // discards it, so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun write(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    // The Boolean return from commit() is the durability boundary; edit { }
    // discards it, so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()

    override fun keys(): Set<String> = preferences.all.keys
}

internal data class EncryptedSecret(val iv: ByteArray, val ciphertext: ByteArray)

internal interface SecretAeadCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret

    @Throws(GeneralSecurityException::class)
    fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    /** Drops the current key material so the user can store fresh values under a new key. */
    @Throws(GeneralSecurityException::class)
    fun resetForReentry()
}

// Carries its cause so a tamper (AEADBadTagException) stays distinguishable from a vanished key in
// logs and tests. Callers still treat every case the same way — re-entry — so the cause is
// diagnostic only and must never be surfaced to the user.
internal class SecretKeyUnavailableException(cause: Throwable? = null) :
    GeneralSecurityException(cause)

/**
 * AndroidKeyStore-only AES-256/GCM implementation.
 *
 * The key is deliberately created without a user-authentication requirement: background provider
 * requests must be able to decrypt without a foreground unlock prompt.
 */
internal class AndroidKeyStoreAeadCipher(
    private val keyAlias: String
) : SecretAeadCipher {
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret = withKey { key ->
        // AndroidKeyStore rejects a caller-supplied IV at ENCRYPT_MODE (it enforces randomized
        // encryption so IVs can never be reused); initializing with no GCMParameterSpec lets the
        // provider generate the IV, which is read back below.
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        check(iv.size == GCM_IV_BYTES) {
            "AndroidKeyStore returned a ${iv.size}-byte GCM IV, expected $GCM_IV_BYTES"
        }
        cipher.updateAAD(aad)
        EncryptedSecret(iv, cipher.doFinal(plaintext))
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
            throw SecretKeyUnavailableException()
        } catch (_: ProviderException) {
            throw SecretKeyUnavailableException()
        }
    }

    private fun <T> withKey(block: (SecretKey) -> T): T = try {
        block(loadOrCreateKey())
    } catch (exception: SecretKeyUnavailableException) {
        throw exception
    } catch (exception: GeneralSecurityException) {
        throw SecretKeyUnavailableException(exception)
    } catch (exception: ProviderException) {
        throw SecretKeyUnavailableException(exception)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        if (keyStore.containsAlias(keyAlias)) {
            return keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw SecretKeyUnavailableException()
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
internal data class SecretEnvelope(val iv: ByteArray, val ciphertext: ByteArray) {
    companion object {
        private const val VERSION = "v1"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16

        fun encode(encrypted: EncryptedSecret): String = listOf(
            VERSION,
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted.iv),
            Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted.ciphertext)
        ).joinToString(".")

        fun decode(serialized: String): SecretEnvelope? {
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
            return SecretEnvelope(iv, ciphertext)
        }
    }
}

/**
 * Binds [SecretRecordStorage] and [SecretAeadCipher] into the one place that turns plaintext into
 * an at-rest envelope and back. Every value is authenticated against the caller's `aad`, so a
 * record cannot be substituted for a different logical value.
 */
internal class KeystoreSecretRecords(
    private val storage: SecretRecordStorage,
    private val cipher: SecretAeadCipher
) {
    /** Returns `Success(null)` when no record exists for [storageKey]. */
    fun read(storageKey: String, aad: String): SecretRecordResult<ByteArray?> {
        val rawRecord = try {
            storage.read(storageKey)
        } catch (exception: SecretRecordMalformedException) {
            Log.e(TAG, "Malformed record for $storageKey", exception)
            return SecretRecordResult.Unrecoverable
        } catch (exception: RuntimeException) {
            Log.e(TAG, "Storage unavailable reading $storageKey", exception)
            return SecretRecordResult.StorageUnavailable
        } ?: return SecretRecordResult.Success(null)

        val envelope = SecretEnvelope.decode(rawRecord) ?: run {
            Log.e(TAG, "Envelope for $storageKey failed to decode")
            return SecretRecordResult.Unrecoverable
        }
        return try {
            SecretRecordResult.Success(
                cipher.decrypt(
                    iv = envelope.iv,
                    ciphertext = envelope.ciphertext,
                    aad = aad.toByteArray(StandardCharsets.UTF_8)
                )
            )
        } catch (exception: SecretKeyUnavailableException) {
            Log.e(TAG, "Key unavailable decrypting $storageKey", exception)
            SecretRecordResult.Unrecoverable
        } catch (exception: GeneralSecurityException) {
            // Includes AES-GCM authentication failure. Do not distinguish tampering from a lost
            // key; both require the value to be entered again.
            Log.e(TAG, "Decryption failed for $storageKey", exception)
            SecretRecordResult.Unrecoverable
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Decryption failed for $storageKey", exception)
            SecretRecordResult.Unrecoverable
        }
    }

    fun write(storageKey: String, aad: String, plaintext: ByteArray): SecretRecordResult<Unit> = try {
        val encrypted = cipher.encrypt(
            plaintext = plaintext,
            aad = aad.toByteArray(StandardCharsets.UTF_8)
        )
        if (storage.write(storageKey, SecretEnvelope.encode(encrypted))) {
            SecretRecordResult.Success(Unit)
        } else {
            Log.e(TAG, "Storage write did not commit for $storageKey")
            SecretRecordResult.StorageUnavailable
        }
    } catch (exception: SecretKeyUnavailableException) {
        Log.e(TAG, "Key unavailable encrypting $storageKey", exception)
        SecretRecordResult.Unrecoverable
    } catch (exception: GeneralSecurityException) {
        Log.e(TAG, "Encryption failed for $storageKey", exception)
        SecretRecordResult.Unrecoverable
    } catch (exception: IllegalArgumentException) {
        Log.e(TAG, "Encryption failed for $storageKey", exception)
        SecretRecordResult.Unrecoverable
    } catch (exception: RuntimeException) {
        Log.e(TAG, "Storage unavailable writing $storageKey", exception)
        SecretRecordResult.StorageUnavailable
    }

    /** Deletes a single record. Callers must never clear a whole preference file instead. */
    fun removeRecord(storageKey: String): SecretRecordResult<Unit> = try {
        if (storage.remove(storageKey)) {
            SecretRecordResult.Success(Unit)
        } else {
            Log.e(TAG, "Storage remove did not commit for $storageKey")
            SecretRecordResult.StorageUnavailable
        }
    } catch (exception: RuntimeException) {
        Log.e(TAG, "Storage unavailable removing $storageKey", exception)
        SecretRecordResult.StorageUnavailable
    }

    fun keys(): SecretRecordResult<Set<String>> = try {
        SecretRecordResult.Success(storage.keys())
    } catch (exception: RuntimeException) {
        Log.e(TAG, "Storage unavailable listing keys", exception)
        SecretRecordResult.StorageUnavailable
    }

    fun resetKeyMaterial(): SecretRecordResult<Unit> = try {
        cipher.resetForReentry()
        SecretRecordResult.Success(Unit)
    } catch (exception: GeneralSecurityException) {
        Log.e(TAG, "Key material reset failed", exception)
        SecretRecordResult.Unrecoverable
    }

    private companion object {
        const val TAG = "KeystoreSecretRecords"
    }
}

/**
 * A one-time migration source. Reading is best-effort: an unreadable source is reported, never
 * silently downgraded to a plaintext fallback.
 */
internal interface LegacySecretSource {
    fun keys(): SecretRecordResult<Set<String>>
    fun readString(key: String): SecretRecordResult<String?>
    fun readBoolean(key: String): SecretRecordResult<Boolean?>
    fun remove(key: String): SecretRecordResult<Unit>
}

private class LegacyStorageCommitException : RuntimeException()

/**
 * The last remaining use of `androidx.security:security-crypto` in the app.
 *
 * It exists only so values written by builds that predate the direct-Keystore stores can be
 * imported once. Nothing reads or writes this file at runtime, and it is never a fallback.
 */
@Deprecated(
    message = "Only use this encrypted preference source to import legacy values into a direct-Keystore store.",
    level = DeprecationLevel.WARNING
)
internal class LegacyEncryptedPreferencesSource(
    private val context: Context
) : LegacySecretSource {
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

    override fun keys(): SecretRecordResult<Set<String>> = withPreferences { it.all.keys }

    override fun readString(key: String): SecretRecordResult<String?> = withPreferences {
        it.getString(key, null)
    }

    override fun readBoolean(key: String): SecretRecordResult<Boolean?> = withPreferences {
        if (it.contains(key)) it.getBoolean(key, false) else null
    }

    // Migration must observe whether the legacy deletion committed; edit { }
    // discards the Boolean from commit(), so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun remove(key: String): SecretRecordResult<Unit> = withPreferences { preferences ->
        if (preferences.edit().remove(key).commit()) Unit else throw LegacyStorageCommitException()
    }

    private fun <T> withPreferences(block: (SharedPreferences) -> T): SecretRecordResult<T> = try {
        SecretRecordResult.Success(block(preferences))
    } catch (_: LegacyStorageCommitException) {
        SecretRecordResult.StorageUnavailable
    } catch (_: java.io.IOException) {
        SecretRecordResult.Unrecoverable
    } catch (_: GeneralSecurityException) {
        SecretRecordResult.Unrecoverable
    } catch (_: SecurityException) {
        SecretRecordResult.Unrecoverable
    } catch (_: IllegalStateException) {
        SecretRecordResult.Unrecoverable
    } catch (_: ClassCastException) {
        SecretRecordResult.Unrecoverable
    } catch (_: RuntimeException) {
        SecretRecordResult.Unrecoverable
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "opendroid_secure_prefs"
    }
}

/**
 * The oldest source: an unencrypted preference file written before any encrypted store existed.
 *
 * Values found here are imported into their classified destination and then erased.
 */
internal class LegacyPlaintextPreferencesSource(
    context: Context,
    preferenceName: String = LEGACY_PREFERENCES_NAME
) : LegacySecretSource {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    override fun keys(): SecretRecordResult<Set<String>> = guarded { preferences.all.keys }

    // A wrongly typed plaintext entry is not importable. Treat it as absent rather than failing
    // the whole migration over an entry no destination can use.
    override fun readString(key: String): SecretRecordResult<String?> = guarded {
        try {
            preferences.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }
    }

    override fun readBoolean(key: String): SecretRecordResult<Boolean?> = guarded {
        try {
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        } catch (_: ClassCastException) {
            null
        }
    }

    // Migration must observe whether the legacy deletion committed; edit { }
    // discards the Boolean from commit(), so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun remove(key: String): SecretRecordResult<Unit> = guarded {
        if (!preferences.edit().remove(key).commit()) throw LegacyStorageCommitException()
    }

    private fun <T> guarded(block: () -> T): SecretRecordResult<T> = try {
        SecretRecordResult.Success(block())
    } catch (_: LegacyStorageCommitException) {
        SecretRecordResult.StorageUnavailable
    } catch (_: RuntimeException) {
        SecretRecordResult.StorageUnavailable
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "opendroid_prefs"
    }
}

/**
 * The ordered legacy sources every one-time import reads through.
 *
 * Both files must be covered by every importer: a value stranded in the older plaintext file is
 * a secret at rest that nothing would otherwise migrate or erase.
 */
@Suppress("DEPRECATION")
internal fun legacyPreferenceSources(context: Context): LegacySecretSource = ChainedLegacySecretSource(
    listOf(
        LegacyEncryptedPreferencesSource(context.applicationContext),
        LegacyPlaintextPreferencesSource(context.applicationContext)
    )
)

/**
 * Reads legacy sources in priority order so a newer encrypted value always wins over an older
 * plaintext one, while a removal still erases every copy.
 */
internal class ChainedLegacySecretSource(
    private val sources: List<LegacySecretSource>
) : LegacySecretSource {
    override fun keys(): SecretRecordResult<Set<String>> {
        val all = linkedSetOf<String>()
        for (source in sources) {
            when (val result = source.keys()) {
                is SecretRecordResult.Success -> all += result.value
                SecretRecordResult.Unrecoverable -> return SecretRecordResult.Unrecoverable
                SecretRecordResult.StorageUnavailable -> return SecretRecordResult.StorageUnavailable
            }
        }
        return SecretRecordResult.Success(all)
    }

    override fun readString(key: String): SecretRecordResult<String?> =
        firstPresent(key) { it.readString(key) }

    override fun readBoolean(key: String): SecretRecordResult<Boolean?> =
        firstPresent(key) { it.readBoolean(key) }

    override fun remove(key: String): SecretRecordResult<Unit> {
        for (source in sources) {
            when (val result = source.remove(key)) {
                is SecretRecordResult.Success -> Unit
                SecretRecordResult.Unrecoverable -> return SecretRecordResult.Unrecoverable
                SecretRecordResult.StorageUnavailable -> return SecretRecordResult.StorageUnavailable
            }
        }
        return SecretRecordResult.Success(Unit)
    }

    private fun <T> firstPresent(
        key: String,
        read: (LegacySecretSource) -> SecretRecordResult<T?>
    ): SecretRecordResult<T?> {
        for (source in sources) {
            when (val result = read(source)) {
                is SecretRecordResult.Success -> result.value?.let { return SecretRecordResult.Success(it) }
                SecretRecordResult.Unrecoverable -> return SecretRecordResult.Unrecoverable
                SecretRecordResult.StorageUnavailable -> return SecretRecordResult.StorageUnavailable
            }
        }
        return SecretRecordResult.Success(null)
    }
}
