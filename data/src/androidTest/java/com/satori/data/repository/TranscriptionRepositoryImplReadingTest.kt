package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The readings of transcript lines (0.8.0, R4) on an in-memory database: stored one a line, read
 * back with the lines as the AI steps read them, a stale one (the line edited since) read as none,
 * and taken away with their lines when a transcript is replaced or a recording deleted; since R6 the
 * lines as they were said read with them in one go. Synthetic lines only.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryImplReadingTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepositoryImpl
    private lateinit var sessionId: String

    // Made up: "We will meet tomorrow morning." / "Bring the papers."
    private val meet = "നമുക്ക് നാളെ രാവിലെ കാണാം."
    private val papers = "പേപ്പറുകൾ കൊണ്ടുവരണം."

    @Before
    fun openDatabase() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        repository = TranscriptionRepositoryImpl(
            sessionDao = db.transcriptionSessionDao(),
            segmentDao = db.transcriptionSegmentDao(),
            insightDao = db.llmInsightDao(),
            searchDao = db.searchDao(),
            mindMapDao = db.mindMapDao(),
            audio = NoAudio,
            readingDao = db.segmentReadingDao()
        )
        sessionId = repository.createSession(name = null, language = RecordingLanguage.MALAYALAM).getOrThrow().id
        repository.saveSegment(line("l1", meet, at = 1_000L)).getOrThrow()
        repository.saveSegment(line("l2", "Call the bank.", at = 2_000L)).getOrThrow()
        repository.saveSegment(line("l3", papers, at = 3_000L)).getOrThrow()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private fun line(id: String, text: String, at: Long, session: String = sessionId) =
        TranscriptionSegment(id = id, sessionId = session, text = text, timestamp = at, isComplete = true)

    private fun reading(segmentId: String, of: String, text: String, session: String = sessionId) = SegmentReading(
        segmentId = segmentId,
        sessionId = session,
        sourceSha256 = SegmentReading.sourceHashOf(of),
        text = text,
        model = "indictrans2-indic-en-dist-200m-int8",
        score = -0.41,
        createdAt = 1_760_300_000_000L
    )

    @Test
    fun theAiStepsReadEachMalayalamLineAsItsReadingAndLeaveOutOneWithout() = runBlocking {
        repository.saveReading(reading("l1", meet, "We will meet tomorrow morning.")).getOrThrow()

        val read = repository.getReadableSegments(sessionId)

        assertEquals(listOf("l1" to "We will meet tomorrow morning.", "l2" to "Call the bank."), read.map { it.id to it.text })
        assertEquals(listOf(1_000L, 2_000L), read.map { it.timestamp })
        assertEquals(listOf("l1"), repository.readingsOf(sessionId).map { it.segmentId })
    }

    @Test
    fun theLinesAsTheyWereSaidComeWithTheirReadingsAndTheLinesTheAiReads() = runBlocking {
        // R6: one read of both, for the language found in what was said (D8) and the lines the AI reads.
        repository.saveReading(reading("l1", meet, "We will meet tomorrow morning.")).getOrThrow()

        val lines = repository.getLinesWithReadings(sessionId)

        assertEquals(listOf("l1" to meet, "l2" to "Call the bank.", "l3" to papers), lines.said.map { it.id to it.text })
        assertEquals(listOf("l1"), lines.readings.map { it.segmentId })
        assertEquals(repository.getReadableSegments(sessionId), lines.readable)
        assertEquals(mapOf("l1" to meet), lines.saidOfRead)
        assertTrue(lines.hasNeverRead)
    }

    @Test
    fun aReadingStoredAgainTakesThePlaceOfTheFirst() = runBlocking {
        repository.saveReading(reading("l3", papers, "Bring papers.")).getOrThrow()
        repository.saveReading(reading("l3", papers, "Bring the papers.")).getOrThrow()

        assertEquals(listOf("Bring the papers."), repository.readingsOf(sessionId).map { it.text })
        assertEquals("Bring the papers.", repository.getReadableSegments(sessionId).single { it.id == "l3" }.text)
    }

    @Test
    fun aLineEditedSinceItWasReadReadsAsUnreadAndKeepsItsOldReading() = runBlocking {
        repository.saveReading(reading("l1", meet, "We will meet tomorrow morning.")).getOrThrow()

        repository.updateSegmentText("l1", "നമുക്ക് മറ്റന്നാൾ കാണാം.").getOrThrow()

        assertEquals(listOf("l2"), repository.getReadableSegments(sessionId).map { it.id })
        // The row stays, of the text it read: the line was read once, before its edit.
        assertEquals(listOf(SegmentReading.sourceHashOf(meet)), repository.readingsOf(sessionId).map { it.sourceSha256 })
    }

    @Test
    fun aReplacedTranscriptAndADeletedRecordingTakeTheirReadingsWithThem() = runBlocking {
        repository.saveReading(reading("l1", meet, "We will meet tomorrow morning.")).getOrThrow()
        repository.saveReading(reading("l3", papers, "Bring the papers.")).getOrThrow()

        // The joining after Stop, or Transcribe again: new lines with new ids.
        repository.replaceSegments(sessionId, listOf(line("j1", "$meet $papers", at = 1_000L))).getOrThrow()

        assertTrue(repository.readingsOf(sessionId).isEmpty())
        assertTrue(repository.getReadableSegments(sessionId).isEmpty())

        repository.saveReading(reading("j1", "$meet $papers", "We will meet tomorrow morning; bring the papers.")).getOrThrow()
        repository.deleteSession(sessionId).getOrThrow()

        assertTrue(repository.readingsOf(sessionId).isEmpty())
        assertEquals(0, db.segmentReadingDao().linesAndReadings(sessionId).readings.size)
    }

    @Test
    fun aReadingOfALineThatIsGoneOrOfAnotherRecordingsLineIsNotStored() = runBlocking {
        val other = repository.createSession(name = null, language = RecordingLanguage.MALAYALAM).getOrThrow().id
        repository.saveSegment(line("o1", meet, at = 1_000L, session = other)).getOrThrow()

        assertTrue(repository.saveReading(reading("gone", meet, "We will meet.")).isFailure)
        assertTrue(repository.saveReading(reading("o1", meet, "We will meet.", session = sessionId)).isFailure)
        assertTrue(repository.saveReading(reading("o1", meet, "We will meet.", session = other)).isSuccess)

        assertTrue(repository.readingsOf(sessionId).isEmpty())
        assertEquals(listOf("o1"), repository.readingsOf(other).map { it.segmentId })
    }
}
