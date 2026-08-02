package com.opendroid.ai.core.security

import java.security.SecureRandom
import java.util.Base64
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCredentialStoreTest {

    @Test
    fun `credential at rest is a versioned ciphertext envelope and never contains plaintext`() {
        val records = InMemoryCredentialRecords()
        val store = newStore(records = records)
        val credential = ProviderCredentialId.ApiKey("OpenAI")
        val secret = "sk-test-secret-that-must-not-be-persisted"

        assertTrue(store.write(credential, secret) is CredentialStoreResult.Success)

        val rawRecord = records.records.values.single()
        assertTrue(rawRecord.startsWith("v1."))
        assertFalse(rawRecord.contains(secret))
        assertEquals(
            secret,
            (store.read(credential) as CredentialStoreResult.Success).value
        )
    }

    @Test
    fun `tampered AES GCM ciphertext is rejected rather than returned`() {
        val records = InMemoryCredentialRecords()
        val store = newStore(records = records)
        val credential = ProviderCredentialId.ApiKey("OpenAI")

        store.write(credential, "original-secret")
        val storageKey = records.records.keys.single()
        val parts = records.records.getValue(storageKey).split('.')
        val ciphertext = Base64.getUrlDecoder().decode(parts[2])
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
        records.records[storageKey] = "${parts[0]}.${parts[1]}." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(credential))
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            store.recoveryState.value
        )
    }

    @Test
    fun `ciphertext is bound to its logical credential ID through authenticated data`() {
        val records = InMemoryCredentialRecords()
        val store = newStore(records = records)
        val openAi = ProviderCredentialId.ApiKey("OpenAI")
        val anthropic = ProviderCredentialId.ApiKey("Anthropic Claude")

        store.write(openAi, "openai-secret")
        store.write(anthropic, "anthropic-secret")
        records.records[anthropic.storageKey] = records.records.getValue(openAi.storageKey)

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(anthropic))
    }

    @Test
    fun `malformed and unknown envelope versions require credential reentry`() {
        val records = InMemoryCredentialRecords()
        val credential = ProviderCredentialId.ElevenLabsApiKey
        val store = newStore(records = records)

        records.records[credential.storageKey] = "v2.invalid.invalid"
        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(credential))

        records.records[credential.storageKey] = "v1.not-base64"
        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(credential))
    }

    @Test
    fun `non string direct record requires credential reentry so recovery UI is reachable`() {
        val records = InMemoryCredentialRecords()
        val credential = ProviderCredentialId.HuggingFaceToken
        records.malformedKeys += credential.storageKey
        val store = newStore(records = records)

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(credential))
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            store.recoveryState.value
        )
    }

    @Test
    fun `legacy credentials are committed to destination before source removal and retries are idempotent`() {
        val events = mutableListOf<String>()
        val credential = ProviderCredentialId.ApiKey("OpenAI")
        val legacy = InMemoryLegacyCredentials(
            credentials = mutableMapOf(credential.legacyPreferenceKey to "legacy-secret"),
            events = events
        )
        val records = InMemoryCredentialRecords(events = events)
        val store = newStore(records = records, legacy = legacy)

        assertTrue(store.migrateLegacyCredentials() is CredentialStoreResult.Success)

        assertEquals(listOf("destination-write", "legacy-remove"), events)
        assertNull(legacy.credentials[credential.legacyPreferenceKey])
        assertEquals(
            "legacy-secret",
            (store.read(credential) as CredentialStoreResult.Success).value
        )

        store.migrateLegacyCredentials()
        assertEquals(listOf("destination-write", "legacy-remove"), events)
    }

    @Test
    fun `legacy source remains intact when destination cannot commit`() {
        val credential = ProviderCredentialId.HuggingFaceToken
        val legacy = InMemoryLegacyCredentials(
            credentials = mutableMapOf(credential.legacyPreferenceKey to "hf-secret")
        )
        val records = InMemoryCredentialRecords(writable = false)
        val store = newStore(records = records, legacy = legacy)

        assertEquals(CredentialStoreResult.StorageUnavailable, store.migrateLegacyCredentials())
        assertEquals("hf-secret", legacy.credentials[credential.legacyPreferenceKey])
        assertFalse(legacy.events.contains("legacy-remove"))
    }

    @Test
    fun `unavailable key surfaces a recoverable credentials must be reentered state`() {
        val cipher = TestAeadCipher().apply { keyAvailable = false }
        val store = newStore(cipher = cipher)

        assertEquals(
            CredentialStoreResult.CredentialsMustBeReentered,
            store.write(ProviderCredentialId.HuggingFaceToken, "hf-secret")
        )
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            store.recoveryState.value
        )
    }

    @Test
    fun `unavailable legacy credential source surfaces reentry without reading a plaintext fallback`() {
        val store = ProviderCredentialStoreImpl(
            records = KeystoreSecretRecords(InMemoryCredentialRecords(), TestAeadCipher()),
            legacyCredentials = UnavailableLegacyCredentials()
        )

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.migrateLegacyCredentials())
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            store.recoveryState.value
        )
    }

    @Test
    fun `explicit recovery lets a user store credentials again without deleting legacy preferences`() {
        val legacy = InMemoryLegacyCredentials(
            credentials = mutableMapOf("huggingface_token" to "legacy-secret")
        )
        val cipher = TestAeadCipher().apply { keyAvailable = false }
        val store = newStore(cipher = cipher, legacy = legacy)

        assertEquals(
            CredentialStoreResult.CredentialsMustBeReentered,
            store.write(ProviderCredentialId.HuggingFaceToken, "new-secret")
        )
        assertTrue(store.resetForReentry() is CredentialStoreResult.Success)
        assertEquals(ProviderCredentialRecoveryState.Ready, store.recoveryState.value)
        assertEquals("legacy-secret", legacy.credentials["huggingface_token"])
        assertTrue(
            store.write(ProviderCredentialId.HuggingFaceToken, "new-secret") is
                CredentialStoreResult.Success
        )
    }

    @Test
    fun `recovery removes only malformed provider records instead of clearing the preferences file`() {
        val records = InMemoryCredentialRecords()
        records.records["credential.not-valid-base64"] = "v1.bad.bad"
        records.records["unrelated-setting"] = "preserve-me"
        val store = newStore(records = records)

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.readProviderApiKeys())
        assertTrue(store.resetForReentry() is CredentialStoreResult.Success)
        assertFalse(records.records.containsKey("credential.not-valid-base64"))
        assertEquals("preserve-me", records.records["unrelated-setting"])
        assertTrue(store.readProviderApiKeys() is CredentialStoreResult.Success)
    }

    @Test
    fun `backup rules exclude the shared preference domain used by provider credential envelopes`() {
        val backupRules = File("src/main/res/xml/backup_rules.xml").readText()
        val dataExtractionRules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(backupRules.contains("<exclude domain=\"sharedpref\" path=\".\"/>"))
        assertTrue(dataExtractionRules.contains("<cloud-backup>"))
        assertTrue(dataExtractionRules.contains("<device-transfer>"))
        assertEquals(
            2,
            "<exclude domain=\"sharedpref\" path=\".\"/>".toRegex()
                .findAll(dataExtractionRules)
                .count()
        )
    }

    private fun newStore(
        records: InMemoryCredentialRecords = InMemoryCredentialRecords(),
        cipher: TestAeadCipher = TestAeadCipher(),
        legacy: InMemoryLegacyCredentials = InMemoryLegacyCredentials()
    ): ProviderCredentialStoreImpl =
        ProviderCredentialStoreImpl(KeystoreSecretRecords(records, cipher), legacy)

    private class InMemoryCredentialRecords(
        private val writable: Boolean = true,
        val events: MutableList<String> = mutableListOf()
    ) : SecretRecordStorage {
        val records = linkedMapOf<String, String>()
        val malformedKeys = mutableSetOf<String>()

        override fun read(key: String): String? {
            if (key in malformedKeys) throw SecretRecordMalformedException()
            return records[key]
        }

        override fun write(key: String, value: String): Boolean {
            events += "destination-write"
            if (!writable) return false
            records[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            records.remove(key)
            return true
        }

        override fun keys(): Set<String> = records.keys.toSet()
    }

    private class InMemoryLegacyCredentials(
        val credentials: MutableMap<String, String> = mutableMapOf(),
        val events: MutableList<String> = mutableListOf()
    ) : LegacySecretSource {
        override fun keys(): SecretRecordResult<Set<String>> =
            SecretRecordResult.Success(credentials.keys.toSet())

        override fun readString(key: String): SecretRecordResult<String?> =
            SecretRecordResult.Success(credentials[key])

        override fun readBoolean(key: String): SecretRecordResult<Boolean?> =
            SecretRecordResult.Success(null)

        override fun remove(key: String): SecretRecordResult<Unit> {
            events += "legacy-remove"
            credentials.remove(key)
            return SecretRecordResult.Success(Unit)
        }
    }

    private class UnavailableLegacyCredentials : LegacySecretSource {
        override fun keys(): SecretRecordResult<Set<String>> = SecretRecordResult.Unrecoverable

        override fun readString(key: String): SecretRecordResult<String?> =
            SecretRecordResult.Unrecoverable

        override fun readBoolean(key: String): SecretRecordResult<Boolean?> =
            SecretRecordResult.Unrecoverable

        override fun remove(key: String): SecretRecordResult<Unit> = SecretRecordResult.Unrecoverable
    }

    private class TestAeadCipher : SecretAeadCipher {
        private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        private val random = SecureRandom()
        var keyAvailable = true

        override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedSecret {
            checkKeyAvailable()
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return EncryptedSecret(iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(iv: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            checkKeyAvailable()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }

        override fun resetForReentry() {
            keyAvailable = true
        }

        private fun checkKeyAvailable() {
            if (!keyAvailable) throw SecretKeyUnavailableException()
        }
    }
}
