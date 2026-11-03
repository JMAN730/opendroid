package com.opendroid.ai.core.memory

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.opendroid.ai.data.models.MemoryType
import com.opendroid.ai.data.repository.MemoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes the "invalid action" notes that early builds wrote into semantic memory.
 *
 * Before ReEvaluationEngine logged unknown actions to their own table, it stored each one as a
 * SEMANTIC row keyed `invalid_action_<name>`. Those rows are the only target. Matching on phrases
 * like "not registered" or "whitelisted" anywhere in a row also hit user facts and knowledge-graph
 * nodes, so the sweep is limited to that key prefix and runs once per install.
 */
@Singleton
class LegacyPoisonedMemoryCleanup internal constructor(
    private val memoryRepository: MemoryRepository,
    private val preferences: SharedPreferences
) {

    @Inject
    constructor(
        memoryRepository: MemoryRepository,
        @ApplicationContext context: Context
    ) : this(
        memoryRepository = memoryRepository,
        preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    )

    suspend fun cleanPoisonedMemories() {
        if (!preferences.getBoolean(KEY_DONE, false)) {
            val poisoned = memoryRepository.getMemoriesByKeyPrefix(LEGACY_KEY_PREFIX).filter {
                it.type == MemoryType.SEMANTIC && it.key.startsWith(LEGACY_KEY_PREFIX)
            }
            poisoned.forEach { memoryRepository.deleteMemory(it.key) }
            if (poisoned.isNotEmpty()) {
                Log.d(TAG, "Removed ${poisoned.size} legacy invalid-action memory entries")
            }
            // The flag is set only after the deletes, so a crash mid-sweep retries next start.
            preferences.edit { putBoolean(KEY_DONE, true) }
        }

        // Expired rows are cleaned on every start; that sweep never touches live facts.
        memoryRepository.deleteExpiredMemories()
    }

    companion object {
        const val PREFERENCES = "opendroid_memory_maintenance"
        private const val KEY_DONE = "legacy_poison_cleanup_done"
        private const val LEGACY_KEY_PREFIX = "invalid_action_"
        private const val TAG = "MemoryCleanup"
    }
}
