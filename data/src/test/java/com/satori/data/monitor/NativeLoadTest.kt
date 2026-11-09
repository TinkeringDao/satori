package com.satori.data.monitor

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recognizer, a VAD or the reader that was loaded is released however its use ends: a native
 * object left to the garbage collector keeps its memory. A load that can be stopped (the reader's)
 * is told to stop as soon as its caller is cancelled, and nothing is freed under it meanwhile.
 */
class NativeLoadTest {

    private val released = CopyOnWriteArrayList<String>()

    @Test
    fun `what was loaded is released once it was used`() = runBlocking {
        val result = withLoaded(Dispatchers.IO, load = { "model" }, release = { released += it }) { "used $it" }

        assertEquals("used model", result)
        assertEquals(listOf("model"), released)
    }

    @Test
    fun `a use that fails still releases it`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { withLoaded(Dispatchers.IO, load = { "model" }, release = { released += it }) { error("decode failed") } }
        }

        assertEquals(listOf("model"), released)
    }

    @Test
    fun `a load that fails has nothing to release`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { withLoaded<String, Unit>(Dispatchers.IO, load = { error("damaged") }, release = { released += it }) {} }
        }

        assertTrue(released.isEmpty())
    }

    @Test
    fun `a cancel during the load releases what the load made, and nothing uses it`() = runBlocking {
        val loading = CountDownLatch(1)
        var used = false
        val transcription = launch(Dispatchers.Default) {
            withLoaded(
                Dispatchers.IO,
                // A native load runs to its end whatever the coroutine is told meanwhile.
                load = {
                    loading.countDown()
                    Thread.sleep(300)
                    "model"
                },
                release = { released += it }
            ) { used = true }
        }
        // Every wait here is bounded: a regression fails the test instead of holding the suite.
        assertTrue("the load never ran", loading.await(5, TimeUnit.SECONDS))

        transcription.cancelAndJoin()

        assertEquals(listOf("model"), released)
        assertFalse(used)
    }

    @Test
    fun `a cancel during a load that can be stopped stops it at once and releases what it made`() = runBlocking {
        val loading = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var used = false
        val reading = launch(Dispatchers.Default) {
            withLoaded(
                Dispatchers.IO,
                // A load that ends only once it is told to stop, as the reader's ends at its flag.
                load = {
                    loading.countDown()
                    check(stopped.await(5, TimeUnit.SECONDS)) { "never told to stop" }
                    "reader"
                },
                release = { released += it },
                stop = { stopped.countDown() }
            ) { used = true }
        }
        assertTrue("the load never ran", loading.await(5, TimeUnit.SECONDS))
        val cancelled = System.nanoTime()

        reading.cancelAndJoin()

        assertTrue("the load ran on for its 5 s", (System.nanoTime() - cancelled) / 1_000_000 < 2_000)
        assertEquals(listOf("reader"), released)
        assertFalse(used)
    }

    @Test
    fun `a stoppable block's answer comes back, and nothing is stopped`() = runBlocking {
        var stops = 0

        assertEquals(42, stoppable(Dispatchers.IO, stop = { stops++ }) { 42 })

        assertEquals(0, stops)
    }

    @Test
    fun `the cancel of a stoppable block goes on only once the block has returned`() = runBlocking {
        val inBlock = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val caller = launch(Dispatchers.Default) {
            stoppable(Dispatchers.IO, stop = { stop.countDown() }) {
                inBlock.countDown()
                // Bounded: without the stop the block ends after 5 s, unreturned, and the test fails.
                if (stop.await(5, TimeUnit.SECONDS)) {
                    // The native call winds down after the flag: its memory is still in use until it returns.
                    Thread.sleep(100)
                    returned.set(true)
                }
            }
        }
        assertTrue("the block never ran", inBlock.await(5, TimeUnit.SECONDS))

        caller.cancelAndJoin()

        assertTrue("the block was never told to stop, or the cancel went on before it returned", returned.get())
    }

    @Test
    fun `a stoppable block that fails throws its own exception`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { stoppable(Dispatchers.IO, stop = {}) { error("load failed") } }
        }
    }
}
