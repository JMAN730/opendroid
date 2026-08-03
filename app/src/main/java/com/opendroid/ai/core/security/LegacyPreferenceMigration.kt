package com.opendroid.ai.core.security

import android.content.Context
import com.opendroid.ai.core.settings.AppSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Moves every non-provider value out of the legacy preference files at startup.
 *
 * Each destination store owns its own write-before-delete import, so this type only orders the
 * work and clears the keys the inventory classifies as obsolete. Provider credentials migrate
 * separately from `SettingsRepository`, which owns their retry policy.
 *
 * Every step is idempotent: a crash between the destination commit and the legacy delete leaves a
 * duplicate that the next run resolves in favour of the already-committed destination.
 *
 * The work opens the Keystore and the legacy keyset, so it runs off the main thread. Callers that
 * must observe migrated values - splash routing and onboarding - [await] it first.
 */
@Singleton
class LegacyPreferenceMigration internal constructor(
    private val userProfileStore: UserProfileStore,
    private val appSettingsStore: AppSettingsStore,
    private val legacySources: LegacySecretSource
) {
    @Inject
    constructor(
        userProfileStore: UserProfileStore,
        appSettingsStore: AppSettingsStore,
        @ApplicationContext context: Context
    ) : this(userProfileStore, appSettingsStore, legacyPreferenceSources(context))

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val completion = CompletableDeferred<Boolean>()
    private var started = false

    /** Starts the migration once per process. Safe to call from `Application.onCreate`. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            completion.complete(runCatching { run() }.getOrDefault(false))
        }
    }

    /**
     * Suspends until the migration has finished, so a caller never reads a store the import has
     * not populated yet. Returns the same value as [run].
     */
    suspend fun await(): Boolean {
        start()
        return completion.await()
    }

    /**
     * Returns true when nothing was left behind. A false result is not an error the user needs to
     * see: the legacy value stays where it is and the next startup retries.
     */
    internal fun run(): Boolean {
        val profileMigrated = userProfileStore.migrateLegacyProfile() is ProfileStoreResult.Success
        val settingsMigrated = appSettingsStore.migrateLegacySettings()
        return profileMigrated && settingsMigrated && clearObsoleteKeys()
    }

    /**
     * Deletes only the keys the inventory classifies as obsolete. Anything unrecognised is left
     * alone rather than guessed at, and provider credentials are another migration's business.
     */
    private fun clearObsoleteKeys(): Boolean {
        val remaining = when (val result = legacySources.keys()) {
            is SecretRecordResult.Success -> result.value
            SecretRecordResult.Unrecoverable -> return false
            SecretRecordResult.StorageUnavailable -> return false
        }
        return remaining
            .filter { LegacySecurePreferenceInventory.classify(it) == LegacyValueClass.OBSOLETE }
            .all { legacySources.remove(it) is SecretRecordResult.Success }
    }
}
