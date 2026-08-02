package com.opendroid.ai.core.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Legacy encrypted preferences for non-provider callers.
 *
 * Provider credentials use [AndroidProviderCredentialStore] instead. There is deliberately no
 * plaintext fallback here. If a legacy keyset is unavailable, this helper leaves the encrypted
 * file untouched rather than inspecting exception messages or deleting a whole preferences file,
 * which would also erase non-provider settings.
 */
object SecurePrefs {

    private const val PREFS_NAME = "opendroid_secure_prefs"
    private const val TAG = "SecurePrefs"

    @Volatile
    private var instance: SharedPreferences? = null

    fun get(context: Context): SharedPreferences = instance ?: synchronized(this) {
        instance ?: buildEncryptedPrefs(context).also { instance = it }
    }

    /**
     * Allows non-provider callers to keep the app usable when the legacy keyset is unreadable.
     * The encrypted file is not modified; provider credential recovery is exposed separately by
     * [ProviderCredentialStore].
     */
    fun getOrNull(context: Context): SharedPreferences? = legacyPreferenceAccessOrNull {
        get(context)
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Centralizes the narrow recovery boundary for callers whose legacy data is optional.
     * This deliberately does not recreate, delete, or downgrade the encrypted preference file.
     */
    internal fun <T> legacyPreferenceAccessOrNull(access: () -> T): T? = try {
        access()
    } catch (_: java.security.GeneralSecurityException) {
        null
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    /**
     * One-time migration from old plaintext "opendroid_prefs" to legacy encrypted storage.
     *
     * This remains for non-provider data. Provider credential migration is performed exclusively
     * by [ProviderCredentialStore.migrateLegacyCredentials], which writes the direct-keystore
     * destination before it removes a legacy credential.
     */
    fun migrateFromPlaintext(context: Context) {
        val oldPrefs = context.getSharedPreferences("opendroid_prefs", Context.MODE_PRIVATE)
        val securePrefs = getOrNull(context) ?: return

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
