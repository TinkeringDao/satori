package com.satori.domain.reading

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript as the AI steps read it (0.8.0): each Malayalam line its current reading, left out
 * without one, never given as it is; every other line as it is. Synthetic lines only.
 */
class ReadableSegmentsTest {

    // Made up: "We will meet tomorrow morning." / "Bring the papers." / "Okay."
    private val meet = segment("നമുക്ക് നാളെ രാവിലെ കാണാം.", at = 1_000L)
    private val papers = segment("പേപ്പറുകൾ കൊണ്ടുവരണം.", at = 2_000L)
    private val english = segment("Call the bank about the card.", at = 3_000L)

    private fun readingOf(line: TranscriptionSegment, text: String, of: String = line.text) = SegmentReading(
        segmentId = line.id,
        sessionId = line.sessionId,
        sourceSha256 = SegmentReading.sourceHashOf(of),
        text = text,
        model = "reader",
        score = -0.4,
        createdAt = 1L
    )

    @Test
    fun `a line with a Malayalam letter or sign needs the reader, one in Latin letters or digits alone doesn't`() {
        assertTrue(ReadableSegments.needsReader(meet.text))
        // English with one Malayalam word in it: the AI model would get Malayalam.
        assertTrue(ReadableSegments.needsReader("Call Anna about the ബജറ്റ് today"))
        // A vowel sign alone, as a speech model may leave one.
        assertTrue(ReadableSegments.needsReader("ാ"))
        assertFalse(ReadableSegments.needsReader(english.text))
        assertFalse(ReadableSegments.needsReader("Morgen um halb 8 die Bank anrufen."))
        // Malayalam digits are no letters: a line of them alone says nothing in Malayalam.
        assertFalse(ReadableSegments.needsReader("൧൨ 12"))
        assertFalse(ReadableSegments.needsReader(""))
    }

    @Test
    fun `each Malayalam line reads as its reading, with its own id and time, and an English line as it is`() {
        val read = ReadableSegments.of(
            listOf(meet, english, papers),
            listOf(readingOf(papers, "Bring the papers."), readingOf(meet, "We will meet tomorrow morning."))
        )

        assertEquals(
            listOf(
                meet.copy(text = "We will meet tomorrow morning."),
                english,
                papers.copy(text = "Bring the papers.")
            ),
            read
        )
    }

    @Test
    fun `a Malayalam line without a reading, with one of its earlier text or with an empty one is left out`() {
        val edited = segment("നമുക്ക് മറ്റന്നാൾ കാണാം.", at = 4_000L)
        // Its reading is of the text it had before the owner edited it.
        val stale = readingOf(edited, "We will meet tomorrow.", of = "നമുക്ക് നാളെ കാണാം.")
        val okay = segment("ശരി.", at = 5_000L)

        val read = ReadableSegments.of(
            listOf(meet, papers, edited, okay, english),
            listOf(readingOf(meet, "We will meet tomorrow morning."), stale, readingOf(okay, ""))
        )

        assertEquals(listOf(meet.copy(text = "We will meet tomorrow morning."), english), read)
    }

    @Test
    fun `a transcript without Malayalam reads as it is, its readings or none`() {
        val lines = listOf(english, segment("Und dann zum Markt.", at = 4_000L), segment("partial", at = 5_000L, complete = false))

        assertEquals(lines, ReadableSegments.of(lines, emptyList()))
    }

    @Test
    fun `the lines still to read are the complete Malayalam ones without a current reading`() {
        val edited = segment("നമുക്ക് മറ്റന്നാൾ കാണാം.", at = 4_000L)
        val partial = segment("പേപ്പറുകൾ കൊണ്ടു", at = 6_000L, complete = false)
        val readings = listOf(readingOf(meet, "We will meet tomorrow morning."), readingOf(edited, "We will meet tomorrow.", of = "നമുക്ക് നാളെ കാണാം."))

        assertEquals(listOf(papers, edited), ReadableSegments.unread(listOf(meet, english, papers, edited, partial), readings))
        // Never read: a line read before an edit was read, and its edit leaves it out of the notes only.
        assertEquals(listOf(papers), ReadableSegments.neverRead(listOf(meet, english, papers, edited, partial), readings))
    }

    @Test
    fun `a reading is current only for its own line and the text it read`() {
        val reading = readingOf(meet, "We will meet tomorrow morning.")

        assertTrue(reading.isCurrentFor(meet))
        assertFalse(reading.isCurrentFor(meet.copy(text = meet.text + " ")))
        assertFalse(reading.isCurrentFor(papers.copy(text = meet.text)))
    }

    @Test
    fun `the source hash is the SHA-256 of the text as UTF-8, in lower-case hex`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", SegmentReading.sourceHashOf("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SegmentReading.sourceHashOf(""))
        val hash = SegmentReading.sourceHashOf(meet.text)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
        assertNotEquals(hash, SegmentReading.sourceHashOf(papers.text))
    }

    @Test
    fun `the lines the rules judge found items by keep a line without a current reading as it was said`() {
        val edited = segment("നമുക്ക് മറ്റന്നാൾ കാണാം.", at = 4_000L)
        val okay = segment("ശരി.", at = 5_000L)
        val readings = listOf(
            readingOf(meet, "We will meet tomorrow morning."),
            readingOf(edited, "We will meet tomorrow.", of = "നമുക്ക് നാളെ കാണാം."),
            readingOf(okay, "")
        )

        assertEquals(
            listOf(meet.copy(text = "We will meet tomorrow morning."), papers, edited, okay, english),
            ReadableSegments.withReadings(listOf(meet, papers, edited, okay, english), readings)
        )
        val lines = listOf(meet, english)
        assertEquals(lines, ReadableSegments.withReadings(lines, emptyList()))
    }

    @Test
    fun `a recording's lines with their readings give the lines the AI reads, the ones never read and each read line as it was said`() {
        val partial = segment("പേപ്പറുകൾ കൊണ്ടു", at = 6_000L, complete = false)
        val lines = LinesWithReadings(listOf(meet, english, papers, partial), listOf(readingOf(meet, "We will meet tomorrow morning.")))

        assertEquals(listOf(meet.copy(text = "We will meet tomorrow morning."), english), lines.readable)
        assertTrue(lines.readByReader)
        // The papers line was never read; the partial one isn't complete yet.
        assertTrue(lines.hasNeverRead)
        assertEquals(mapOf(meet.id to meet.text), lines.saidOfRead)

        val read = LinesWithReadings(listOf(meet, english, papers), listOf(readingOf(meet, "We will meet tomorrow morning."), readingOf(papers, "")))
        assertFalse(read.hasNeverRead)
        // A line read empty is no line the AI reads, so none of its words is asked about.
        assertEquals(mapOf(meet.id to meet.text), read.saidOfRead)

        val said = LinesWithReadings(listOf(english), emptyList())
        assertEquals(listOf(english), said.readable)
        assertFalse(said.readByReader)
        assertFalse(said.hasNeverRead)
        assertTrue(said.saidOfRead.isEmpty())
    }

    @Test
    fun `a repository's lines with their readings are its lines and its readings, read together by default`() = runBlocking {
        val repository = FakeTranscriptionRepository(session(), listOf(meet, english, papers))
        assertTrue(repository.saveReading(readingOf(meet, "We will meet tomorrow morning.")).isSuccess)

        val lines = repository.getLinesWithReadings("s1")

        assertEquals(listOf(meet, english, papers), lines.said)
        assertEquals(listOf(meet.id), lines.readings.map { it.segmentId })
        assertEquals(repository.getReadableSegments("s1"), lines.readable)
    }

    @Test
    fun `a repository's readable lines are its lines with its readings, by the same rule`() = runBlocking {
        // The interface's own default: the fake stores readings beside its lines and reads them back.
        val repository = FakeTranscriptionRepository(session(), listOf(meet, english, papers))
        assertTrue(repository.saveReading(readingOf(meet, "We will meet tomorrow morning.")).isSuccess)

        assertEquals(listOf(meet.copy(text = "We will meet tomorrow morning."), english), repository.getReadableSegments("s1"))
        assertEquals(listOf(meet.id), repository.readingsOf("s1").map { it.segmentId })
        assertTrue(repository.readingsOf("other").isEmpty())
    }
}
