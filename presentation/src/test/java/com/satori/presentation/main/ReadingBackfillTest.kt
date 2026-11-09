package com.satori.presentation.main

import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.model.AppSettings
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.reading.LinesWithReadings
import com.satori.domain.reading.ReaderStatus
import com.satori.domain.repository.TranscriptionRepository
import com.satori.presentation.firstrun.FakeFirstRunCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backfill of readings (design R9): once the reader is on the phone, AI notes are on and an AI
 * model is there or on its way, every Malayalam recording due to be made again, without a job, with
 * lines the reader reads, is queued again, once no capture is live; it looks again as the reader,
 * the settings or the first run's AI model change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingBackfillTest {

    private val queue = RedoQueue()
    private val readers = HeldReaderAvailability(ReaderStatus.PRESENT)
    private val settings = HeldSettings(AppSettings(llmEnabled = true))
    private val coordinator = FakeFirstRunCoordinator()
    private val recorder = MutableStateFlow<ActiveRecorder.Snapshot?>(null)
    private val log = RecordedLog()
    private var livePath: String? = "live.gguf"

    /** The Malayalam recordings due to be made again without a job, oldest first, as the database gives them, with their failures. */
    private val candidates = mutableListOf("m1", "m2", "m3")
    private val asked = mutableListOf<Int>()

    /** Those of them with lines the reader reads; m2's are all in Latin letters. */
    private val readerLines = mutableSetOf("m1", "m3")

    /** Those of them whose lines were all read already, by other work than the backfill's. */
    private val readAlready = mutableSetOf<String>()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun stop() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.backfill(): ReadingBackfill {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        return ReadingBackfill(
            queue = queue,
            transcriptionRepository = Candidates(),
            reader = readers,
            settingsRepository = settings,
            modelPaths = AiModelPaths { livePath },
            firstRun = { coordinator },
            recorder = recorder,
            scope = scope,
            log = log
        )
    }

    @Test
    fun `with the reader on the phone it queues each Malayalam recording due to be made again that has lines the reader reads, once`() = runTest {
        val backfill = backfill()

        backfill.start()
        backfill.start()
        runCurrent()

        assertEquals(listOf("m1", "m3"), queue.redone)
        assertEquals(listOf(ReadingBackfill.MAX_FAILURES), asked.distinct())
        assertTrue("Backfill of readings queued 2 of 2 Malayalam recordings to be made again over their readings" in log.lines)
    }

    @Test
    fun `a recording whose lines other work read already is queued all the same, as an older version's notes stay until it is made again`() = runTest {
        // The page's Find again, the map or a debug rerun read m1's lines first.
        readAlready += "m1"
        val backfill = backfill()

        backfill.start()
        runCurrent()

        assertEquals(listOf("m1", "m3"), queue.redone)
    }

    @Test
    fun `nothing is queued while the reader is only on its way`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        val backfill = backfill()

        backfill.start()
        runCurrent()
        readers.changed.tryEmit(Unit)
        runCurrent()

        assertTrue(queue.redone.isEmpty())
    }

    @Test
    fun `nothing is queued without the reader, without AI notes or without an AI model, and each coming looks again`() = runTest {
        readers.status = ReaderStatus.ON_ITS_WAY
        settings.settings.value = AppSettings(llmEnabled = false)
        livePath = null
        val backfill = backfill()
        backfill.start()
        runCurrent()

        // The reader comes: AI notes are still off.
        readers.status = ReaderStatus.PRESENT
        readers.changed.tryEmit(Unit)
        runCurrent()
        assertTrue(queue.redone.isEmpty())

        // AI notes on, but no AI model on the phone, and none on its way.
        settings.settings.value = AppSettings(llmEnabled = true)
        runCurrent()
        assertTrue(queue.redone.isEmpty())

        // The first run plans the AI model: the readings come now, and the notes wait for it.
        coordinator.snapshot.value = FirstRunSnapshot(plan = FirstRunPlan(listOf(FirstRunPlan.Item(FirstRunPart.AI_MODEL, 1L, FirstRunPlan.Status.PENDING))))
        runCurrent()
        assertEquals(listOf("m1", "m3"), queue.redone)
    }

    @Test
    fun `never beside a capture, it waits for the capture to end, and stops queuing when one starts`() = runTest {
        recorder.value = ActiveRecorder.Snapshot("live", ActiveRecorder.Activity.RECORDING)
        val backfill = backfill()
        backfill.start()
        runCurrent()
        assertTrue(queue.redone.isEmpty())

        // An open recorder that doesn't record is no capture.
        recorder.value = ActiveRecorder.Snapshot("live", ActiveRecorder.Activity.IDLE)
        queue.onRedo = { recorder.value = ActiveRecorder.Snapshot("next", ActiveRecorder.Activity.STARTING) }
        runCurrent()

        // The first is queued; a recorder starts, and the rest wait for it to be over.
        assertEquals(listOf("m1"), queue.redone)
        assertTrue("Backfill of readings queued 1 of 2 Malayalam recordings to be made again over their readings" in log.lines)

        queue.onRedo = {}
        recorder.value = null
        runCurrent()
        assertEquals(listOf("m1", "m3"), queue.redone)
        assertTrue("Backfill of readings queued 1 of 1 Malayalam recordings to be made again over their readings" in log.lines)
    }

    @Test
    fun `a recording the queue refuses, one that has a job by now, isn't counted, and a read that fails queues nothing`() = runTest {
        queue.refused += "m1"
        val backfill = backfill()
        backfill.start()
        runCurrent()
        assertEquals(listOf("m3"), queue.redone)
        assertTrue("Backfill of readings queued 1 of 2 Malayalam recordings to be made again over their readings" in log.lines)

        readFails = true
        readers.changed.tryEmit(Unit)
        runCurrent()
        assertEquals(listOf("m3"), queue.redone)
        assertTrue(log.lines.any { it.startsWith("W Backfill of readings could not read the recordings") })
    }

    private var readFails = false

    /** The database's side: the candidates, and the failures limit asked with. */
    private inner class Candidates : TranscriptionRepository by StoredLengths() {
        override suspend fun backfillCandidates(maxFailures: Int): List<String> {
            asked += maxFailures
            if (readFails) throw IllegalStateException("database closed")
            // As the database: a recording queued has a job, which takes it out.
            return candidates.filter { it !in queue.redone }
        }

        // Made up: "the roof leaks", and the reader's English of it, stored already for those read before.
        override suspend fun getLinesWithReadings(sessionId: String): LinesWithReadings {
            val text = if (sessionId in readerLines) "മേൽക്കൂര ചോരുന്നു" else "The roof leaks"
            val line = TranscriptionSegment("$sessionId-1", sessionId, text, timestamp = 1L, isComplete = true)
            val readings = if (sessionId in readAlready) {
                listOf(SegmentReading(line.id, sessionId, SegmentReading.sourceHashOf(text), "The roof leaks", "reader-test", -0.4, createdAt = 2L))
            } else {
                emptyList()
            }
            return LinesWithReadings(listOf(line), readings)
        }
    }

    /** The finishing queue's redo: what was queued, in order; [refused] ones have a job already. */
    private class RedoQueue : FinishingQueue {
        val redone = mutableListOf<String>()
        val refused = mutableSetOf<String>()
        var onRedo: () -> Unit = {}

        override suspend fun redo(sessionId: String): Boolean {
            if (sessionId in refused) return false
            redone += sessionId
            onRedo()
            return true
        }

        override val statuses: StateFlow<List<FinishingStatus>> = MutableStateFlow(emptyList())
        override val busy: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun captureStarted(sessionId: String) = Unit
        override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) = Unit
        override suspend fun cancel(sessionId: String) = Unit
        override fun skipBetterTranscript(sessionId: String) = Unit
        override fun resume(mayStartForeground: Boolean) = Unit
        override fun runUnderWakeJob(done: () -> Unit) = Unit
        override fun wakeJobStopped() = Unit
    }
}
