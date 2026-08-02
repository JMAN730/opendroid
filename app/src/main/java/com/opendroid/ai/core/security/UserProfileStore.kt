package com.opendroid.ai.core.security

import android.content.Context
import java.nio.charset.StandardCharsets

/** The profile details the user gives during onboarding. Both fields are personal data. */
data class UserProfile(val name: String, val dateOfBirth: String)

/** Result of a profile operation. No failure case includes profile data. */
sealed interface ProfileStoreResult<out T> {
    data class Success<T>(val value: T) : ProfileStoreResult<T>

    /** The authenticated ciphertext or Keystore key cannot safely be used. */
    data object ProfileMustBeReentered : ProfileStoreResult<Nothing>

    /** App-private storage could not durably persist the requested operation. */
    data object StorageUnavailable : ProfileStoreResult<Nothing>
}

/**
 * Direct Android-Keystore backed storage for the user's profile name and date of birth.
 *
 * Profile details are personal data, so they stay encrypted at rest. There is no plaintext
 * fallback: an unreadable record answers [ProfileStoreResult.ProfileMustBeReentered] and the user
 * re-enters the details through onboarding.
 */
interface UserProfileStore {
    /** Returns `Success(null)` when no profile has been stored yet. */
    fun read(): ProfileStoreResult<UserProfile?>

    fun write(profile: UserProfile): ProfileStoreResult<Unit>

    /** Attempts an idempotent, write-before-delete import from legacy preferences. */
    fun migrateLegacyProfile(): ProfileStoreResult<Unit>

    /** Drops unreadable key material and the stale record so onboarding can store a fresh profile. */
    fun resetForReentry(): ProfileStoreResult<Unit>

    companion object {
        /** The legacy preference keys this store owns; see [LegacySecurePreferenceInventory]. */
        const val LEGACY_NAME_KEY = "user_name"
        const val LEGACY_DATE_OF_BIRTH_KEY = "user_dob"
    }
}

/** Production Android implementation. The backing SharedPreferences file contains envelopes only. */
class AndroidUserProfileStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) : UserProfileStore {
    private val delegate = UserProfileStoreImpl(
        records = KeystoreSecretRecords(
            storage = SharedPreferencesSecretRecordStorage(
                context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
            ),
            // A dedicated alias keeps provider-credential recovery from destroying the profile,
            // and profile recovery from destroying provider credentials.
            cipher = AndroidKeyStoreAeadCipher(keyAlias)
        ),
        legacyProfile = legacyPreferenceSources(context)
    )

    override fun read(): ProfileStoreResult<UserProfile?> = delegate.read()

    override fun write(profile: UserProfile): ProfileStoreResult<Unit> = delegate.write(profile)

    override fun migrateLegacyProfile(): ProfileStoreResult<Unit> = delegate.migrateLegacyProfile()

    override fun resetForReentry(): ProfileStoreResult<Unit> = delegate.resetForReentry()

    companion object {
        const val PREFERENCES_NAME = "opendroid_user_profile"
        const val KEY_ALIAS = "opendroid.user_profile.aes_gcm.v1"
    }
}

/** JVM-testable implementation seam. Android wiring is limited to its two collaborators. */
internal class UserProfileStoreImpl(
    private val records: KeystoreSecretRecords,
    private val legacyProfile: LegacySecretSource
) : UserProfileStore {
    private val lock = Any()

    override fun read(): ProfileStoreResult<UserProfile?> = synchronized(lock) { readLocked() }

    override fun write(profile: UserProfile): ProfileStoreResult<Unit> = synchronized(lock) {
        writeLocked(profile)
    }

    override fun migrateLegacyProfile(): ProfileStoreResult<Unit> = synchronized(lock) {
        // Check the destination first. A profile already committed here wins, and its presence
        // means an unreadable legacy keyset is not a lost profile - only a stale duplicate that
        // could not be swept up. Reading the legacy file first would report a false loss.
        val destination = when (val result = readLocked()) {
            is ProfileStoreResult.Success -> result.value
            ProfileStoreResult.ProfileMustBeReentered -> return@synchronized ProfileStoreResult.ProfileMustBeReentered
            ProfileStoreResult.StorageUnavailable -> return@synchronized ProfileStoreResult.StorageUnavailable
        }

        val legacyName = when (val result = legacyProfile.readString(UserProfileStore.LEGACY_NAME_KEY)) {
            is SecretRecordResult.Success -> result.value
            // Nothing to import and nothing lost when the destination already holds a profile.
            SecretRecordResult.Unrecoverable -> return@synchronized if (destination == null) {
                ProfileStoreResult.ProfileMustBeReentered
            } else {
                ProfileStoreResult.Success(Unit)
            }
            SecretRecordResult.StorageUnavailable -> return@synchronized ProfileStoreResult.StorageUnavailable
        }
        val legacyDateOfBirth = when (
            val result = legacyProfile.readString(UserProfileStore.LEGACY_DATE_OF_BIRTH_KEY)
        ) {
            is SecretRecordResult.Success -> result.value
            SecretRecordResult.Unrecoverable -> return@synchronized if (destination == null) {
                ProfileStoreResult.ProfileMustBeReentered
            } else {
                ProfileStoreResult.Success(Unit)
            }
            SecretRecordResult.StorageUnavailable -> return@synchronized ProfileStoreResult.StorageUnavailable
        }
        if (legacyName == null && legacyDateOfBirth == null) {
            return@synchronized ProfileStoreResult.Success(Unit)
        }

        if (destination == null) {
            val migrated = UserProfile(
                name = legacyName.orEmpty(),
                dateOfBirth = legacyDateOfBirth.orEmpty()
            )
            when (val result = writeLocked(migrated)) {
                is ProfileStoreResult.Success -> Unit
                ProfileStoreResult.ProfileMustBeReentered ->
                    return@synchronized ProfileStoreResult.ProfileMustBeReentered
                // The destination write failed, so the legacy source is deliberately left intact
                // and a later run retries the whole import.
                ProfileStoreResult.StorageUnavailable ->
                    return@synchronized ProfileStoreResult.StorageUnavailable
            }
        }

        // The destination is durably committed by now, so a failure to sweep the legacy copy only
        // leaves work for the next run - it is never a lost profile.
        for (key in listOf(UserProfileStore.LEGACY_NAME_KEY, UserProfileStore.LEGACY_DATE_OF_BIRTH_KEY)) {
            if (legacyProfile.remove(key) !is SecretRecordResult.Success) {
                return@synchronized ProfileStoreResult.StorageUnavailable
            }
        }
        ProfileStoreResult.Success(Unit)
    }

    override fun resetForReentry(): ProfileStoreResult<Unit> = synchronized(lock) {
        when (records.resetKeyMaterial()) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable -> return@synchronized ProfileStoreResult.ProfileMustBeReentered
            SecretRecordResult.StorageUnavailable -> return@synchronized ProfileStoreResult.StorageUnavailable
        }
        // Target only the profile record. Other preferences in this file must survive recovery.
        when (records.removeRecord(STORAGE_KEY)) {
            is SecretRecordResult.Success -> Unit
            SecretRecordResult.Unrecoverable -> return@synchronized ProfileStoreResult.ProfileMustBeReentered
            SecretRecordResult.StorageUnavailable -> return@synchronized ProfileStoreResult.StorageUnavailable
        }
        ProfileStoreResult.Success(Unit)
    }

    private fun readLocked(): ProfileStoreResult<UserProfile?> =
        when (val result = records.read(STORAGE_KEY, AAD)) {
            is SecretRecordResult.Success -> {
                val plaintext = result.value
                when {
                    plaintext == null -> ProfileStoreResult.Success(null)
                    else -> decodeProfile(plaintext)
                        ?.let { ProfileStoreResult.Success(it) }
                        ?: ProfileStoreResult.ProfileMustBeReentered
                }
            }
            SecretRecordResult.Unrecoverable -> ProfileStoreResult.ProfileMustBeReentered
            SecretRecordResult.StorageUnavailable -> ProfileStoreResult.StorageUnavailable
        }

    private fun writeLocked(profile: UserProfile): ProfileStoreResult<Unit> =
        when (records.write(STORAGE_KEY, AAD, encodeProfile(profile))) {
            is SecretRecordResult.Success -> ProfileStoreResult.Success(Unit)
            SecretRecordResult.Unrecoverable -> ProfileStoreResult.ProfileMustBeReentered
            SecretRecordResult.StorageUnavailable -> ProfileStoreResult.StorageUnavailable
        }

    internal companion object {
        const val STORAGE_KEY = "profile.user"
        const val AAD = "user-profile"

        private const val VERSION: Byte = 1
        private const val FIELD_COUNT = 2
        private const val LENGTH_BYTES = 4
        private const val MAX_FIELD_BYTES = 64 * 1024

        /** `[version][len(name)][name][len(dob)][dob]`, all lengths 4-byte big-endian. */
        fun encodeProfile(profile: UserProfile): ByteArray {
            val fields = listOf(profile.name, profile.dateOfBirth)
                .map { it.toByteArray(StandardCharsets.UTF_8) }
            val output = ByteArray(1 + fields.sumOf { LENGTH_BYTES + it.size })
            output[0] = VERSION
            var offset = 1
            for (field in fields) {
                for (shift in 3 downTo 0) {
                    output[offset++] = ((field.size ushr (shift * 8)) and 0xFF).toByte()
                }
                field.copyInto(output, offset)
                offset += field.size
            }
            return output
        }

        /** Returns null for any encoding this version cannot safely interpret. */
        fun decodeProfile(plaintext: ByteArray): UserProfile? {
            if (plaintext.isEmpty() || plaintext[0] != VERSION) return null
            var offset = 1
            val fields = mutableListOf<String>()
            repeat(FIELD_COUNT) {
                if (offset + LENGTH_BYTES > plaintext.size) return null
                var length = 0
                repeat(LENGTH_BYTES) {
                    length = (length shl 8) or (plaintext[offset++].toInt() and 0xFF)
                }
                if (length < 0 || length > MAX_FIELD_BYTES) return null
                if (offset + length > plaintext.size) return null
                fields += String(plaintext, offset, length, StandardCharsets.UTF_8)
                offset += length
            }
            if (offset != plaintext.size) return null
            return UserProfile(name = fields[0], dateOfBirth = fields[1])
        }
    }
}
