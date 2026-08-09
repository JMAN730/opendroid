package com.opendroid.ai.core.service

import android.content.SharedPreferences
import com.opendroid.ai.core.security.EncryptedSecret
import com.opendroid.ai.core.security.KeystoreSecretRecords
import com.opendroid.ai.core.security.SecretAeadCipher
import com.opendroid.ai.core.security.SecretRecordStorage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class McpConfigStoreTest {

    @Test
    fun `headers round trip through encrypted config store`() {
        val storage = InMemorySecretStorage()
        val preferences = InMemorySharedPreferences()
        val store = McpConfigStore.createForTest(
            records = KeystoreSecretRecords(storage, TestAeadCipher()),
            preferences = preferences
        )
        val expected = McpEndpointConfig(
            name = "local",
            url = "http://127.0.0.1:9000/mcp",
            enabled = true,
            headers = mapOf("Authorization" to "Bearer secret", "X-Test" to "value")
        )

        store.upsert(expected)

        assertEquals(expected, store.list().single())
        // Ciphertext only at rest — plaintext Authorization must not appear in storage raw values.
        assertEquals(false, storage.records.values.any { it.contains("Bearer secret") })
    }

    @Test
    fun `access token migrates from plaintext prefs into keystore envelopes`() {
        val storage = InMemorySecretStorage()
        val preferences = InMemorySharedPreferences(mutableMapOf("access_token" to "plaintext-token-abc"))
        val store = McpConfigStore.createForTest(
            records = KeystoreSecretRecords(storage, TestAeadCipher()),
            preferences = preferences
        )

        assertEquals("plaintext-token-abc", store.accessToken())
        assertEquals("plaintext-token-abc", store.accessToken())
        assertEquals(true, storage.records.containsKey("access_token"))
        assertEquals(false, storage.records["access_token"]!!.contains("plaintext-token-abc"))
    }

    private class InMemorySecretStorage : SecretRecordStorage {
        val records = linkedMapOf<String, String>()

        override fun read(key: String): String? = records[key]

        override fun write(key: String, value: String): Boolean {
            records[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            records.remove(key)
            return true
        }

        override fun keys(): Set<String> = records.keys.toSet()
    }

    private class TestAeadCipher : SecretAeadCipher {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private val random = SecureRandom()

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return EncryptedSecret(iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        override fun resetForReentry() = Unit
    }

    /**
     * Minimal SharedPreferences for migration; only getString/putString paths matter.
     */
    private class InMemorySharedPreferences(
        private val values: MutableMap<String, Any?> = mutableMapOf()
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = values
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) values[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) values.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }
}
