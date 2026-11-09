package com.satori.presentation.main

import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureEventTest {

    private val partial = TranscriptionSegment("p", "s", "Book the room", 1L, isComplete = false)
    private val complete = TranscriptionSegment("c", "s", "Book the room for Friday.", 2L, isComplete = true)

    @Test
    fun `the end comes after the last line`() = runTest {
        val events = CaptureEvent.of(flowOf(partial, complete)) { error("no failure") }.toList()

        assertEquals(listOf(CaptureEvent.Line(partial), CaptureEvent.Line(complete), CaptureEvent.End), events)
    }

    @Test
    fun `a failure is handled and the end still comes`() = runTest {
        val failure = IOException("microphone gone")
        val handled = mutableListOf<Throwable>()
        val failing = flow {
            emit(complete)
            throw failure
        }

        val events = CaptureEvent.of(failing) { handled += it }.toList()

        assertEquals(listOf(CaptureEvent.Line(complete), CaptureEvent.End), events)
        assertEquals(listOf<Throwable>(failure), handled)
    }

    @Test
    fun `the lines leave the end out`() = runTest {
        val lines = CaptureEvent.of(flowOf(partial, complete)) { error("no failure") }.lines().toList()

        assertEquals(listOf(partial, complete), lines)
    }

    @Test
    fun `a shared capture, which never completes, is read to its end`() = runTest {
        val shared = CaptureEvent.of(flowOf(partial, complete)) { error("no failure") }
            .shareIn(backgroundScope, SharingStarted.WhileSubscribed(), replay = 0)

        // Completes by itself: Stop waits as long as the last words take, not a fixed time.
        assertEquals(listOf(partial, complete), shared.linesUntilEnd().toList())
    }

    @Test
    fun `Stop waits for the capture's end as long as it takes within the guard`() = runTest {
        val lastWords = CompletableDeferred<Unit>()
        val collector = launch { lastWords.await() }
        val waited = async { awaitCaptureEnd(collector, guardMs = 15_000) }

        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(waited.isCompleted)
        lastWords.complete(Unit)

        assertTrue(waited.await())
        assertEquals(3_000, currentTime)
        assertFalse(collector.isCancelled)
    }

    @Test
    fun `a capture whose end does not come within the guard is cut off`() = runTest {
        val collector = launch { awaitCancellation() }

        assertFalse(awaitCaptureEnd(collector, guardMs = 15_000))

        assertEquals(15_000, currentTime)
        assertTrue(collector.isCancelled)
    }

    @Test
    fun `a capture without a collector has ended`() = runTest {
        assertTrue(awaitCaptureEnd(null, guardMs = 15_000))
        assertEquals(0, currentTime)
    }
}
