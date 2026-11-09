package com.satori.presentation.ai

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiWorkGateTest {

    private val gate = AiWorkGate()

    @Test
    fun `enter waits for the exit, and waiters take the gate in the order they came`() = runTest {
        gate.enter(AiWorkGate.FINISHER)
        val order = mutableListOf<String>()
        for (holder in listOf(AiWorkGate.MAP, AiWorkGate.REWRITE, AiWorkGate.TODOS)) {
            launch {
                gate.enter(holder)
                order += holder
            }
            runCurrent()
        }
        assertEquals(emptyList<String>(), order)

        gate.exit(AiWorkGate.FINISHER)
        runCurrent()
        assertEquals(listOf(AiWorkGate.MAP), order)
        assertEquals(AiWorkGate.MAP, gate.holder.value)

        gate.exit(AiWorkGate.MAP)
        runCurrent()
        gate.exit(AiWorkGate.REWRITE)
        runCurrent()

        assertEquals(listOf(AiWorkGate.MAP, AiWorkGate.REWRITE, AiWorkGate.TODOS), order)
        assertEquals(AiWorkGate.TODOS, gate.holder.value)
    }

    @Test
    fun `the gate goes from one holder to the next without reading free, and nobody can slip in`() = runTest {
        gate.enter(AiWorkGate.FINISHER)
        launch { gate.enter(AiWorkGate.MAP) }
        runCurrent()

        gate.exit(AiWorkGate.FINISHER)

        assertEquals(AiWorkGate.MAP, gate.holder.value)
        assertTrue(gate.busy.value)
        assertFalse(gate.tryEnter(AiWorkGate.LIVE_NOTE))
        advanceUntilIdle()
        assertEquals(AiWorkGate.MAP, gate.holder.value)
    }

    @Test
    fun `tryEnter takes a free gate and fails while it is held, without waiting`() {
        assertTrue(gate.tryEnter(AiWorkGate.LIVE_NOTE))
        assertFalse(gate.tryEnter(AiWorkGate.FINISHER))
        assertFalse(gate.tryEnter(AiWorkGate.LIVE_NOTE))

        gate.exit(AiWorkGate.LIVE_NOTE)

        assertTrue(gate.tryEnter(AiWorkGate.FINISHER))
    }

    @Test
    fun `an exit by anyone but the holder changes nothing`() {
        gate.tryEnter(AiWorkGate.FINISHER)

        gate.exit(AiWorkGate.LIVE_NOTE)
        gate.exit(AiWorkGate.RECORDER_RELEASE)

        assertEquals(AiWorkGate.FINISHER, gate.holder.value)
        assertTrue(gate.busy.value)

        gate.exit(AiWorkGate.FINISHER)
        gate.exit(AiWorkGate.FINISHER)

        assertNull(gate.holder.value)
        assertFalse(gate.busy.value)
    }

    @Test
    fun `busy and holder follow the gate`() = runTest {
        assertNull(gate.holder.value)
        assertFalse(gate.busy.value)

        gate.enter(AiWorkGate.TRANSCRIBE)
        assertEquals(AiWorkGate.TRANSCRIBE, gate.holder.value)
        assertTrue(gate.busy.value)

        gate.exit(AiWorkGate.TRANSCRIBE)
        assertNull(gate.holder.value)
        assertFalse(gate.busy.value)
    }

    @Test
    fun `a waiter cancelled in line never takes the gate, and the next one does`() = runTest {
        gate.enter(AiWorkGate.FINISHER)
        val cancelled = launch { gate.enter(AiWorkGate.MAP) }
        var rewriteEntered = false
        launch {
            gate.enter(AiWorkGate.REWRITE)
            rewriteEntered = true
        }
        runCurrent()

        cancelled.cancel()
        runCurrent()
        gate.exit(AiWorkGate.FINISHER)
        runCurrent()

        assertTrue(cancelled.isCancelled)
        assertTrue(rewriteEntered)
        assertEquals(AiWorkGate.REWRITE, gate.holder.value)
    }

    @Test
    fun `a waiter cancelled just as it was handed the gate passes it on`() = runTest {
        gate.enter(AiWorkGate.FINISHER)
        val cancelled = launch { gate.enter(AiWorkGate.MAP) }
        var todosEntered = false
        launch {
            gate.enter(AiWorkGate.TODOS)
            todosEntered = true
        }
        runCurrent()

        // The gate is handed to MAP, whose coroutine is cancelled before it resumes.
        gate.exit(AiWorkGate.FINISHER)
        cancelled.cancel()
        advanceUntilIdle()

        assertTrue(todosEntered)
        assertEquals(AiWorkGate.TODOS, gate.holder.value)

        gate.exit(AiWorkGate.TODOS)
        assertNull(gate.holder.value)
        assertFalse(gate.busy.value)
    }

    @Test
    fun `a waiter cancelled as it was handed the gate frees it when nobody else waits`() = runTest {
        gate.enter(AiWorkGate.FINISHER)
        val cancelled = launch { gate.enter(AiWorkGate.MAP) }
        runCurrent()

        gate.exit(AiWorkGate.FINISHER)
        cancelled.cancel()
        advanceUntilIdle()

        assertNull(gate.holder.value)
        assertFalse(gate.busy.value)
        assertTrue(gate.tryEnter(AiWorkGate.LIVE_NOTE))
    }
}
