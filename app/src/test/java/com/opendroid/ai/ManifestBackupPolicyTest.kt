package com.opendroid.ai

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ManifestBackupPolicyTestApplication : Application()

/**
 * Guards the backup posture set by the security hardening: backups are disabled entirely
 * (`android:allowBackup="false"`), and `backup_rules.xml` / `data_extraction_rules.xml` are
 * only defense in depth. A manifest rewrite once flipped the flag back to `true` (#86), which
 * put the agent workspace, background photos and downloaded model files into Auto Backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ManifestBackupPolicyTestApplication::class)
class ManifestBackupPolicyTest {

    @Test
    fun `merged manifest keeps backups disabled`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val flags = context.applicationInfo.flags

        assertEquals(
            "android:allowBackup must stay \"false\"; see backup_rules.xml",
            0,
            flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }
}
