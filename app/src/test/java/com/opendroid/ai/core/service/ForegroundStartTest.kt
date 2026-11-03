package com.opendroid.ai.core.service

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartTest {

    // ForegroundServiceStartNotAllowedException is an IllegalStateException; the stub
    // android.jar can't construct it on the JVM, so the tests throw its supertype.
    private fun notAllowed() = IllegalStateException("startForeground() not allowed due to mAllowStartForeground false")

    @Test
    fun `android 14 and later try microphone first and fall back to special use`() {
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
            ForegroundStart.typesFor(sdk = 35, micGranted = true)
        )
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
            ForegroundStart.typesFor(sdk = 34, micGranted = false)
        )
    }

    @Test
    fun `android 11 to 13 use microphone and older versions start untyped`() {
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE), ForegroundStart.typesFor(sdk = 33, micGranted = true))
        assertEquals(listOf<Int?>(null), ForegroundStart.typesFor(sdk = 29, micGranted = true))
    }

    @Test
    fun `a boot start refused for microphone falls back to special use`() {
        val attempted = mutableListOf<Int?>()

        val started = ForegroundStart.startFirstAllowed(ForegroundStart.typesFor(sdk = 35, micGranted = true)) { type ->
            attempted += type
            if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) throw notAllowed()
        }

        assertTrue(started)
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
            attempted
        )
    }

    @Test
    fun `a sticky restart refused for every type reports failure instead of throwing`() {
        val started = ForegroundStart.startFirstAllowed(ForegroundStart.typesFor(sdk = 35, micGranted = true)) {
            throw notAllowed()
        }

        assertFalse(started)
    }

    @Test
    fun `a refused start on android 12 and 13 reports failure instead of throwing`() {
        val started = ForegroundStart.startFirstAllowed(ForegroundStart.typesFor(sdk = 31, micGranted = true)) {
            throw notAllowed()
        }

        assertFalse(started)
    }

    @Test
    fun `a missing mic permission still falls back to special use`() {
        val attempted = mutableListOf<Int?>()

        val started = ForegroundStart.startFirstAllowed(ForegroundStart.typesFor(sdk = 34, micGranted = true)) { type ->
            attempted += type
            if (type == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) throw SecurityException("mic not in use")
        }

        assertTrue(started)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, attempted.last())
    }
}
