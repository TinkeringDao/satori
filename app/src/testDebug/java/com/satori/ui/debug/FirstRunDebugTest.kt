package com.satori.ui.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * firstrun-reset's log line says what it did, and gives the commands that take the permissions
 * back for sure: Android may keep them when the process ends itself right after asking (design
 * 5.0; the emulator kept the microphone allowed), and the tester revokes them by hand then.
 */
class FirstRunDebugTest {

    private val microphone = "android.permission.RECORD_AUDIO"

    @Test
    fun `with permissions granted it says Android was asked, and how to be sure`() {
        val line = FirstRunDebug.resetReport(2, 5, 1, listOf(microphone, "android.permission.POST_NOTIFICATIONS"), "com.satori")

        assertTrue(line, line.startsWith("First run reset: 2 model files, 5 preference files and 1 data stores deleted."))
        assertTrue(line, "pm revoke com.satori android.permission.RECORD_AUDIO" in line)
        assertTrue(line, "pm revoke com.satori android.permission.POST_NOTIFICATIONS" in line)
        assertTrue(line, "pm clear-permission-flags com.satori android.permission.RECORD_AUDIO user-set user-fixed" in line)
        // It never claims they are gone.
        assertFalse(line, "taken back as the process ends" in line)
    }

    @Test
    fun `with none granted it has nothing to revoke`() {
        val line = FirstRunDebug.resetReport(0, 0, 0, emptyList(), "com.satori")

        assertTrue(line, "No runtime permission was granted." in line)
        assertFalse(line, "pm revoke" in line)
    }
}
