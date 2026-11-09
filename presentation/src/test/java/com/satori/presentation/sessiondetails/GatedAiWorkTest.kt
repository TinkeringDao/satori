package com.satori.presentation.sessiondetails

import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatedAiWorkTest {

    private val gate = AiWorkGate()
    private val events = mutableListOf<String>()

    /** Frees the model, saying who held the gate meanwhile. */
    private val release: suspend () -> Unit = { events += "release, held by ${gate.holder.value}" }

    @Test
    fun `the work runs holding the gate, and the gate opens once the model is released`() = runTest {
        val ran = gate.runGated(AiWorkGate.REWRITE, refused = { false }, release = release) {
            events += "work, held by ${gate.holder.value}"
        }

        assertTrue(ran)
        assertEquals(listOf("work, held by rewrite", "release, held by rewrite"), events)
        assertNull(gate.holder.value)
    }

    @Test
    fun `refused, as while a recording waits to be finished, it runs and holds nothing`() = runTest {
        val ran = gate.runGated(AiWorkGate.TODOS, refused = { true }, release = release) { events += "work" }

        assertFalse(ran)
        assertTrue(events.isEmpty())
        assertNull(gate.holder.value)
    }

    @Test
    fun `with the gate held by another it runs nothing, frees no model and leaves the gate to it`() = runTest {
        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))

        val ran = gate.runGated(AiWorkGate.TRANSCRIBE, refused = { false }, release = release) { events += "work" }

        assertFalse(ran)
        assertTrue(events.isEmpty())
        assertEquals(AiWorkGate.FINISHER, gate.holder.value)
    }

    @Test
    fun `a cancel still releases the model to the end under the gate, then opens it`() = runTest {
        val started = CompletableDeferred<Unit>()
        val work = launch {
            gate.runGated(
                AiWorkGate.REWRITE,
                refused = { false },
                // A release that takes a while, as freeing the model does, and must not be cut short.
                release = {
                    delay(500)
                    events += "released, held by ${gate.holder.value}"
                }
            ) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()

        work.cancel()
        advanceUntilIdle()

        assertTrue(work.isCancelled)
        assertEquals(listOf("released, held by rewrite"), events)
        assertNull(gate.holder.value)
    }

    @Test
    fun `work that fails still releases the model and opens the gate, and the failure goes on`() = runTest {
        try {
            gate.runGated(AiWorkGate.TODOS, refused = { false }, release = release) { error("the settings couldn't be read") }
            fail("The failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("the settings couldn't be read", e.message)
        }

        assertEquals(listOf("release, held by todos"), events)
        assertNull(gate.holder.value)
    }

    @Test
    fun `a release that fails still opens the gate`() = runTest {
        try {
            gate.runGated(AiWorkGate.REWRITE, refused = { false }, release = { error("cleanup failed") }) { events += "work" }
            fail("The failure was swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("cleanup failed", e.message)
        }

        assertEquals(listOf("work"), events)
        assertNull(gate.holder.value)
        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))
    }
}
