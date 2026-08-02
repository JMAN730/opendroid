package com.opendroid.ai.core.settings

import android.content.Context
import android.content.SharedPreferences
import com.opendroid.ai.core.security.LegacySecretSource
import com.opendroid.ai.core.security.SecretRecordResult
import com.opendroid.ai.core.security.legacyPreferenceSources

/**
 * Ordinary, non-secret app settings.
 *
 * Only values that carry no personal or credential data live here. Today that is the onboarding
 * completion flag and the Hugging Face verification timestamp - a display string such as
 * "Today 3:15 PM" that says when a token was last checked, never the token itself. Anything that
 * identifies the user belongs in [com.opendroid.ai.core.security.UserProfileStore], and anything
 * secret belongs in [com.opendroid.ai.core.security.ProviderCredentialStore].
 *
 * The backing file is still app-private and excluded from cloud backup and device transfer.
 */
interface AppSettingsStore {
    fun isOnboardingCompleted(): Boolean

    fun setOnboardingCompleted(completed: Boolean): Boolean

    /** Returns null when the token has never been verified on this device. */
    fun huggingFaceLastVerified(): String?

    fun setHuggingFaceLastVerified(value: String?): Boolean

    /** Idempotent, write-before-delete import of the non-secret keys this store owns. */
    fun migrateLegacySettings(): Boolean

    companion object {
        /** The legacy preference keys this store owns; see `LegacySecurePreferenceInventory`. */
        const val LEGACY_ONBOARDING_COMPLETED_KEY = "onboarding_completed"
        const val LEGACY_HUGGING_FACE_LAST_VERIFIED_KEY = "huggingface_last_verified"
    }
}

/** Narrow persistence boundary so the settings logic stays testable off-device. */
internal interface AppSettingsRecordStorage {
    fun contains(key: String): Boolean
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean): Boolean
    fun getString(key: String): String?
    fun putString(key: String, value: String): Boolean
    fun remove(key: String): Boolean
}

private class SharedPreferencesAppSettingsRecordStorage(
    private val preferences: SharedPreferences
) : AppSettingsRecordStorage {
    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    // The Boolean return from commit() is the durability boundary; edit { }
    // discards it, so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun putBoolean(key: String, value: Boolean): Boolean =
        preferences.edit().putBoolean(key, value).commit()

    override fun getString(key: String): String? = preferences.getString(key, null)

    // The Boolean return from commit() is the durability boundary; edit { }
    // discards it, so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun putString(key: String, value: String): Boolean =
        preferences.edit().putString(key, value).commit()

    // The Boolean return from commit() is the durability boundary; edit { }
    // discards it, so the KTX idiom cannot express this. See #99.
    @Suppress("UseKtx")
    override fun remove(key: String): Boolean = preferences.edit().remove(key).commit()
}

/** Production Android implementation over an app-private plaintext preference file. */
class AndroidAppSettingsStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME
) : AppSettingsStore {
    private val delegate = AppSettingsStoreImpl(
        settings = SharedPreferencesAppSettingsRecordStorage(
            context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        ),
        legacySettings = legacyPreferenceSources(context)
    )

    override fun isOnboardingCompleted(): Boolean = delegate.isOnboardingCompleted()

    override fun setOnboardingCompleted(completed: Boolean): Boolean =
        delegate.setOnboardingCompleted(completed)

    override fun huggingFaceLastVerified(): String? = delegate.huggingFaceLastVerified()

    override fun setHuggingFaceLastVerified(value: String?): Boolean =
        delegate.setHuggingFaceLastVerified(value)

    override fun migrateLegacySettings(): Boolean = delegate.migrateLegacySettings()

    companion object {
        const val PREFERENCES_NAME = "opendroid_app_settings"
    }
}

/** JVM-testable implementation seam. */
internal class AppSettingsStoreImpl(
    private val settings: AppSettingsRecordStorage,
    private val legacySettings: LegacySecretSource
) : AppSettingsStore {
    override fun isOnboardingCompleted(): Boolean =
        settings.getBoolean(ONBOARDING_COMPLETED_KEY, false)

    override fun setOnboardingCompleted(completed: Boolean): Boolean =
        settings.putBoolean(ONBOARDING_COMPLETED_KEY, completed)

    override fun huggingFaceLastVerified(): String? = settings.getString(HUGGING_FACE_LAST_VERIFIED_KEY)

    override fun setHuggingFaceLastVerified(value: String?): Boolean = if (value == null) {
        settings.remove(HUGGING_FACE_LAST_VERIFIED_KEY)
    } else {
        settings.putString(HUGGING_FACE_LAST_VERIFIED_KEY, value)
    }

    override fun migrateLegacySettings(): Boolean {
        val onboarding = migrateOnboardingCompleted()
        val lastVerified = migrateHuggingFaceLastVerified()
        return onboarding && lastVerified
    }

    private fun migrateOnboardingCompleted(): Boolean {
        val legacyValue = when (
            val result = legacySettings.readBoolean(AppSettingsStore.LEGACY_ONBOARDING_COMPLETED_KEY)
        ) {
            is SecretRecordResult.Success -> result.value
            // An unreadable legacy keyset must not downgrade a completed onboarding into a
            // repeated one silently, but it also must not block the rest of startup.
            SecretRecordResult.Unrecoverable -> return false
            SecretRecordResult.StorageUnavailable -> return false
        } ?: return true

        if (!settings.contains(ONBOARDING_COMPLETED_KEY) && !setOnboardingCompleted(legacyValue)) {
            return false
        }
        return removeLegacy(AppSettingsStore.LEGACY_ONBOARDING_COMPLETED_KEY)
    }

    private fun migrateHuggingFaceLastVerified(): Boolean {
        val legacyValue = when (
            val result = legacySettings.readString(AppSettingsStore.LEGACY_HUGGING_FACE_LAST_VERIFIED_KEY)
        ) {
            is SecretRecordResult.Success -> result.value
            SecretRecordResult.Unrecoverable -> return false
            SecretRecordResult.StorageUnavailable -> return false
        } ?: return true

        if (!settings.contains(HUGGING_FACE_LAST_VERIFIED_KEY) &&
            !setHuggingFaceLastVerified(legacyValue)
        ) {
            return false
        }
        return removeLegacy(AppSettingsStore.LEGACY_HUGGING_FACE_LAST_VERIFIED_KEY)
    }

    private fun removeLegacy(key: String): Boolean =
        legacySettings.remove(key) is SecretRecordResult.Success

    private companion object {
        const val ONBOARDING_COMPLETED_KEY = "onboarding_completed"
        const val HUGGING_FACE_LAST_VERIFIED_KEY = "huggingface_last_verified"
    }
}
