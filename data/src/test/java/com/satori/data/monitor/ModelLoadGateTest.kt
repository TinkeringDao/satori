package com.satori.data.monitor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One model load at a time, and the recorder's own load never behind another: it goes ahead, and
 * the others wait for it before they start.
 */
class ModelLoadGateTest {

    private val gate = ModelLoadGate(pollMs = 5)

    // The gate blocks threads: one that is never given back fails its test here instead of hanging the build.
    @get:Rule
    val timeout: Timeout = Timeout.seconds(20)

    @Test
    fun `a second load waits until the first gives the gate back`() {
        gate.acquire("gemma-4-E2B_q4_0-it.gguf") { false }
        val secondIn = CountDownLatch(1)
        val second = thread {
            gate.acquire("cohere-transcribe-14-lang-int8") { false }
            secondIn.countDown()
        }

        assertFalse("in beside the first", secondIn.await(100, TimeUnit.MILLISECONDS))
        assertEquals("gemma-4-E2B_q4_0-it.gguf", gate.holder)

        gate.release()
        assertTrue(secondIn.await(2, TimeUnit.SECONDS))
        assertEquals("cohere-transcribe-14-lang-int8", gate.holder)
        second.join()
        gate.release()
        assertNull(gate.holder)
    }

    @Test
    fun `the recorder's load never waits, also while another holds the gate, and the others see it`() {
        gate.acquire("gemma-4-E2B_q4_0-it.gguf") { false }

        val ran = gate.recorder {
            assertTrue(gate.recorderLoading)
            "parakeet loaded"
        }

        assertEquals("parakeet loaded", ran)
        assertFalse(gate.recorderLoading)
        gate.release()
    }

    @Test
    fun `a load that is about to start waits while the recorder's load runs`() {
        val recorderIn = CountDownLatch(1)
        val recorderOut = CountDownLatch(1)
        val recorder = thread {
            gate.recorder {
                recorderIn.countDown()
                recorderOut.await()
            }
        }
        recorderIn.await()
        val otherIn = AtomicBoolean(false)
        val other = thread {
            gate.acquire("gemma-3-1b-it-Q8_0.gguf") { false }
            otherIn.set(true)
        }

        Thread.sleep(100)
        assertFalse("started beside the recorder's load", otherIn.get())

        recorderOut.countDown()
        other.join(2_000)
        assertTrue(otherIn.get())
        recorder.join()
        gate.release()
    }

    @Test
    fun `a load cancelled while it waits holds no gate`() {
        gate.acquire("gemma-4-E2B_q4_0-it.gguf") { false }
        try {
            gate.acquire("cohere-transcribe-14-lang-int8") { true }
            fail("not cancelled")
        } catch (e: CancellationException) {
            // expected
        }
        gate.release()

        // The gate is free again: the next load is in at once.
        gate.acquire("parakeet-tdt-0.6b-v2-int8") { throw AssertionError("waited") }
        assertEquals("parakeet-tdt-0.6b-v2-int8", gate.holder)
        gate.release()
    }

    @Test
    fun `an AI model's load waits for the gate without blocking its thread, and is cancelled as its coroutine is`() = runBlocking {
        gate.acquire("cohere-transcribe-14-lang-int8") { false }
        val waiting = async(Dispatchers.Default) { gate.acquireSuspending("gemma-4-E2B_q4_0-it.gguf") }
        delay(100)
        assertFalse(waiting.isCompleted)

        gate.release()
        withTimeout(2_000) { waiting.await() }
        assertEquals("gemma-4-E2B_q4_0-it.gguf", gate.holder)

        val cancelled = CompletableDeferred<Unit>()
        val behind = async(Dispatchers.Default) {
            try {
                gate.acquireSuspending("gemma-3-1b-it-Q8_0.gguf")
            } finally {
                cancelled.complete(Unit)
            }
        }
        delay(50)
        behind.cancel()
        withTimeout(2_000) { cancelled.await() }
        assertEquals("gemma-4-E2B_q4_0-it.gguf", gate.holder)
        gate.release()
        assertNull(gate.holder)
    }
}
