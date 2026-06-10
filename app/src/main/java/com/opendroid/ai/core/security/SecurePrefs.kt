package com.opendroid.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Centralized secure preferences using Android Keystore + EncryptedSharedPreferences.
 * All API keys and sensitive user data are AES-256 encrypted at rest.
 *
 * Falls back to standard SharedPreferences only if the device doesn't support
 * hardware-backed keystore (extremely rare on API 26+).
 */
object SecurePrefs {

    private const val PREFS_NAME = "opendroid_secure_prefs"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createEncryptedPrefs(context).also { instance = it }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, falling back to standard prefs: ${e.localizedMessage}")
            // Graceful degradation — still better than crashing
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * One-time migration from old plaintext "opendroid_prefs" to encrypted store.
     * Call this once at app startup. Automatically deletes the old file after migration.
     */
    fun migrateFromPlaintext(context: Context) {
        val oldPrefs = context.getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        val securePrefs = get(context)

        // Only migrate if old prefs have data and secure prefs don't yet
        if (oldPrefs.all.isNotEmpty() && !securePrefs.contains("migration_done")) {
            val editor = securePrefs.edit()
            for ((key, value) in oldPrefs.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.putBoolean("migration_done", true)
            editor.apply()

            // Capture count before clearing
            val migratedCount = oldPrefs.all.size

            // Wipe the old plaintext prefs
            oldPrefs.edit().clear().apply()
            Log.d(TAG, "Migrated $migratedCount entries from plaintext to encrypted prefs")
        }
    }
}
