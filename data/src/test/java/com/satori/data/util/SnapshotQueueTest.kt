package com.satori.data.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * PerfLogger's snapshots read on a thread of their own: the reads (binder calls into system_server
 * and the battery HAL) took up to 0.9 s on the razr's main thread, ahead of a recording's start
 * buzz (2026-10-13), and a snapshot must never hold up or fail its caller.
 */
class SnapshotQueueTest {

    private val thread = Executors.newSingleThreadExecutor()
    private val lines = LinkedBlockingQueue<String>()

    // Advanced by each read that wants its time counted.
    private val now = AtomicLong(1_000L)
    private val queue = SnapshotQueue(thread, clock = { now.get() }) { lines.put(it) }

    @After
    fun stopThread() {
        thread.shutdownNow()
    }

    private fun nextLine(): String? = lines.poll(5, TimeUnit.SECONDS)

    @Test
    fun `a snapshot whose reads take long returns at once, and logs once they are done`() {
        val released = CountDownLatch(1)
        var readOn: Thread? = null

        queue.take("session-start") {
            readOn = Thread.currentThread()
            // Released only after take() came back: read on the caller's thread, it would wait in vain.
            check(released.await(5, TimeUnit.SECONDS)) { "the caller was held up" }
            now.addAndGet(557L)
            "battery=100% current=-208000µA"
        }
        assertNull(lines.poll())
        released.countDown()

        assertEquals("[session-start] battery=100% current=-208000µA | read in 557 ms", nextLine())
        assertNotSame(Thread.currentThread(), readOn)
    }

    @Test
    fun `snapshots are logged in the order they were asked for`() {
        queue.take("session-end") { "a" }
        queue.take("session-start") { "b" }
        queue.take("heartbeat t+5min") { "c" }

        assertEquals(
            listOf("[session-end] a | read in 0 ms", "[session-start] b | read in 0 ms", "[heartbeat t+5min] c | read in 0 ms"),
            List(3) { nextLine() }
        )
    }

    @Test
    fun `a read that fails is logged as such, and the next snapshot still comes`() {
        queue.take("model-switched") { throw NullPointerException("no battery service") }
        queue.take("llm-start") { "battery=99%" }

        assertEquals("[model-switched] not read: NullPointerException", nextLine())
        assertEquals("[llm-start] battery=99% | read in 0 ms", nextLine())
        assertTrue(lines.isEmpty())
    }
}
