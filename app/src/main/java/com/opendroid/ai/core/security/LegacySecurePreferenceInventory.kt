package com.opendroid.ai.core.security

import com.opendroid.ai.core.settings.AppSettingsStore

/** How a legacy `opendroid_secure_prefs` entry must be handled when it is retired. */
enum class LegacyValueClass {
    /** Personal data. Stays encrypted at rest in [UserProfileStore]. */
    PROFILE_PII,

    /** Secret material. Owned by [ProviderCredentialStore]. */
    PROVIDER_CREDENTIAL,

    /** Non-secret preference. Moves to [AppSettingsStore]. */
    APP_SETTING,

    /** Bookkeeping from the retired migration engine. Deleted, never re-homed. */
    OBSOLETE
}

/**
 * The complete inventory of keys that were ever written to the legacy encrypted preference file,
 * and the destination each one is classified into.
 *
 * This exists so retiring the legacy store is a checked exercise rather than a survey of call
 * sites: [LegacyPreferenceMigration] purges the keys this classifies as
 * [LegacyValueClass.OBSOLETE], and the tests assert every classified key is claimed by exactly
 * the store its class names.
 */
object LegacySecurePreferenceInventory {

    /** Written by builds before the direct-Keystore stores existed. */
    val classifiedKeys: Map<String, LegacyValueClass> = mapOf(
        UserProfileStore.LEGACY_NAME_KEY to LegacyValueClass.PROFILE_PII,
        UserProfileStore.LEGACY_DATE_OF_BIRTH_KEY to LegacyValueClass.PROFILE_PII,
        AppSettingsStore.LEGACY_ONBOARDING_COMPLETED_KEY to LegacyValueClass.APP_SETTING,
        AppSettingsStore.LEGACY_HUGGING_FACE_LAST_VERIFIED_KEY to LegacyValueClass.APP_SETTING,
        ProviderCredentialId.ElevenLabsApiKey.legacyPreferenceKey to LegacyValueClass.PROVIDER_CREDENTIAL,
        ProviderCredentialId.HuggingFaceToken.legacyPreferenceKey to LegacyValueClass.PROVIDER_CREDENTIAL,
        MIGRATION_DONE_KEY to LegacyValueClass.OBSOLETE
    )

    /**
     * Classifies a legacy key. Provider API keys are a family rather than a fixed key, so they
     * are matched by prefix. An unrecognised key returns null and is deliberately left untouched
     * instead of being guessed at.
     */
    fun classify(key: String): LegacyValueClass? = classifiedKeys[key]
        ?: LegacyValueClass.PROVIDER_CREDENTIAL.takeIf { key.startsWith(PROVIDER_API_KEY_PREFIX) }

    internal const val MIGRATION_DONE_KEY = "migration_done"
    private const val PROVIDER_API_KEY_PREFIX = "llm_api_key_"
}
