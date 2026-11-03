package com.opendroid.ai.core.security

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Backups stay off (#86). Workspace documents, background photos and model files live outside the
 * sharedpref/database/datastore exclusions, so these guard the two settings that keep them on the device.
 */
@RunWith(RobolectricTestRunner::class)
class BackupPostureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `merged manifest does not allow backup`() {
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun `device transfer excludes app files and external files`() {
        // Some manufacturers keep device transfer on despite allowBackup=false, so it needs its own rules.
        val deviceTransfer = File("src/main/res/xml/data_extraction_rules.xml").readText()
            .substringAfter("<device-transfer>")
            .substringBefore("</device-transfer>")

        assertTrue(deviceTransfer.contains("<exclude domain=\"file\" path=\".\"/>"))
        assertTrue(deviceTransfer.contains("<exclude domain=\"external\" path=\".\"/>"))
    }
}
