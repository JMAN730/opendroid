package com.opendroid.ai.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAppGuardTest {

    @Test
    fun `the target app in front may be automated`() {
        assertTrue(isTargetAppInFront("com.google.android.apps.messaging", "com.google.android.apps.messaging"))
    }

    @Test
    fun `OpenDroid's own chat screen in front is not the target`() {
        assertFalse(isTargetAppInFront("com.opendroid.ai", "com.google.android.apps.messaging"))
    }

    @Test
    fun `no active window or no known target is a refusal`() {
        assertFalse(isTargetAppInFront(null, "com.google.android.apps.messaging"))
        assertFalse(isTargetAppInFront("com.google.android.apps.messaging", null))
        assertFalse(isTargetAppInFront(null, null))
    }

    @Test
    fun `the system chooser is not a target, so the fallback answers instead`() {
        assertEquals("com.sms.default", resolveTargetPackage("android", "com.sms.default"))
        assertEquals("com.sms.resolved", resolveTargetPackage("com.sms.resolved", "com.sms.default"))
        assertEquals("com.sms.default", resolveTargetPackage(null, "com.sms.default"))
        assertNull(resolveTargetPackage("android", null))
    }
}
