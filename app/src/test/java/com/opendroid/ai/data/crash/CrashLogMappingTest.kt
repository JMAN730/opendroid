package com.opendroid.ai.data.crash

import com.opendroid.ai.core.crash.CrashLogRecord
import com.opendroid.ai.core.crash.DeviceMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashLogMappingTest {

    private val device = DeviceMetadata(
        appVersionName = "1.2.3",
        appVersionCode = 42L,
        androidRelease = "14",
        androidSdkInt = 34,
        deviceManufacturer = "Google",
        deviceModel = "Pixel 8",
    )

    @Test
    fun `record survives a Room entity round trip without unpacking device metadata`() {
        val record = CrashLogRecord(
            timestamp = 1_700_000_000_000L,
            exceptionClass = "java.lang.IllegalStateException",
            message = "boom",
            threadName = "main",
            stackTrace = "java.lang.IllegalStateException: boom",
            device = device,
        )

        assertEquals(record, record.toEntity().toRecord())
    }
}
