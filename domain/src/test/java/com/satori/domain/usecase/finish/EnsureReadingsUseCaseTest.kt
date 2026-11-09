package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.ReaderNotLoadedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readings AI work outside the finishing makes first (0.8.0, R6): by the Reading step's rules,
 * only when a line lacks one, and with what came of it in the field log, counts only. Synthetic
 * Malayalam and English only.
 */
class EnsureReadingsUseCaseTest {

    // Made up: "Tomorrow at ten the bank must be called." / "Okay." / "The roof of the shed leaks again."
    private val bank = segment("നാളെ പത്ത് മണിക്ക് ബാങ്കിൽ വിളിക്കണം.", at = 1_000L)
    private val okay = segment("ശരി.", at = 2_000L)
    private val roof = segment("ഷെഡിന്റെ മേൽക്കൂര വീണ്ടും ചോരുന്നു.", at = 3_000L)
    private val english = segment("Call the plumber about the kitchen tap.", at = 4_000L)

    private val readings = mapOf(
        bank.text to "Tomorrow at ten the bank has to be called.",
        roof.text to "The roof of the shed is leaking again."
    )

    /** The AI model, in memory until it is let go; [unloads] counts the lets-go. */
    private class Model : LlmRepository by FakeLlmRepository() {
        var inMemory = true
        var unloads = 0
        override val isModelInMemory: Boolean get() = inMemory

        override suspend fun cleanup() {
            unloads++
            inMemory = false
        }
    }

    private inner class Rig(vararg lines: TranscriptionSegment) {
        val repository = FakeTranscriptionRepository(session(), lines.toList())
        val reader = FakeReader(english = readings)
        val model = Model()
        val heard = mutableListOf<ReadingsLog.Event>()
        val ensure = EnsureReadingsUseCase(ReadingStep(repository, reader, model, now = { 7L }, clock = { 0L })) { heard += it }

        fun ensure(work: EnsureReadingsUseCase.Work = EnsureReadingsUseCase.Work.REWRITE) = runBlocking { ensure("s1", work) }

        fun lines(): List<String> = heard.map(ReadingsLog::line)
    }

    @Test
    fun `a recording without a line the reader reads, or with all of them read, is ready at once, nothing loaded or logged`() {
        val rig = Rig(english)

        assertEquals(EnsureReadingsUseCase.Outcome.Ready, rig.ensure())
        assertTrue(rig.reader.events.isEmpty())
        assertEquals(0, rig.model.unloads)
        assertTrue(rig.heard.isEmpty())

        val read = Rig(bank, english)
        assertEquals(EnsureReadingsUseCase.Outcome.Ready, read.ensure())
        read.reader.events.clear()
        read.heard.clear()
        // Asked again, there is nothing left to read.
        assertEquals(EnsureReadingsUseCase.Outcome.Ready, read.ensure())
        assertTrue(read.reader.events.isEmpty())
        assertTrue(read.heard.isEmpty())
    }

    @Test
    fun `the lines it lacks are read and stored by the Reading step's rules, the AI model let go first, and counted for the log`() {
        val rig = Rig(bank, okay, english, roof)
        rig.reader.onRead = { assertFalse("the AI model is still in memory as the reader reads", rig.model.inMemory) }

        assertEquals(EnsureReadingsUseCase.Outcome.Ready, rig.ensure(EnsureReadingsUseCase.Work.FIND_AGAIN))

        assertEquals(1, rig.model.unloads)
        assertEquals(listOf("load", "read Tomorrow at ten the bank has to be called.", "read The roof of the shed is leaking again.", "release"), rig.reader.events)
        // The fragment is read empty without the model; the English line has no reading.
        assertEquals(
            mapOf(bank.id to "Tomorrow at ten the bank has to be called.", okay.id to "", roof.id to "The roof of the shed is leaking again."),
            rig.repository.readings.associate { it.segmentId to it.text }
        )
        assertEquals(
            listOf(
                "Readings of s1 for Find again: read 3 lines in 0 ms, 2 kept, 1 empty (1 fragments, 0 under the floor of -1.5, " +
                    "0 outside the band, 0 repeating, 0 blank, 0 not read)"
            ),
            rig.lines()
        )
        assertFalse(rig.heard.single().warns)
    }

    @Test
    fun `fragments alone are read empty without the model`() {
        val rig = Rig(okay, english)

        assertEquals(EnsureReadingsUseCase.Outcome.Ready, rig.ensure())

        assertTrue(rig.reader.events.isEmpty())
        assertEquals(0, rig.model.unloads)
        assertEquals(mapOf(okay.id to ""), rig.repository.readings.associate { it.segmentId to it.text })
    }

    @Test
    fun `without the reader's files the readings are missing at once, nothing loaded and the AI model left in memory`() {
        val rig = Rig(bank, english)
        rig.reader.onPhone = false

        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.NO_READER), rig.ensure(EnsureReadingsUseCase.Work.MAP))

        assertTrue(rig.reader.events.isEmpty())
        assertEquals(0, rig.model.unloads)
        assertTrue(rig.repository.readings.isEmpty())
        assertEquals(listOf("Readings of s1 for The map missing: the reader isn't on the phone"), rig.lines())
        assertTrue(rig.heard.single().warns)
    }

    @Test
    fun `a reader held off, stopped, short of memory or failing leaves them missing, with what was read before stored`() {
        val held = Rig(bank, roof)
        held.reader.refusal = ReaderNotLoadedException.Why.SPEECH_MODEL
        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.HELD_OFF), held.ensure(EnsureReadingsUseCase.Work.TEXT_AGAIN))
        assertEquals("Readings of s1 for Text again missing: the reader can't load beside the speech model now", held.lines().last())

        val stopped = Rig(bank, roof)
        stopped.reader.yieldsAt = 2
        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.STOPPED), stopped.ensure())
        assertEquals(listOf(bank.id), stopped.repository.readings.map { it.segmentId })
        assertEquals("Readings of s1 for Rewrite missing: a capture opened, which stopped the reader", stopped.lines().last())

        val short = Rig(bank)
        short.reader.loadStops = true
        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.NO_MEMORY), short.ensure())
        assertEquals("Readings of s1 for Rewrite missing: the phone's memory stopped the reader's load", short.lines().last())

        val failing = Rig(bank, roof)
        failing.reader.failsAfter = 1
        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.FAILED), failing.ensure(EnsureReadingsUseCase.Work.RERUN))
        assertEquals(listOf(bank.id), failing.repository.readings.map { it.segmentId })
        assertEquals("Readings of s1 for Debug rerun missing: the reader failed (ReaderFailedException)", failing.lines().last())
        // Each reading that loaded the reader has its counts line before the reason.
        assertTrue(failing.lines().first(), failing.lines().first().startsWith("Readings of s1 for Debug rerun: read 2 lines in 0 ms, 1 kept"))
    }

    @Test
    fun `a reader whose files went between the look and the load is missing as none on the phone`() {
        val rig = Rig(bank)
        rig.reader.refusal = ReaderNotLoadedException.Why.NOT_ON_PHONE

        assertEquals(EnsureReadingsUseCase.Outcome.Missing(EnsureReadingsUseCase.Why.NO_READER), rig.ensure())
        assertEquals("Readings of s1 for Rewrite missing: the reader isn't on the phone", rig.lines().last())
    }

    @Test
    fun `the log's lines name what kept the reader from loading`() {
        fun held(why: ReaderNotLoadedException.Why?) =
            ReadingsLog.line(ReadingsLog.Event.Missing("s1", EnsureReadingsUseCase.Work.REWRITE, EnsureReadingsUseCase.Why.HELD_OFF, besides = why))

        assertEquals("Readings of s1 for Rewrite missing: the reader can't load beside the AI model now", held(ReaderNotLoadedException.Why.AI_MODEL))
        assertEquals("Readings of s1 for Rewrite missing: the reader can't load beside a capture now", held(ReaderNotLoadedException.Why.CAPTURE))
        assertEquals("Readings of s1 for Rewrite missing: the reader can't load beside a capture now", held(null))
        assertEquals(
            "Readings of s1 for Rewrite missing: the reader failed",
            ReadingsLog.line(ReadingsLog.Event.Missing("s1", EnsureReadingsUseCase.Work.REWRITE, EnsureReadingsUseCase.Why.FAILED))
        )
    }
}
