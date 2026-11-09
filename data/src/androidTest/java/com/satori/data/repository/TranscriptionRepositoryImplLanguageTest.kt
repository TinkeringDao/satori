package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.CategoryEntity
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The language a recording is made in, on an in-memory database: stored as it is created, in the
 * same insert, switched later by [TranscriptionRepositoryImpl.setLanguage] (while it records, on
 * its page) without touching its other columns, and read back by the home list and the page. The
 * language spoken in it is not known as it is created, and the finishing stores it
 * ([TranscriptionRepositoryImpl.setInputLanguage]) in its own column.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryImplLanguageTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: TranscriptionRepositoryImpl

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
        db.categoryDao().upsert(CategoryEntity("meeting", "Meeting", RecordingMode.SHORT_MEETING.name, sortOrder = 0, createdAt = 1L))
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private suspend fun stored(id: String) = repository.getSession(id).first()!!

    @Test
    fun aMeetingRecordedInMalayalamIsStoredSoFromItsFirstRow() = runBlocking {
        val created = repository.createSession(
            name = null,
            mode = RecordingMode.SHORT_MEETING,
            categoryId = "meeting",
            language = RecordingLanguage.MALAYALAM
        ).getOrThrow()

        assertEquals(RecordingLanguage.MALAYALAM, created.language)
        assertEquals(RecordingLanguage.MALAYALAM, stored(created.id).language)
        assertEquals("ml", db.transcriptionSessionDao().getSession(created.id).first()!!.language)
        assertEquals(RecordingLanguage.MALAYALAM, repository.getAllSessions().first().single().session.language)
        // Its transcript is the live one of the Malayalam model, which the finisher reads as live.
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, created.transcriptModel)
        assertEquals(SpeechModelId.INDICCONFORMER_ML.id, db.transcriptionSessionDao().getSession(created.id).first()!!.transcriptModel)
    }

    @Test
    fun aRecordingIsEnglishOrGermanUnlessItIsMadeInMalayalam() = runBlocking {
        val id = repository.createSession(name = null, categoryId = "meeting").getOrThrow().id

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, stored(id).language)
        assertNull(db.transcriptionSessionDao().getSession(id).first()!!.language)
        assertEquals(SpeechModelId.PARAKEET_V3.id, stored(id).transcriptModel)
    }

    @Test
    fun aSwitchStoresTheLanguageBothWaysAndLeavesTheRestAlone() = runBlocking {
        val id = repository.createSession(name = null, mode = RecordingMode.SHORT_MEETING, categoryId = "meeting").getOrThrow().id
        repository.renameSession(id, "Weekly sync").getOrThrow()

        assertTrue(repository.setLanguage(id, RecordingLanguage.MALAYALAM).getOrThrow())
        val switched = stored(id)
        assertEquals(RecordingLanguage.MALAYALAM, switched.language)
        assertEquals("Weekly sync", switched.name)
        assertEquals("meeting", switched.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, switched.mode)

        assertTrue(repository.setLanguage(id, RecordingLanguage.ENGLISH_OR_GERMAN).getOrThrow())
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, stored(id).language)
        // The language it has already is stored again.
        assertTrue(repository.setLanguage(id, RecordingLanguage.ENGLISH_OR_GERMAN).getOrThrow())
    }

    @Test
    fun aRecordingThatIsGoneIsLeftAlone() = runBlocking {
        val id = repository.createSession(name = null, categoryId = "meeting").getOrThrow().id

        assertFalse(repository.setLanguage("gone", RecordingLanguage.MALAYALAM).getOrThrow())

        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, stored(id).language)
    }

    @Test
    fun theSpokenLanguageIsUnknownAtFirstAndStoredByTheFinishingAlone() = runBlocking {
        val id = repository.createSession(
            name = null,
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "en",
            categoryId = "meeting"
        ).getOrThrow().id
        repository.renameSession(id, "Weekly planning").getOrThrow()

        // Not the old default "it".
        assertEquals("", stored(id).inputLanguage)

        repository.setInputLanguage(id, "de").getOrThrow()
        val finished = stored(id)
        assertEquals("de", finished.inputLanguage)
        assertEquals("en", finished.outputLanguage)
        assertEquals("Weekly planning", finished.name)
        assertEquals(RecordingMode.REAL_TIME_TRANSLATION, finished.mode)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, finished.language)

        // A recording that is gone is left alone.
        repository.setInputLanguage("gone", "de").getOrThrow()
        assertEquals(1, repository.getAllSessions().first().size)
    }
}
