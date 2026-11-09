package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TranscriptionRepositoryImpl]'s place and transcript model on an in-memory database: a new
 * recording starts with the live speech model and no place, a place is stored whole and removed
 * whole, one off the earth is refused, and both setters leave a session that is gone alone.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryImplPlaceTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepositoryImpl

    private val cologne = RecordingPlace(latitude = 50.9375, longitude = 6.9603, accuracyMeters = 12.5F, name = "Köln")

    @Before
    fun openDatabase() {
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
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private suspend fun newRecording(): String = repository.createSession(name = null).getOrThrow().id

    private suspend fun stored(id: String) = repository.getSession(id).first()!!

    @Test
    fun aNewRecordingStartsWithTheLiveModelAndNoPlace() = runBlocking {
        val created = repository.createSession(name = null).getOrThrow()

        assertEquals(SpeechModelId.LIVE.id, created.transcriptModel)
        assertEquals(SpeechModelId.LIVE.id, stored(created.id).transcriptModel)
        assertNull(stored(created.id).place)
    }

    @Test
    fun aPlaceIsStoredAndRemovedWhole() = runBlocking {
        val id = newRecording()

        assertTrue(repository.setPlace(id, cologne).isSuccess)
        assertEquals(cologne, stored(id).place)

        assertTrue(repository.setPlace(id, null).isSuccess)
        val row = db.transcriptionSessionDao().getSession(id).first()!!
        assertEquals(listOf(null, null, null, null), listOf(row.latitude, row.longitude, row.locationAccuracyMeters, row.placeName))
        assertEquals(SpeechModelId.LIVE.id, row.transcriptModel)
    }

    @Test
    fun aBlankNameIsStoredAsNone() = runBlocking {
        val id = newRecording()

        assertTrue(repository.setPlace(id, cologne.copy(accuracyMeters = null, name = "  ")).isSuccess)

        assertEquals(cologne.copy(accuracyMeters = null, name = null), stored(id).place)
    }

    @Test
    fun aPlaceOffTheEarthIsRefusedAndTheOneStoredKept() = runBlocking {
        val id = newRecording()
        repository.setPlace(id, cologne)

        assertTrue(repository.setPlace(id, cologne.copy(latitude = 91.0)).isFailure)
        assertTrue(repository.setPlace(id, cologne.copy(longitude = Double.NaN)).isFailure)

        assertEquals(cologne, stored(id).place)
    }

    @Test
    fun theTranscriptModelIsStoredAndForgotten() = runBlocking {
        val id = newRecording()

        assertTrue(repository.setTranscriptModel(id, SpeechModelId.COHERE_TRANSCRIBE.id).isSuccess)
        assertEquals("cohere-transcribe-14-lang-int8", stored(id).transcriptModel)

        assertTrue(repository.setTranscriptModel(id, " ").isSuccess)
        assertNull(stored(id).transcriptModel)
    }

    @Test
    fun aRecordingThatIsGoneIsLeftAlone() = runBlocking {
        assertTrue(repository.setPlace("gone", cologne).isSuccess)
        assertTrue(repository.setTranscriptModel("gone", SpeechModelId.PARAKEET_V2_EN.id).isSuccess)

        assertEquals(0, db.transcriptionSessionDao().getSessionCount())
    }
}
