package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchMatchSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search finds a recording by the name of the place it was made ([SearchMatchSource.PLACE], the
 * name as the snippet), with the first letter in either case and by a part of the name, and not
 * once the place is removed or when the place has no name.
 */
@RunWith(AndroidJUnit4::class)
class PlaceSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepositoryImpl

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

    private suspend fun recordingAt(place: RecordingPlace?): String {
        val id = repository.createSession(name = null).getOrThrow().id
        if (place != null) repository.setPlace(id, place).getOrThrow()
        return id
    }

    private suspend fun found(query: String) = repository.searchTranscriptions(query).first()

    @Test
    fun aRecordingIsFoundByThePlaceItWasMade() = runBlocking {
        val cologne = recordingAt(RecordingPlace(50.9375, 6.9603, 10F, "Köln"))
        recordingAt(RecordingPlace(48.2085, 16.3721, 8F, "Vienna, AT"))
        recordingAt(null)

        val result = found("Köln").single()

        assertEquals(cologne, result.sessionId)
        assertEquals(SearchMatchSource.PLACE, result.matchSource)
        assertEquals("Köln", result.snippet)
    }

    @Test
    fun theFirstLetterMatchesInEitherCaseAndAPartOfTheNameMatches() = runBlocking {
        val cologne = recordingAt(RecordingPlace(50.9375, 6.9603, 10F, "Köln"))
        val vienna = recordingAt(RecordingPlace(48.2085, 16.3721, 8F, "Vienna, AT"))

        assertEquals(listOf(cologne), found("köln").map { it.sessionId })
        assertEquals(listOf(vienna), found("enna").map { it.sessionId })
        assertEquals(listOf(vienna), found(", AT").map { it.sessionId })
    }

    @Test
    fun aRemovedPlaceAndAPlaceWithoutANameFindNothing() = runBlocking {
        val removed = recordingAt(RecordingPlace(50.9375, 6.9603, 10F, "Köln"))
        repository.setPlace(removed, null).getOrThrow()
        recordingAt(RecordingPlace(54.5, 3.5, 30F, null))

        assertTrue(found("Köln").isEmpty())
        assertTrue(found("54.5").isEmpty())
    }
}
