package com.satori.domain.usecase.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRetentionSchedulerTest {

    /** Records what was asked of the job scheduler, in order. */
    private class Asked : AudioRetentionScheduler {
        val calls = mutableListOf<String>()
        override fun scheduleDaily() {
            calls += "daily"
        }

        override fun cancel() {
            calls += "cancel"
        }

        override fun runNow() {
            calls += "now"
        }
    }

    @Test
    fun `a rule that is on keeps the daily run and runs once at once`() {
        val compress = Asked().apply { rulesChanged(compressAfterDays = 7, deleteAfterDays = null) }
        val delete = Asked().apply { rulesChanged(compressAfterDays = null, deleteAfterDays = 30) }
        val both = Asked().apply { rulesChanged(compressAfterDays = 14, deleteAfterDays = 60) }

        assertEquals(listOf("daily", "now"), compress.calls)
        assertEquals(listOf("daily", "now"), delete.calls)
        assertEquals(listOf("daily", "now"), both.calls)
    }

    @Test
    fun `both rules on never cancel every run`() {
        val off = Asked().apply { rulesChanged(compressAfterDays = null, deleteAfterDays = null) }

        assertEquals(listOf("cancel"), off.calls)
    }
}
