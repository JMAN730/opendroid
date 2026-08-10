package com.opendroid.ai.core.security

import com.opendroid.ai.core.settings.AppSettingsRecordStorage
import com.opendroid.ai.core.settings.AppSettingsStore
import com.opendroid.ai.core.settings.AppSettingsStoreImpl
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers retiring `SecurePrefs` for the non-provider callers: the complete key inventory, the
 * crash-safe migration ordering, the no-plaintext-at-rest guarantee, and the recovery paths.
 */
class LegacySecurePreferencesRetirementTest {

    // ---------------------------------------------------------------- caller inventory

    @Test
    fun `every legacy key a caller ever wrote is classified`() {
        val callerKeys = listOf(
            "user_name",
            "user_dob",
            "onboarding_completed",
            "huggingface_last_verified",
            "migration_done",
            "elevenlabs_api_key",
            "huggingface_token",
            "llm_api_key_OpenAI"
        )

        for (key in callerKeys) {
            assertTrue(
                "Legacy key '$key' is unclassified",
                LegacySecurePreferenceInventory.classify(key) != null
            )
        }
        // An unknown key is deliberately left alone rather than guessed into a destination.
        assertNull(LegacySecurePreferenceInventory.classify("some_future_key"))
    }

    @Test
    fun `profile details are classified as PII and owned by the encrypted profile store`() {
        val profileKeys = LegacySecurePreferenceInventory.classifiedKeys
            .filterValues { it == LegacyValueClass.PROFILE_PII }
            .keys

        assertEquals(
            setOf(UserProfileStore.LEGACY_NAME_KEY, UserProfileStore.LEGACY_DATE_OF_BIRTH_KEY),
            profileKeys
        )
    }

    @Test
    fun `only non-secret keys are classified as ordinary app settings`() {
        val settingKeys = LegacySecurePreferenceInventory.classifiedKeys
            .filterValues { it == LegacyValueClass.APP_SETTING }
            .keys

        assertEquals(
            setOf(
                AppSettingsStore.LEGACY_ONBOARDING_COMPLETED_KEY,
                AppSettingsStore.LEGACY_HUGGING_FACE_LAST_VERIFIED_KEY
            ),
            settingKeys
        )
        // The Hugging Face token itself stays a credential; only its verification timestamp is
        // downgraded to an ordinary setting.
        assertEquals(
            LegacyValueClass.PROVIDER_CREDENTIAL,
            LegacySecurePreferenceInventory.classify("huggingface_token")
        )
    }

    @Test
    fun `provider api keys are matched as a family rather than one fixed key`() {
        assertEquals(
            LegacyValueClass.PROVIDER_CREDENTIAL,
            LegacySecurePreferenceInventory.classify("llm_api_key_Anthropic Claude")
        )
    }

    // ---------------------------------------------------------------- profile at rest

    @Test
    fun `profile at rest is a versioned ciphertext envelope and never contains plaintext`() {
        val records = InMemoryRecords()
        val store = newProfileStore(records = records)

        assertTrue(
            store.write(UserProfile(name = "Ada Lovelace", dateOfBirth = "12/10/1815")) is
                ProfileStoreResult.Success
        )

        val rawRecord = records.records.values.single()
        assertTrue(rawRecord.startsWith("v1."))
        assertFalse(rawRecord.contains("Ada Lovelace"))
        assertFalse(rawRecord.contains("12/10/1815"))
        assertEquals(
            UserProfile(name = "Ada Lovelace", dateOfBirth = "12/10/1815"),
            (store.read() as ProfileStoreResult.Success).value
        )
    }

    @Test
    fun `tampered profile ciphertext requires reentry rather than returning a value`() {
        val records = InMemoryRecords()
        val store = newProfileStore(records = records)
        store.write(UserProfile(name = "Ada", dateOfBirth = "12/10/1815"))

        val storageKey = records.records.keys.single()
        val parts = records.records.getValue(storageKey).split('.')
        val ciphertext = Base64.getUrlDecoder().decode(parts[2])
        ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
        records.records[storageKey] = "${parts[0]}.${parts[1]}." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)

        assertEquals(ProfileStoreResult.ProfileMustBeReentered, store.read())
    }

    @Test
    fun `a profile payload this version cannot decode requires reentry`() {
        assertNull(UserProfileStoreImpl.decodeProfile(byteArrayOf()))
        assertNull(UserProfileStoreImpl.decodeProfile(byteArrayOf(9, 0, 0, 0, 0)))
        // A declared field length that runs past the payload must not be trusted.
        assertNull(UserProfileStoreImpl.decodeProfile(byteArrayOf(1, 0, 0, 0, 8, 65)))
        // Trailing bytes beyond the declared fields are rejected too.
        assertNull(UserProfileStoreImpl.decodeProfile(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 7)))
    }

    @Test
    fun `profile encoding round-trips values that share the field separator characters`() {
        val profile = UserProfile(name = "a.b c", dateOfBirth = "")
        assertEquals(
            profile,
            UserProfileStoreImpl.decodeProfile(UserProfileStoreImpl.encodeProfile(profile))
        )
    }

    // ---------------------------------------------------------------- profile migration

    @Test
    fun `legacy profile is committed to the destination before the source is removed`() {
        val events = mutableListOf<String>()
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("user_name" to "Ada", "user_dob" to "12/10/1815"),
            events = events
        )
        val records = InMemoryRecords(events = events)
        val store = newProfileStore(records = records, legacy = legacy)

        assertTrue(store.migrateLegacyProfile() is ProfileStoreResult.Success)

        assertEquals(
            listOf("destination-write", "legacy-remove:user_name", "legacy-remove:user_dob"),
            events
        )
        assertNull(legacy.strings["user_name"])
        assertNull(legacy.strings["user_dob"])
        assertEquals(
            UserProfile(name = "Ada", dateOfBirth = "12/10/1815"),
            (store.read() as ProfileStoreResult.Success).value
        )
    }

    @Test
    fun `rerunning the profile migration writes nothing new`() {
        val events = mutableListOf<String>()
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("user_name" to "Ada", "user_dob" to "12/10/1815"),
            events = events
        )
        val store = newProfileStore(records = InMemoryRecords(events = events), legacy = legacy)

        store.migrateLegacyProfile()
        val afterFirstRun = events.toList()
        store.migrateLegacyProfile()

        assertEquals(afterFirstRun, events)
    }

    @Test
    fun `a legacy duplicate left by a crash resolves in favour of the committed destination`() {
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("user_name" to "Stale", "user_dob" to "01/01/1900")
        )
        val records = InMemoryRecords()
        val store = newProfileStore(records = records, legacy = legacy)
        store.write(UserProfile(name = "Current", dateOfBirth = "12/10/1815"))

        assertTrue(store.migrateLegacyProfile() is ProfileStoreResult.Success)

        assertEquals(
            UserProfile(name = "Current", dateOfBirth = "12/10/1815"),
            (store.read() as ProfileStoreResult.Success).value
        )
        assertNull(legacy.strings["user_name"])
    }

    @Test
    fun `legacy profile survives a destination that cannot commit`() {
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("user_name" to "Ada", "user_dob" to "12/10/1815")
        )
        val store = newProfileStore(records = InMemoryRecords(writable = false), legacy = legacy)

        assertEquals(ProfileStoreResult.StorageUnavailable, store.migrateLegacyProfile())
        assertEquals("Ada", legacy.strings["user_name"])
        assertFalse(legacy.events.any { it.startsWith("legacy-remove") })
    }

    @Test
    fun `an unreadable legacy keyset never yields a plaintext profile`() {
        val records = InMemoryRecords()
        val store = newProfileStore(records = records, legacy = UnreadableLegacySource())

        assertEquals(ProfileStoreResult.ProfileMustBeReentered, store.migrateLegacyProfile())
        assertTrue(records.records.isEmpty())
    }

    @Test
    fun `an unreadable legacy keyset is not a lost profile once the destination holds one`() {
        val records = InMemoryRecords()
        val store = newProfileStore(records = records, legacy = UnreadableLegacySource())
        store.write(UserProfile(name = "Ada", dateOfBirth = "12/10/1815"))

        assertTrue(store.migrateLegacyProfile() is ProfileStoreResult.Success)
        assertEquals(
            UserProfile(name = "Ada", dateOfBirth = "12/10/1815"),
            (store.read() as ProfileStoreResult.Success).value
        )
    }

    // ---------------------------------------------------------------- profile recovery

    @Test
    fun `an invalidated Keystore key surfaces reentry and recovery restores writability`() {
        val cipher = TestCipher().apply { keyAvailable = false }
        val store = newProfileStore(cipher = cipher)

        assertEquals(
            ProfileStoreResult.ProfileMustBeReentered,
            store.write(UserProfile(name = "Ada", dateOfBirth = "12/10/1815"))
        )

        assertTrue(store.resetForReentry() is ProfileStoreResult.Success)
        assertTrue(
            store.write(UserProfile(name = "Ada", dateOfBirth = "12/10/1815")) is
                ProfileStoreResult.Success
        )
    }

    @Test
    fun `profile recovery removes only the profile record`() {
        val records = InMemoryRecords()
        records.records["unrelated-setting"] = "preserve-me"
        val store = newProfileStore(records = records)
        store.write(UserProfile(name = "Ada", dateOfBirth = "12/10/1815"))

        assertTrue(store.resetForReentry() is ProfileStoreResult.Success)

        assertNull(records.records[UserProfileStoreImpl.STORAGE_KEY])
        assertEquals("preserve-me", records.records["unrelated-setting"])
    }

    // ---------------------------------------------------------------- app settings

    @Test
    fun `non-secret settings migrate destination-first and reruns are idempotent`() {
        val events = mutableListOf<String>()
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("huggingface_last_verified" to "Today 3:15 PM"),
            booleans = mutableMapOf("onboarding_completed" to true),
            events = events
        )
        val settings = InMemorySettings(events = events)
        val store = AppSettingsStoreImpl(settings, legacy)

        assertTrue(store.migrateLegacySettings())

        assertEquals(
            listOf(
                "settings-write:onboarding_completed",
                "legacy-remove:onboarding_completed",
                "settings-write:huggingface_last_verified",
                "legacy-remove:huggingface_last_verified"
            ),
            events
        )
        assertTrue(store.isOnboardingCompleted())
        assertEquals("Today 3:15 PM", store.huggingFaceLastVerified())

        store.migrateLegacySettings()
        assertEquals(4, events.size)
    }

    @Test
    fun `settings migration leaves legacy values in place when the destination cannot commit`() {
        val legacy = InMemoryLegacySource(
            booleans = mutableMapOf("onboarding_completed" to true)
        )
        val store = AppSettingsStoreImpl(InMemorySettings(writable = false), legacy)

        assertFalse(store.migrateLegacySettings())
        assertEquals(true, legacy.booleans["onboarding_completed"])
        assertFalse(legacy.events.any { it.startsWith("legacy-remove") })
    }

    @Test
    fun `an unreadable legacy keyset leaves onboarding state untouched rather than guessing`() {
        val settings = InMemorySettings()
        val store = AppSettingsStoreImpl(settings, UnreadableLegacySource())

        assertFalse(store.migrateLegacySettings())
        assertFalse(settings.booleans.containsKey("onboarding_completed"))
        assertFalse(store.isOnboardingCompleted())
    }

    @Test
    fun `the settings store never claims a profile or credential key`() {
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf(
                "user_name" to "Ada",
                "user_dob" to "12/10/1815",
                "huggingface_token" to "hf-secret"
            )
        )
        val settings = InMemorySettings()
        AppSettingsStoreImpl(settings, legacy).migrateLegacySettings()

        assertTrue(settings.strings.isEmpty())
        assertEquals("Ada", legacy.strings["user_name"])
        assertEquals("hf-secret", legacy.strings["huggingface_token"])
    }

    // ---------------------------------------------------------------- startup migration

    @Test
    fun `startup migration re-homes both destinations and clears obsolete bookkeeping`() {
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf(
                "user_name" to "Ada",
                "user_dob" to "12/10/1815",
                "huggingface_last_verified" to "Today 3:15 PM"
            ),
            booleans = mutableMapOf("onboarding_completed" to true, "migration_done" to true)
        )
        val profileStore = newProfileStore(legacy = legacy)
        val settingsStore = AppSettingsStoreImpl(InMemorySettings(), legacy)

        assertTrue(LegacyPreferenceMigration(profileStore, settingsStore, legacy).run())

        assertTrue(legacy.strings.isEmpty())
        assertTrue(legacy.booleans.isEmpty())
        assertTrue(settingsStore.isOnboardingCompleted())
        assertEquals(
            UserProfile(name = "Ada", dateOfBirth = "12/10/1815"),
            (profileStore.read() as ProfileStoreResult.Success).value
        )
    }

    @Test
    fun `startup migration reports incomplete work so the next start retries`() {
        val legacy = InMemoryLegacySource(
            strings = mutableMapOf("user_name" to "Ada")
        )
        val profileStore = newProfileStore(records = InMemoryRecords(writable = false), legacy = legacy)
        val settingsStore = AppSettingsStoreImpl(InMemorySettings(), legacy)

        assertFalse(LegacyPreferenceMigration(profileStore, settingsStore, legacy).run())
        assertEquals("Ada", legacy.strings["user_name"])
    }

    // ---------------------------------------------------------------- dependency boundary

    @Test
    fun `SecurePrefs is gone and no caller references it`() {
        assertFalse(File("src/main/java/com/opendroid/ai/core/security/SecurePrefs.kt").exists())
        assertTrue(mainSourcesMentioning("SecurePrefs").isEmpty())
    }

    @Test
    fun `security crypto has no production references`() {
        val forbiddenTokens = listOf(
            "EncryptedSharedPreferences",
            "MasterKey",
            "androidx.security.crypto"
        )

        for (token in forbiddenTokens) {
            assertTrue("Production source references $token", mainSourcesMentioning(token).isEmpty())
        }
        assertFalse(
            File("build.gradle").readText().contains("androidx.security:" + "security-crypto")
        )
        assertFalse(File("proguard-rules.pro").readText().contains("androidx.security.crypto"))
    }

    @Test
    fun `every importer reads the plaintext legacy source so values can be safely rehomed`() {
        val importers = listOf(
            "src/main/java/com/opendroid/ai/core/security/ProviderCredentialStore.kt",
            "src/main/java/com/opendroid/ai/core/security/UserProfileStore.kt",
            "src/main/java/com/opendroid/ai/core/settings/AppSettingsStore.kt"
        )

        for (path in importers) {
            assertTrue(
                "$path must import through legacyPreferenceSources",
                File(path).readText().contains("legacyPreferenceSources(context)")
            )
        }
    }

    private fun mainSourcesMentioning(token: String): Set<String> =
        File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(token) }
            .map { it.name }
            .toSet()

    // ---------------------------------------------------------------- fakes

    private fun newProfileStore(
        records: InMemoryRecords = InMemoryRecords(),
        cipher: TestCipher = TestCipher(),
        legacy: LegacySecretSource = InMemoryLegacySource()
    ): UserProfileStoreImpl = UserProfileStoreImpl(KeystoreSecretRecords(records, cipher), legacy)

    private class InMemoryRecords(
        private val writable: Boolean = true,
        val events: MutableList<String> = mutableListOf()
    ) : SecretRecordStorage {
        val records = linkedMapOf<String, String>()

        override fun read(key: String): String? = records[key]

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

    private class InMemorySettings(
        private val writable: Boolean = true,
        val events: MutableList<String> = mutableListOf()
    ) : AppSettingsRecordStorage {
        val strings = linkedMapOf<String, String>()
        val booleans = linkedMapOf<String, Boolean>()

        override fun contains(key: String): Boolean = strings.containsKey(key) || booleans.containsKey(key)

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            booleans[key] ?: defaultValue

        override fun putBoolean(key: String, value: Boolean): Boolean {
            events += "settings-write:$key"
            if (!writable) return false
            booleans[key] = value
            return true
        }

        override fun getString(key: String): String? = strings[key]

        override fun putString(key: String, value: String): Boolean {
            events += "settings-write:$key"
            if (!writable) return false
            strings[key] = value
            return true
        }

        override fun remove(key: String): Boolean {
            strings.remove(key)
            booleans.remove(key)
            return true
        }
    }

    private class InMemoryLegacySource(
        val strings: MutableMap<String, String> = mutableMapOf(),
        val booleans: MutableMap<String, Boolean> = mutableMapOf(),
        val events: MutableList<String> = mutableListOf()
    ) : LegacySecretSource {
        override fun keys(): SecretRecordResult<Set<String>> =
            SecretRecordResult.Success(strings.keys + booleans.keys)

        override fun readString(key: String): SecretRecordResult<String?> =
            SecretRecordResult.Success(strings[key])

        override fun readBoolean(key: String): SecretRecordResult<Boolean?> =
            SecretRecordResult.Success(booleans[key])

        override fun remove(key: String): SecretRecordResult<Unit> {
            if (strings.containsKey(key) || booleans.containsKey(key)) {
                events += "legacy-remove:$key"
            }
            strings.remove(key)
            booleans.remove(key)
            return SecretRecordResult.Success(Unit)
        }
    }

    private class UnreadableLegacySource : LegacySecretSource {
        override fun keys(): SecretRecordResult<Set<String>> = SecretRecordResult.Unrecoverable

        override fun readString(key: String): SecretRecordResult<String?> =
            SecretRecordResult.Unrecoverable

        override fun readBoolean(key: String): SecretRecordResult<Boolean?> =
            SecretRecordResult.Unrecoverable

        override fun remove(key: String): SecretRecordResult<Unit> = SecretRecordResult.Unrecoverable
    }

    private class TestCipher : SecretAeadCipher {
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
