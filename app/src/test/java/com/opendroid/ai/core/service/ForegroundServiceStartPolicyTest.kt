package com.opendroid.ai.core.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = true)
            )
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = false)
            )
        }
    }

    @Test
    fun `API 30 to 33 always declares microphone type`() {
        listOf(30, 31, 32, 33).forEach { sdk ->
            assertEquals(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = false)
            )
        }
    }

    @Test
    fun `below API 30 leaves the type to the manifest`() {
        listOf(26, 29).forEach { sdk ->
            assertEquals(
                ForegroundServiceStartPolicy.TYPE_MANIFEST,
                ForegroundServiceStartPolicy.preferredType(sdk, micGranted = true)
            )
        }
    }

    @Test
    fun `fallback type is specialUse only where types exist and differ from preferred`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ForegroundServiceStartPolicy.fallbackType(36, micGranted = true)
        )
        // Nothing to fall back to when specialUse was already the preferred type.
        assertEquals(
            ForegroundServiceStartPolicy.TYPE_NONE,
            ForegroundServiceStartPolicy.fallbackType(36, micGranted = false)
        )
        assertEquals(
            ForegroundServiceStartPolicy.TYPE_NONE,
            ForegroundServiceStartPolicy.fallbackType(29, micGranted = true)
        )
    }
}
