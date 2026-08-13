package com.opendroid.ai.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStartPolicyTest {

    @Test
    fun `boot auto start always allowed since a boot-safe type is always reachable`() {
        // microphone FGS is banned from BOOT_COMPLETED on 34+, but preferredType only picks
        // it when RECORD_AUDIO is granted, and fallbackType then offers specialUse - which
        // is never boot-restricted - so OpenDroidService always has a boot-safe type to try.
        listOf(26, 29, 30, 31, 32, 33, 34, 35, 36).forEach { sdk ->
            assertTrue("sdk $sdk", ForegroundServiceStartPolicy.isBootAutoStartAllowed(sdk, micGranted = true))
            assertTrue("sdk $sdk", ForegroundServiceStartPolicy.isBootAutoStartAllowed(sdk, micGranted = false))
        }
    }

    @Test
    fun `Android 14 up uses microphone type only when RECORD_AUDIO granted`() {
        listOf(34, 35, 36).forEach { sdk ->
            assertEquals(
                ForegroundServiceType.MICROPHONE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = true)
            )
            assertEquals(
                ForegroundServiceType.SPECIAL_USE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = false)
            )
        }
    }

    @Test
    fun `API 30 to 33 always declares microphone type`() {
        listOf(30, 31, 32, 33).forEach { sdk ->
            assertEquals(
                ForegroundServiceType.MICROPHONE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = false)
            )
        }
    }

    @Test
    fun `below API 30 leaves the type to the manifest`() {
        listOf(26, 29).forEach { sdk ->
            assertEquals(
                ForegroundServiceType.MANIFEST,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = true)
            )
        }
    }

    @Test
    fun `fallback type is specialUse only where types exist and differ from preferred`() {
        assertEquals(
            ForegroundServiceType.SPECIAL_USE,
            ForegroundServiceStartPolicy.fallbackType(36, micGranted = true)
        )
        // Nothing to fall back to when specialUse was already the preferred type.
        assertEquals(
            ForegroundServiceType.NONE,
            ForegroundServiceStartPolicy.fallbackType(36, micGranted = false)
        )
        assertEquals(
            ForegroundServiceType.NONE,
            ForegroundServiceStartPolicy.fallbackType(29, micGranted = true)
        )
    }

    @Test
    fun `only microphone and manifest starts carry microphone eligibility`() {
        assertTrue(ForegroundServiceStartPolicy.carriesMicrophoneEligibility(ForegroundServiceType.MICROPHONE))
        // Below API 30 the manifest types apply and there is no per-type mic restriction yet.
        assertTrue(ForegroundServiceStartPolicy.carriesMicrophoneEligibility(ForegroundServiceType.MANIFEST))
        assertFalse(ForegroundServiceStartPolicy.carriesMicrophoneEligibility(ForegroundServiceType.SPECIAL_USE))
        assertFalse(ForegroundServiceStartPolicy.carriesMicrophoneEligibility(ForegroundServiceType.NONE))
    }

    @Test
    fun `a specialUse boot fallback can still be promoted once the mic is granted`() {
        // The boot start lands on specialUse because Android 14 bans a microphone FGS from
        // BOOT_COMPLETED; the type OpenDroidService retries on a later start must be microphone,
        // otherwise wake word detection would stay suppressed for the life of the service.
        listOf(34, 35, 36).forEach { sdk ->
            val bootFallback = ForegroundServiceStartPolicy.fallbackType(sdk, micGranted = true)
            assertFalse("sdk $sdk", ForegroundServiceStartPolicy.carriesMicrophoneEligibility(bootFallback))

            val promotion = ForegroundServiceStartPolicy.preferredType(sdk, micGranted = true)
            assertEquals("sdk $sdk", ForegroundServiceType.MICROPHONE, promotion)
            assertTrue("sdk $sdk", ForegroundServiceStartPolicy.carriesMicrophoneEligibility(promotion))
        }
    }

    @Test
    fun `promotion is pointless while RECORD_AUDIO is missing on Android 14 up`() {
        listOf(34, 35, 36).forEach { sdk ->
            val withoutGrant = ForegroundServiceStartPolicy.preferredType(sdk, micGranted = false)
            assertFalse("sdk $sdk", ForegroundServiceStartPolicy.carriesMicrophoneEligibility(withoutGrant))
        }
    }
}
