package com.satori.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishingWakeSchedulerTest {

    /** JobScheduler as the scheduler sees it: one job id, which waits or runs, or is gone. */
    private class FakeJobs : FinishingWakeScheduler.Jobs {
        var exists = false
        val calls = mutableListOf<String>()

        override fun exists(): Boolean = exists

        override fun schedule() {
            calls += "schedule"
            exists = true
        }

        override fun cancel() {
            calls += "cancel"
            exists = false
        }
    }

    private val jobs = FakeJobs()
    private val wake = FinishingWakeScheduler(jobs)

    @Test
    fun `without a job the wake job is scheduled`() {
        wake.schedule()

        assertEquals(listOf("schedule"), jobs.calls)
    }

    @Test
    fun `a job that runs is left running`() {
        // The job started the process, and the queue's resume() under it asks for the job again:
        // scheduling it once more would stop it.
        jobs.exists = true

        wake.schedule()

        assertTrue(jobs.calls.isEmpty())
    }

    @Test
    fun `asking again while the job waits keeps the one scheduled`() {
        wake.schedule()
        wake.schedule()
        wake.schedule()

        assertEquals(listOf("schedule"), jobs.calls)
    }

    @Test
    fun `after a cancel the job is scheduled again`() {
        wake.schedule()
        wake.cancel()
        wake.schedule()

        assertEquals(listOf("schedule", "cancel", "schedule"), jobs.calls)
    }
}
