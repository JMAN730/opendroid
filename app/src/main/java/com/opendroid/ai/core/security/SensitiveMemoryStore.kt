package com.opendroid.ai.core.security

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Storage interface for Level 4 Sensitive Memory.
 * Backed by direct AndroidKeyStore AES-256-GCM encryption with strict access boundaries.
 */
interface SensitiveMemoryStore {
    fun read(key: String): String?
    fun write(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun listKeys(): Set<String>
    fun getAllDecrypted(): Map<String, String>
    fun clearAll(): Boolean
}

class AndroidSensitiveMemoryStore(
    context: Context,
    preferenceName: String = PREFERENCES_NAME,
    keyAlias: String = KEY_ALIAS
) : SensitiveMemoryStore {

    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(
            context.applicationContext.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreAeadCipher(keyAlias)
    )

    private val lock = Any()

    override fun read(key: String): String? = synchronized(lock) {
        val aad = "$AAD_PREFIX:$key"
        when (val result = records.read(key, aad)) {
            is SecretRecordResult.Success -> result.value?.let { String(it, StandardCharsets.UTF_8) }
            else -> null
        }
    }

    override fun write(key: String, value: String): Boolean = synchronized(lock) {
        val aad = "$AAD_PREFIX:$key"
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        when (records.write(key, aad, bytes)) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    override fun remove(key: String): Boolean = synchronized(lock) {
        when (records.removeRecord(key)) {
            is SecretRecordResult.Success -> true
            else -> false
        }
    }

    override fun listKeys(): Set<String> = synchronized(lock) {
        when (val result = records.keys()) {
            is SecretRecordResult.Success -> result.value
            else -> emptySet()
        }
    }

    override fun getAllDecrypted(): Map<String, String> = synchronized(lock) {
        val keys = listKeys()
        val map = mutableMapOf<String, String>()
        for (key in keys) {
            read(key)?.let { map[key] = it }
        }
        map
    }

    override fun clearAll(): Boolean = synchronized(lock) {
        val keys = listKeys()
        var allSuccess = true
        for (key in keys) {
            if (!remove(key)) {
                allSuccess = false
            }
        }
        allSuccess
    }

    companion object {
        const val PREFERENCES_NAME = "opendroid_sensitive_memory"
        const val KEY_ALIAS = "opendroid.sensitive_memory.aes_gcm.v1"
        private const val AAD_PREFIX = "sensitive-memory"
    }
}
