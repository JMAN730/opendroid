package com.opendroid.ai.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [AndroidKeyStoreAeadCipher] directly against the real AndroidKeyStore provider.
 *
 * [AndroidProviderCredentialStoreInstrumentationTest] covers the store built on top of this
 * cipher, but nothing previously instantiated [AndroidKeyStoreAeadCipher] itself: the JVM unit
 * tests all use `TestCipher` doubles, which is correct for what they test but leaves the real
 * AndroidKeyStore-backed encrypt/decrypt path with zero coverage. This is that coverage; #105
 * wires it into the API 26/36 CI matrix.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeyStoreAeadCipherInstrumentationTest {
    private val keyAlias = "opendroid.instrumentation.aead.${UUID.randomUUID()}"
    private val cipher = AndroidKeyStoreAeadCipher(keyAlias)

    @After
    fun tearDown() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            if (containsAlias(keyAlias)) deleteEntry(keyAlias)
        }
    }

    @Test
    fun encryptThenDecryptRoundTripsThePlaintext() {
        val plaintext = "instrumentation-round-trip-secret".toByteArray()
        val aad = "credential-id".toByteArray()

        val encrypted = cipher.encrypt(plaintext, aad)

        assertEquals(12, encrypted.iv.size)
        assertNotEquals(
            "ciphertext must not equal plaintext",
            String(plaintext),
            String(encrypted.ciphertext)
        )

        val decrypted = cipher.decrypt(encrypted.iv, encrypted.ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun successiveEncryptionsUseDistinctKeystoreGeneratedIvs() {
        val plaintext = "instrumentation-iv-reuse-check".toByteArray()
        val aad = "credential-id".toByteArray()

        val first = cipher.encrypt(plaintext, aad)
        val second = cipher.encrypt(plaintext, aad)

        assertNotEquals(
            "successive encryptions must not reuse the AndroidKeyStore-generated IV",
            String(first.iv),
            String(second.iv)
        )
    }

    // GCM signals tamper with AEADBadTagException, but that is a GeneralSecurityException, so
    // withKey normalizes it to SecretKeyUnavailableException before it escapes — every unreadable
    // record routes to the same re-entry recovery regardless of why it was unreadable. These tests
    // therefore assert the normalized type and pin the cause, which is what proves GCM actually
    // rejected the ciphertext rather than the key having gone missing.
    @Test
    fun flippedCiphertextByteIsRejectedWithAeadBadTag() {
        val plaintext = "instrumentation-tamper-check".toByteArray()
        val aad = "credential-id".toByteArray()
        val encrypted = cipher.encrypt(plaintext, aad)

        val tampered = encrypted.ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        val thrown = assertThrows(SecretKeyUnavailableException::class.java) {
            cipher.decrypt(encrypted.iv, tampered, aad)
        }
        assertTrue(
            "tamper must be reported by GCM, not by a missing key; was ${thrown.cause}",
            thrown.cause is AEADBadTagException
        )
    }

    @Test
    fun mismatchedCredentialIdAadIsRejected() {
        val plaintext = "instrumentation-aad-check".toByteArray()
        val encrypted = cipher.encrypt(plaintext, "correct-credential-id".toByteArray())

        val thrown = assertThrows(SecretKeyUnavailableException::class.java) {
            cipher.decrypt(encrypted.iv, encrypted.ciphertext, "wrong-credential-id".toByteArray())
        }
        assertTrue(
            "AAD mismatch must be reported by GCM, not by a missing key; was ${thrown.cause}",
            thrown.cause is AEADBadTagException
        )
    }
}
