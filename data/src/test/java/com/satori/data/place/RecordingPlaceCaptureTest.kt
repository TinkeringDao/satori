package com.satori.data.place

import com.satori.domain.place.LocationFix
import com.satori.domain.place.LocationProvider
import com.satori.domain.place.LocationSource
import com.satori.domain.usecase.place.SaveRecordingPlaceUseCase.Outcome
import com.satori.domain.usecase.place.TakeLocationFixUseCase.Step
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The capture reads a recording's place only while the setting is on and location is allowed,
 * once per recording, and says it is reading until every read has ended: the recording service
 * holds the location type for as long (RecordingService). A read that fails is logged and ends,
 * and the read of a recording deleted because nothing was said in it is stopped.
 */
class RecordingPlaceCaptureTest {

    private val source = AllowedSource()

    // How each recording's read ends; a read waits until the test says.
    private val answers = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()
    private val reads = CopyOnWriteArrayList<String>()
    private val infos = CopyOnWriteArrayList<String>()
    private val warnings = CopyOnWriteArrayList<String>()

    private val capture = RecordingPlaceCapture(
        locationSource = source,
        saveRecordingPlace = { sessionId, _ ->
            reads += sessionId
            if (sessionId == FAILING) throw IOException("database closed")
            answerFor(sessionId).await()
        },
        info = { message, _ -> infos += message },
        warn = { message, _ -> warnings += message }
    )

    private fun answerFor(sessionId: String) = answers.computeIfAbsent(sessionId) { CompletableDeferred() }

    private val saved = Outcome.Saved(Step.CURRENT_GPS, accuracyMeters = 6F, named = true)

    @Test
    fun `nothing is read while the setting is off`() {
        capture.onRecordingStarted("rec", savePlace = false)

        assertFalse(capture.reading.value)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `nothing is read while location is not allowed`() {
        source.allowed = false

        capture.onRecordingStarted("rec", savePlace = true)

        assertFalse(capture.reading.value)
        assertTrue(reads.isEmpty())
    }

    @Test
    fun `it says it is reading before the start returns, and until the place is stored`() = runBlocking {
        capture.onRecordingStarted("rec", savePlace = true)

        // The recording service is started right after and must see it.
        assertTrue(capture.reading.value)
        answerFor("rec").complete(saved)
        awaitReadingEnded()
        assertTrue(infos.single(), infos.single().startsWith("Place of recording rec saved after"))
    }

    @Test
    fun `a recording whose place is being read is not read twice`() = runBlocking {
        capture.onRecordingStarted("rec", savePlace = true)
        // A start again after an error, while the first read waits for its position.
        capture.onRecordingStarted("rec", savePlace = true)

        answerFor("rec").complete(saved)
        awaitReadingEnded()
        assertEquals(listOf("rec"), reads)
    }

    @Test
    fun `it reads until the place of every recording is read`() = runBlocking {
        capture.onRecordingStarted("first", savePlace = true)
        capture.onRecordingStarted("second", savePlace = true)

        answerFor("first").complete(Outcome.NoFix)
        awaitUntil { infos.any { "first" in it } }
        // The first read ends right after its line; the reading must not end with it.
        repeat(20) {
            assertTrue(capture.reading.value)
            Thread.sleep(5)
        }

        answerFor("second").complete(saved)
        awaitReadingEnded()
    }

    @Test
    fun `a recording gone meanwhile is logged as gone, not as saved`() = runBlocking {
        capture.onRecordingStarted("rec", savePlace = true)

        answerFor("rec").complete(Outcome.Gone)
        awaitReadingEnded()
        assertEquals(listOf("Recording rec is gone, no place stored"), infos)
    }

    @Test
    fun `a recording discarded while its place is read is read no further`() = runBlocking {
        capture.onRecordingStarted("rec", savePlace = true)
        // Waiting for its position.
        awaitUntil { reads.isNotEmpty() }

        capture.onRecordingDiscarded("rec")

        awaitReadingEnded()
        assertEquals(listOf("Recording rec was discarded, its place is not read"), infos)
    }

    @Test
    fun `a recording discarded as its read begins ends the reading too`() = runBlocking {
        repeat(50) { i ->
            capture.onRecordingStarted("rec$i", savePlace = true)
            capture.onRecordingDiscarded("rec$i")
        }

        awaitReadingEnded()
    }

    @Test
    fun `discarding a recording whose place is not being read changes nothing`() {
        capture.onRecordingDiscarded("rec")

        assertFalse(capture.reading.value)
        assertTrue(infos.isEmpty())
    }

    @Test
    fun `a read that fails is logged and ends the reading`() = runBlocking {
        capture.onRecordingStarted(FAILING, savePlace = true)

        awaitReadingEnded()
        assertEquals(1, warnings.size)
        // The next recording is read as usual.
        capture.onRecordingStarted("rec", savePlace = true)
        assertTrue(capture.reading.value)
        answerFor("rec").complete(saved)
        awaitReadingEnded()
    }

    private suspend fun awaitReadingEnded() {
        withTimeout(TIMEOUT_MS) { capture.reading.first { !it } }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out" }
            Thread.sleep(5)
        }
    }

    /** Location as the capture checks it; the positions themselves come from the save step. */
    private class AllowedSource : LocationSource {
        var allowed = true
        override fun isAllowed() = allowed
        override fun elapsedRealtimeNanos() = System.nanoTime()
        override fun lastFix(provider: LocationProvider): LocationFix? = null
        override suspend fun currentFix(provider: LocationProvider): LocationFix? = null
    }

    private companion object {
        const val FAILING = "failing"
        const val TIMEOUT_MS = 5_000L
    }
}
