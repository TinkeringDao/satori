package com.satori.service.reminders

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishingResumeTest {

    @Test
    fun `a reboot resumes under the wake job, scheduled before the broadcast ends`() {
        val resume = FinishingResume.of(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(FinishingResume.BOOT, resume)
        assertFalse(resume!!.mayStartForeground)
        assertTrue(resume.schedulesWake)
    }

    @Test
    fun `an update resumes with the AI's service`() {
        val resume = FinishingResume.of(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertEquals(FinishingResume.UPDATE, resume)
        assertTrue(resume!!.mayStartForeground)
        assertFalse(resume.schedulesWake)
    }

    @Test
    fun `the other broadcasts only rearm the reminders`() {
        assertNull(FinishingResume.of(Intent.ACTION_TIMEZONE_CHANGED))
        assertNull(FinishingResume.of(Intent.ACTION_TIME_CHANGED))
        assertNull(FinishingResume.of(null))
    }
}
