package com.satori.presentation.main

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The order of what Stop does once the last words are saved, and where the recorder goes then. */
class HandOffPlanTest {

    private val steps = mutableListOf<String>()
    private var state = MainUiState(isInitializing = false, llmStatus = LlmStatus.Ready, saving = true)

    private fun plan(silent: Boolean, startInFlight: Boolean = false, handOffFails: Throwable? = null) = HandOffPlan(
        deleteIfSilent = {
            steps += if (silent) "delete" else "keep"
            silent
        },
        handOff = {
            steps += "handOff"
            handOffFails?.let { throw it }
        },
        stopService = { steps += "stopService" },
        leave = { deleted -> state = state.afterHandOff(deleted, startInFlight) }
    )

    @Test
    fun `a silent recording is deleted, the service stops, and the recorder goes home saying why`() = runBlocking {
        plan(silent = true).run()

        assertEquals(listOf("delete", "stopService"), steps)
        assertEquals(RecorderExit.NOTHING_SAID, state.exit)
    }

    @Test
    fun `a kept recording is handed off before the service stops, then the recorder goes home`() = runBlocking {
        plan(silent = false).run()

        assertEquals(listOf("keep", "handOff", "stopService"), steps)
        assertEquals(RecorderExit.HOME, state.exit)
    }

    @Test
    fun `Back while saving goes back once the recording is handed off`() = runBlocking {
        state = state.copy(leaveWhenSaved = true)

        plan(silent = false).run()

        assertEquals(listOf("keep", "handOff", "stopService"), steps)
        assertEquals(RecorderExit.BACK, state.exit)
    }

    @Test
    fun `while a quick-key start waits the recorder goes nowhere, kept or deleted`() = runBlocking {
        plan(silent = false, startInFlight = true).run()
        assertEquals(RecorderExit.REPLACED, state.exit)

        state = state.copy(saving = true, exit = null)
        steps.clear()
        plan(silent = true, startInFlight = true).run()

        assertEquals(listOf("delete", "stopService"), steps)
        assertEquals(RecorderExit.REPLACED, state.exit)
    }

    @Test
    fun `the recording service stops also when the hand-off fails or the recorder closes during it`() {
        for (failure in listOf(IllegalStateException("queue not written"), CancellationException("recorder closed"))) {
            steps.clear()
            state = state.copy(saving = true, exit = null)

            assertThrows(failure.javaClass) { runBlocking { plan(silent = false, handOffFails = failure).run() } }

            assertEquals(listOf("keep", "handOff", "stopService"), steps)
            // The recorder that closes deals with the recording itself.
            assertNull(state.exit)
        }
    }
}
