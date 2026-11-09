package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.reading.ReadableSegments
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.ReaderOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Reading step (0.8.0, R5): the Malayalam lines read into English one by one, each reading
 * stored as it is made so a reading cut short goes on from the next line, D5's empty readings, the
 * AI model let go before the reader loads, and a stop at once for a capture (D14). Synthetic
 * Malayalam and English only.
 */
class ReadingStepTest {

    // Made up: "We will meet tomorrow morning." / "Bring the papers to the office." /
    // "After that we go to the market together." / "Okay."
    private val meet = segment("നമുക്ക് നാളെ രാവിലെ കാണാം.", at = 1_000L)
    private val english = segment("Call the bank about the card.", at = 2_000L)
    private val papers = segment("പേപ്പറുകൾ ഓഫീസിലേക്ക് കൊണ്ടുവരണം.", at = 3_000L)
    private val market = segment("അതിനുശേഷം നമ്മൾ ഒരുമിച്ച് മാർക്കറ്റിൽ പോകും.", at = 4_000L)
    private val okay = segment("ശരി.", at = 5_000L)

    private val readings = mapOf(
        meet.text to "We will meet tomorrow morning.",
        papers.text to "Bring the papers to the office.",
        market.text to "After that we go to the market together."
    )

    /** The AI model, in memory while [inMemory] says so; [unloads] counts the lets-go, and [stays] keeps it in memory through one (a load under way). */
    private class Model : LlmRepository by FakeLlmRepository() {
        var inMemory = false
        var stays = false
        var unloads = 0
        override val isModelInMemory: Boolean get() = inMemory

        override suspend fun cleanup() {
            unloads++
            if (!stays) inMemory = false
        }
    }

    private inner class Rig(vararg lines: TranscriptionSegment) {
        val repository = FakeTranscriptionRepository(session(), lines.toList())
        val reader = FakeReader(english = readings)
        val model = Model()
        val runs = mutableListOf<ReadingRun>()
        val step = ReadingStep(repository, reader, model, now = { 42L }, clock = { 0L })

        suspend fun read(onStart: (ReadingRun) -> Unit = {}) = step.read("s1") { run ->
            runs += run
            onStart(run)
        }

        fun stored(): Map<String, String> = repository.readings.associate { it.segmentId to it.text }
    }

    @Test
    fun `each Malayalam line is read in order and its reading stored, the English one left as it is`() = runBlocking {
        val rig = Rig(meet, english, papers)

        val read = rig.read()

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2)), read)
        assertEquals(listOf("load", "read We will meet tomorrow morning.", "read Bring the papers to the office.", "release"), rig.reader.events)
        assertEquals(mapOf(meet.id to "We will meet tomorrow morning.", papers.id to "Bring the papers to the office."), rig.stored())
        val first = rig.repository.readings.first { it.segmentId == meet.id }
        assertEquals(SegmentReading(meet.id, "s1", SegmentReading.sourceHashOf(meet.text), "We will meet tomorrow morning.", "reader-test", -0.4, createdAt = 42L), first)
        // The AI steps now read English only.
        assertEquals(
            listOf("We will meet tomorrow morning.", "Call the bank about the card.", "Bring the papers to the office."),
            rig.repository.getReadableSegments("s1").map { it.text }
        )
        assertEquals(1, rig.runs.size)
    }

    @Test
    fun `the AI model is let go before the reader loads, and one that stays in memory keeps it from loading`() = runBlocking {
        val rig = Rig(meet)
        rig.model.inMemory = true
        rig.reader.onRead = { assertFalse("the AI model is still in memory as the reader reads", rig.model.inMemory) }

        assertTrue(rig.read() is ReadingStep.Outcome.Read)
        assertEquals(1, rig.model.unloads)

        val held = Rig(meet)
        held.model.inMemory = true
        held.model.stays = true

        assertEquals(ReadingStep.Outcome.NotLoaded(ReaderNotLoadedException.Why.AI_MODEL, ReadingStep.Counts(lines = 1)), held.read())
        assertTrue(held.reader.events.isEmpty())
        assertTrue(held.runs.isEmpty())
    }

    @Test
    fun `a reading stopped for a capture keeps what it read, and the next goes on from the next line`() = runBlocking {
        val rig = Rig(meet, papers, market)
        // The capture opens as the second line is read: the reader stops itself, as its watch does.
        rig.reader.yieldsAt = 2

        val stopped = rig.read()

        assertEquals(ReadingStep.Outcome.Stopped(ReadingStep.Counts(lines = 3, kept = 1)), stopped)
        assertEquals(mapOf(meet.id to "We will meet tomorrow morning."), rig.stored())
        assertEquals("release", rig.reader.events.last())

        // After the capture: only the two lines left are read.
        rig.reader.yieldsAt = null
        rig.reader.events.clear()

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2)), rig.read())
        assertEquals(listOf("load", "read Bring the papers to the office.", "read After that we go to the market together.", "release"), rig.reader.events)
        assertEquals(3, rig.stored().size)
    }

    @Test
    fun `the run's stop ends a Run under way at once, and a stop as it is handed out keeps the reader from loading`() = runBlocking {
        val rig = Rig(meet, papers)
        rig.reader.holdAt = 2
        val reading = async(Dispatchers.Default) { rig.read() }
        withTimeout(5_000) { rig.reader.held.await() }

        rig.runs.single().stop()
        val stopped = withTimeout(5_000) { reading.await() }

        assertEquals(ReadingStep.Outcome.Stopped(ReadingStep.Counts(lines = 2, kept = 1)), stopped)
        assertEquals(listOf("load", "read We will meet tomorrow morning.", "release"), rig.reader.events)
        assertEquals(setOf(meet.id), rig.stored().keys)

        // A capture that went live as the run was handed out: the finisher stops it there.
        val early = Rig(meet)
        assertEquals(ReadingStep.Outcome.Stopped(ReadingStep.Counts(lines = 1)), early.read { it.stop() })
        assertTrue(early.reader.events.isEmpty())
        // A stop after the reading changes nothing.
        val done = Rig(meet)
        assertTrue(done.read() is ReadingStep.Outcome.Read)
        done.runs.single().stop()
        assertEquals(1, done.stored().size)
    }

    @Test
    fun `a cancel of the caller is passed on, with the reader released and what was read kept`() = runBlocking {
        val rig = Rig(meet, papers)
        rig.reader.holdAt = 2
        val reading = async(Dispatchers.Default) { rig.read() }
        withTimeout(5_000) { rig.reader.held.await() }

        reading.cancel()
        val thrown = runCatching { withTimeout(5_000) { reading.await() } }.exceptionOrNull()

        assertTrue("$thrown", thrown is CancellationException)
        assertEquals("release", rig.reader.events.last())
        assertEquals(setOf(meet.id), rig.stored().keys)
    }

    @Test
    fun `fragments get empty readings without the model, and only fragments load nothing`() = runBlocking {
        val rig = Rig(okay, meet)

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 1, fragments = 1)), rig.read())
        assertEquals(mapOf(okay.id to "", meet.id to "We will meet tomorrow morning."), rig.stored())
        assertEquals(listOf("load", "read We will meet tomorrow morning.", "release"), rig.reader.events)

        val fragments = Rig(okay, english)
        fragments.reader.onPhone = false

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 1, fragments = 1)), fragments.read())
        assertEquals(mapOf(okay.id to ""), fragments.stored())
        assertTrue(fragments.reader.events.isEmpty())
        assertTrue(fragments.runs.isEmpty())
        assertNull(fragments.repository.readings.single().score)
    }

    @Test
    fun `readings under the floor, outside the band, repeating or blank are stored empty, each counted`() = runBlocking {
        val noise1 = segment("മ്മ് മ്മ് ഹാ ഹാ ഹാ", at = 10L)
        val noise2 = segment("അതെ അതെ അതെ അതെ ശരി", at = 20L)
        val noise3 = segment("ഇത് പിന്നെ അത് ഇത് പിന്നെ", at = 30L)
        val noise4 = segment("ഉം ആ ഉം ആ ഉം ആ", at = 40L)
        val rig = Rig(noise1, noise2, noise3, noise4, meet)
        rig.reader.english = readings + mapOf(
            noise1.text to "I am going to the station now.",
            noise2.text to "Yes, yes, yes, yes, yes, okay.",
            noise3.text to "This and then that, this and then that, over again.",
            noise4.text to ""
        )
        rig.reader.output = { line, text ->
            when (line) {
                noise1.text -> ReaderOutput(text, pieces = 10, tokens = 7, meanLogProb = -2.3, capped = false)
                noise3.text -> ReaderOutput(text, pieces = 14, tokens = 40, meanLogProb = -0.9, capped = true)
                else -> ReaderOutput(text, pieces = 12, tokens = 9, meanLogProb = -0.5, capped = false)
            }
        }

        val read = rig.read()

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 5, kept = 1, floor = 1, band = 1, repeats = 1, blank = 1)), read)
        assertEquals(listOf("", "", "", "", "We will meet tomorrow morning."), listOf(noise1, noise2, noise3, noise4, meet).map { rig.stored()[it.id] })
        // An empty reading keeps its score, and its line is out of the notes.
        assertEquals(-2.3, rig.repository.readings.first { it.segmentId == noise1.id }.score!!, 0.0)
        assertEquals(listOf(meet.id), rig.repository.getReadableSegments("s1").map { it.id })
        assertEquals(ReadingStep.Needs.NOTHING, rig.step.needs("s1"))
    }

    @Test
    fun `a line the reader can't read gets an empty reading and the next is read, and a reader that fails keeps what it read`() = runBlocking {
        val rig = Rig(meet, papers, market)
        rig.reader.failOn = setOf(papers.text)

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 3, kept = 2, notRead = 1)), rig.read())
        assertEquals("", rig.stored()[papers.id])
        assertNull(rig.repository.readings.first { it.segmentId == papers.id }.score)

        val failing = Rig(meet, papers, market)
        failing.reader.failsAfter = 1

        val failed = failing.read() as ReadingStep.Outcome.Failed

        assertEquals(ReadingStep.Counts(lines = 3, kept = 1), failed.counts)
        assertEquals(setOf(meet.id), failing.stored().keys)
        assertEquals("release", failing.reader.events.last())
    }

    @Test
    fun `a reader not on the phone, refused, or stopped for memory loads nothing and reads nothing`() = runBlocking {
        val missing = Rig(meet)
        missing.reader.onPhone = false
        assertEquals(ReadingStep.Outcome.NotLoaded(ReaderNotLoadedException.Why.NOT_ON_PHONE, ReadingStep.Counts(lines = 1)), missing.read())
        assertFalse(missing.step.isOnPhone())

        val speech = Rig(meet)
        speech.reader.refusal = ReaderNotLoadedException.Why.SPEECH_MODEL
        assertEquals(ReadingStep.Outcome.NotLoaded(ReaderNotLoadedException.Why.SPEECH_MODEL, ReadingStep.Counts(lines = 1)), speech.read())

        val memory = Rig(meet)
        memory.reader.loadStops = true
        assertEquals(ReadingStep.Outcome.LoadStopped(ReadingStep.Counts(lines = 1)), memory.read())

        val broken = Rig(meet)
        broken.reader.loadFails = true
        assertTrue(broken.read() is ReadingStep.Outcome.Failed)

        for (rig in listOf(missing, speech, memory, broken)) {
            assertTrue(rig.reader.events.isEmpty())
            assertTrue(rig.repository.readings.isEmpty())
        }
    }

    @Test
    fun `a line edited while the reader read is read once more at the end, with the reader still loaded`() = runBlocking {
        val rig = Rig(meet, papers)
        val edited = "നമുക്ക് മറ്റന്നാൾ രാവിലെ കാണാം."
        rig.reader.english = readings + (edited to "We will meet the day after tomorrow, in the morning.")
        // The owner edits the first line while the second is read.
        rig.reader.onRead = { line ->
            if (line == papers.text) rig.repository.segments = rig.repository.segments.map { if (it.id == meet.id) it.copy(text = edited) else it }
        }

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 3, kept = 3)), rig.read())

        assertEquals(
            listOf(
                "load",
                "read We will meet tomorrow morning.",
                "read Bring the papers to the office.",
                "read We will meet the day after tomorrow, in the morning.",
                "release"
            ),
            rig.reader.events
        )
        assertEquals("We will meet the day after tomorrow, in the morning.", rig.repository.getReadableSegments("s1").first().text)
    }

    @Test
    fun `a reading that can't be stored is counted, and the step goes on`() = runBlocking {
        val rig = Rig(meet, papers)
        rig.repository.readingFailure = IllegalStateException("database or disk is full")

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2, notStored = 2)), rig.read())
        assertTrue(rig.repository.readings.isEmpty())
    }

    @Test
    fun `what the lines still need, and whether some were never read`() = runBlocking {
        val rig = Rig(meet, english, okay)
        assertEquals(ReadingStep.Needs.READER, rig.step.needs("s1"))
        assertTrue(rig.step.hasNeverRead("s1"))

        rig.repository.saveReading(SegmentReading(meet.id, "s1", SegmentReading.sourceHashOf(meet.text), "We will meet.", "reader-test", -0.3, createdAt = 1L))
        assertEquals(ReadingStep.Needs.NO_MODEL, rig.step.needs("s1"))

        rig.read()
        assertEquals(ReadingStep.Needs.NOTHING, rig.step.needs("s1"))
        assertFalse(rig.step.hasNeverRead("s1"))

        // A line edited after it was read needs the reader again, but was read once.
        rig.repository.segments = rig.repository.segments.map { if (it.id == meet.id) it.copy(text = papers.text) else it }
        assertEquals(ReadingStep.Needs.READER, rig.step.needs("s1"))
        assertFalse(rig.step.hasNeverRead("s1"))
        assertEquals(listOf(meet.id), ReadableSegments.unread(rig.repository.segments, rig.repository.readings).map { it.id })
    }

    @Test
    fun `a reading again reads every Malayalam line, stores nothing, and gives each with its verdict`() = runBlocking {
        val rig = Rig(meet, english, okay)
        rig.repository.saveReading(SegmentReading(meet.id, "s1", SegmentReading.sourceHashOf(meet.text), "We will meet.", "reader-test", -0.3, createdAt = 1L))
        val lines = mutableListOf<ReadingStep.LineRead>()

        val read = rig.step.readAgain("s1", onLine = { lines += it })

        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 1, fragments = 1)), read)
        assertEquals(listOf(meet.id to ReadingStep.Verdict.KEPT, okay.id to ReadingStep.Verdict.FRAGMENT), lines.map { it.segment.id to it.verdict })
        assertEquals("We will meet tomorrow morning.", lines.first().output?.text)
        assertEquals(mapOf(meet.id to "We will meet."), rig.stored())
    }

    @Test
    fun `a reading again in windows of 2 reads each two Malayalam lines as one text, a last one alone, and counts windows`() = runBlocking {
        val rig = Rig(meet, english, papers, market)
        // Made up: the first two lines read together.
        rig.reader.english = readings + ("${meet.text} ${papers.text}" to "We will meet tomorrow morning; bring the papers to the office.")
        val lines = mutableListOf<ReadingStep.LineRead>()

        val read = rig.step.readAgain("s1", onLine = { lines += it }, window = 2)

        // The English line between them is not the reader's: the two Malayalam ones around it make the first window.
        assertEquals(ReadingStep.Outcome.Read(ReadingStep.Counts(lines = 2, kept = 2)), read)
        assertEquals(listOf(listOf(meet.id, papers.id), listOf(market.id)), lines.map { window -> window.window.map { it.id } })
        assertEquals(listOf(meet.id, market.id), lines.map { it.segment.id })
        assertEquals(
            listOf("load", "read We will meet tomorrow morning; bring the papers to the office.", "read After that we go to the market together.", "release"),
            rig.reader.events
        )
        assertTrue(rig.stored().isEmpty())
        // A window of 1 is each line alone, as the Reading step reads them.
        val alone = mutableListOf<ReadingStep.LineRead>()
        rig.step.readAgain("s1", onLine = { alone += it }, window = 1)
        assertEquals(listOf(listOf(meet.id), listOf(papers.id), listOf(market.id)), alone.map { window -> window.window.map { it.id } })
    }
}
