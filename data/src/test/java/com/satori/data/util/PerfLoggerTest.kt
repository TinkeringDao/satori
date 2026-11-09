package com.satori.data.util

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * PerfLogger's snapshot, which a recording's start and stop take on the main thread: it hands its
 * reads to the snapshots' own thread and returns before they run. On 2026-10-13 those reads took
 * up to 0.9 s, ahead of the next recording's start buzz.
 */
class PerfLoggerTest {

    private val shipped = PerfLogger.snapshots
    private val handedOver = mutableListOf<Runnable>()
    private val logged = mutableListOf<String>()

    @After
    fun putTheQueueBack() {
        PerfLogger.snapshots = shipped
    }

    @Test
    fun `a snapshot hands its reads to the snapshots' thread and returns before they run`() {
        PerfLogger.snapshots = SnapshotQueue(Executor { handedOver += it }, clock = { 0L }) { logged += it }

        PerfLogger.logSnapshot(ContextWrapper(null), "session-start")

        // Back with nothing read: the reads wait for the queue's thread.
        assertEquals(1, handedOver.size)
        assertTrue(logged.isEmpty())

        handedOver.single().run()

        // A JVM test's context has no system services: the read fails there, not in the caller, and says so.
        assertEquals(listOf("[session-start] not read: NullPointerException"), logged)
    }
}
