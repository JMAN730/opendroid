package com.opendroid.ai.core.security

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidProviderCredentialStoreInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = UUID.randomUUID().toString()
    private val preferencesName = "provider-credentials-instrumentation-$testId"
    private val keyAlias = "opendroid.instrumentation.$testId"

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    @Test
    fun realAndroidKeyStoreUsesAesGcmAndRoundTripsVersionedCredentialEnvelopes() {
        val store = AndroidProviderCredentialStore(context, preferencesName, keyAlias)
        val credential = ProviderCredentialId.ApiKey("OpenAI")
        val firstSecret = "instrumentation-secret-one"
        val secondSecret = "instrumentation-secret-two"

        assertTrue(store.write(credential, firstSecret) is CredentialStoreResult.Success)
        val firstEnvelope = rawEnvelope()
        assertTrue(firstEnvelope.startsWith("v1."))
        assertFalse(firstEnvelope.contains(firstSecret))
        assertEquals(firstSecret, (store.read(credential) as CredentialStoreResult.Success).value)

        assertTrue(store.write(credential, firstSecret) is CredentialStoreResult.Success)
        val rewrittenEnvelope = rawEnvelope()
        assertNotEquals(firstEnvelope, rewrittenEnvelope)
        assertFalse(rewrittenEnvelope.contains(firstSecret))

        assertTrue(store.write(credential, secondSecret) is CredentialStoreResult.Success)
        val secondEnvelope = rawEnvelope()
        assertNotEquals(rewrittenEnvelope, secondEnvelope)
        assertFalse(secondEnvelope.contains(secondSecret))
        assertEquals(secondSecret, (store.read(credential) as CredentialStoreResult.Success).value)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(keyAlias, null)
        assertNotNull(key)
        // KeyFactory handles asymmetric keys only; a symmetric AES SecretKey needs
        // SecretKeyFactory, or the provider answers "no such algorithm: AES".
        val keyInfo = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key as SecretKey, KeyInfo::class.java) as KeyInfo
        assertEquals(256, keyInfo.keySize)
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            keyInfo.purposes
        )
        assertTrue(keyInfo.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)))
        assertTrue(
            keyInfo.encryptionPaddings.contentEquals(
                arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE)
            )
        )
        assertFalse(keyInfo.isUserAuthenticationRequired)
    }

    @Test
    fun nonStringDirectPreferenceRecordSurfacesCredentialReentry() {
        val store = AndroidProviderCredentialStore(context, preferencesName, keyAlias)
        val credential = ProviderCredentialId.HuggingFaceToken
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putInt(credential.storageKey, 7)
            .commit()

        assertEquals(CredentialStoreResult.CredentialsMustBeReentered, store.read(credential))
        assertEquals(
            ProviderCredentialRecoveryState.CredentialsMustBeReentered,
            store.recoveryState.value
        )
    }

    private fun rawEnvelope(): String = context
        .getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        .all
        .values
        .single() as String
}
