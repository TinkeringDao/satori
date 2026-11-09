package com.satori.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.database.AppDatabase
import com.satori.data.database.entity.CategoryEntity
import com.satori.domain.model.RecordingMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [TranscriptionRepositoryImpl.setCategory], which "Record as" and "Change type" store a switch
 * with, on an in-memory database: the category and its mode in one statement, so the home list
 * names the new category and the notes follow its mode; never for a Translate recording or into
 * one, nor into a category that is gone or records in another mode, and a recording that is gone
 * is left alone.
 */
@RunWith(AndroidJUnit4::class)
class TranscriptionRepositoryImplCategoryTest {

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
        listOf(
            CategoryEntity("voice-note", "Voice note", RecordingMode.SIMPLE_LISTENING.name, sortOrder = 0, createdAt = 1L),
            CategoryEntity("meeting", "Meeting", RecordingMode.SHORT_MEETING.name, sortOrder = 1, createdAt = 1L),
            CategoryEntity("board", "Board", RecordingMode.LONG_MEETING.name, sortOrder = 2, createdAt = 1L),
            CategoryEntity("trip", "Trip", RecordingMode.REAL_TIME_TRANSLATION.name, sortOrder = 3, createdAt = 1L)
        ).forEach { db.categoryDao().upsert(it) }
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    private suspend fun recording(mode: RecordingMode, categoryId: String?): String =
        repository.createSession(name = null, mode = mode, categoryId = categoryId).getOrThrow().id

    private suspend fun stored(id: String) = repository.getSession(id).first()!!

    @Test
    fun aVoiceNoteSwitchedToAMeetingStoresBothAndHomeListsItAsAMeeting() = runBlocking {
        val id = recording(RecordingMode.SIMPLE_LISTENING, "voice-note")

        assertTrue(repository.setCategory(id, "meeting", RecordingMode.SHORT_MEETING).getOrThrow())

        val session = stored(id)
        assertEquals("meeting", session.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, session.mode)
        assertEquals("Meeting", repository.getAllSessions().first().single().categoryName)
    }

    @Test
    fun aLongMeetingCategoryGivesTheLongMeetingMode() = runBlocking {
        val id = recording(RecordingMode.SIMPLE_LISTENING, "voice-note")

        assertTrue(repository.setCategory(id, "board", RecordingMode.LONG_MEETING).getOrThrow())

        assertEquals(RecordingMode.LONG_MEETING, stored(id).mode)
    }

    @Test
    fun aTranslationIsNeitherSwitchedNorSwitchedInto() = runBlocking {
        val translation = recording(RecordingMode.REAL_TIME_TRANSLATION, null)
        val voiceNote = recording(RecordingMode.SIMPLE_LISTENING, "voice-note")

        assertFalse(repository.setCategory(translation, "meeting", RecordingMode.SHORT_MEETING).getOrThrow())
        assertFalse(repository.setCategory(voiceNote, "trip", RecordingMode.REAL_TIME_TRANSLATION).getOrThrow())

        assertEquals(RecordingMode.REAL_TIME_TRANSLATION, stored(translation).mode)
        assertEquals(null, stored(translation).categoryId)
        assertEquals(RecordingMode.SIMPLE_LISTENING, stored(voiceNote).mode)
        assertEquals("voice-note", stored(voiceNote).categoryId)
    }

    @Test
    fun aCategoryThatIsGoneOrRecordsInAnotherModeChangesNothing() = runBlocking {
        val id = recording(RecordingMode.SIMPLE_LISTENING, "voice-note")

        assertFalse(repository.setCategory(id, "deleted", RecordingMode.SHORT_MEETING).getOrThrow())
        // The meeting category was edited to another mode since it was read.
        assertFalse(repository.setCategory(id, "meeting", RecordingMode.LONG_MEETING).getOrThrow())

        assertEquals("voice-note", stored(id).categoryId)
        assertEquals(RecordingMode.SIMPLE_LISTENING, stored(id).mode)
    }

    @Test
    fun aRecordingThatIsGoneIsLeftAlone() = runBlocking {
        val id = recording(RecordingMode.SIMPLE_LISTENING, "voice-note")

        assertFalse(repository.setCategory("gone", "meeting", RecordingMode.SHORT_MEETING).getOrThrow())

        assertEquals(RecordingMode.SIMPLE_LISTENING, stored(id).mode)
    }
}
