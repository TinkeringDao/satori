package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * "Record as" while recording and "Change type" on the recording's page: a recording takes the
 * category and its mode in one write; a Translate recording is never switched, nor switched into;
 * what can't be stored changes nothing and says why.
 */
class ChangeRecordingTypeUseCaseTest {

    private val voiceNote = session().copy(mode = RecordingMode.SIMPLE_LISTENING, categoryId = "voice-note")
    private val categories = CategoriesHeld(*starterCategories.toTypedArray(), translatingCategory, longMeetingCategory)

    private fun change(recordings: FakeTranscriptionRepository) = ChangeRecordingTypeUseCase(recordings, categories)

    @Test
    fun `a voice note switched to a meeting stores the category and its mode together`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)

        val outcome = change(recordings)("s1", "meeting")

        assertEquals(Outcome.Changed(starterCategories[1]), outcome)
        // One write with both: the recording is never filed under one with the other's mode.
        assertEquals(listOf(Triple("s1", "meeting", RecordingMode.SHORT_MEETING)), recordings.categoryWrites)
        assertEquals("meeting", recordings.session?.categoryId)
        assertEquals(RecordingMode.SHORT_MEETING, recordings.session?.mode)
    }

    @Test
    fun `short or long meeting follows the category's mode, as at the start`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)

        change(recordings)("s1", "board")

        assertEquals(RecordingMode.LONG_MEETING, recordings.session?.mode)
        assertEquals("board", recordings.session?.categoryId)
    }

    @Test
    fun `a category of the same mode is still a change of category`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)

        val outcome = change(recordings)("s1", "idea")

        assertEquals(Outcome.Changed(starterCategories[2]), outcome)
        assertEquals("idea", recordings.session?.categoryId)
        assertEquals(RecordingMode.SIMPLE_LISTENING, recordings.session?.mode)
    }

    @Test
    fun `the category it has already stores nothing`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)

        assertSame(Outcome.Unchanged, change(recordings)("s1", "voice-note"))
        assertTrue(recordings.categoryWrites.isEmpty())
    }

    @Test
    fun `nothing is switched into a translation`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)

        assertSame(Outcome.Refused, change(recordings)("s1", "trip"))
        assertTrue(recordings.categoryWrites.isEmpty())
        assertEquals(voiceNote, recordings.session)
    }

    @Test
    fun `a translation is not switched`() = runBlocking {
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de")
        val recordings = FakeTranscriptionRepository(translation)

        assertSame(Outcome.Refused, change(recordings)("s1", "meeting"))
        assertTrue(recordings.categoryWrites.isEmpty())
        assertEquals(translation, recordings.session)
    }

    @Test
    fun `a category or a recording that is gone is refused`() = runBlocking {
        val recordings = FakeTranscriptionRepository(voiceNote)
        assertSame(Outcome.Refused, change(recordings)("s1", "deleted-meanwhile"))
        assertSame(Outcome.Refused, change(FakeTranscriptionRepository(null))("s1", "meeting"))
        assertTrue(recordings.categoryWrites.isEmpty())
    }

    @Test
    fun `a write the database turns down is refused`() = runBlocking {
        // Its one statement checks again: the category was edited to another mode meanwhile, say.
        val row = FakeTranscriptionRepository(voiceNote)
        val turningDown = object : TranscriptionRepository by row {
            override suspend fun setCategory(sessionId: String, categoryId: String, mode: RecordingMode) = Result.success(false)
        }

        assertSame(Outcome.Refused, ChangeRecordingTypeUseCase(turningDown, categories)("s1", "meeting"))
        assertEquals(voiceNote, row.session)
    }

    @Test
    fun `a write or a read that fails changes nothing and is told`() = runBlocking {
        val failure = IOException("disk full")
        val recordings = FakeTranscriptionRepository(voiceNote).apply { categoryFailure = failure }
        assertEquals(Outcome.Failed(failure), change(recordings)("s1", "meeting"))
        assertEquals(voiceNote, recordings.session)

        val unreadable = IOException("database closed")
        categories.readFailure = unreadable
        assertEquals(Outcome.Failed(unreadable), change(FakeTranscriptionRepository(voiceNote))("s1", "meeting"))
    }

    @Test
    fun `the choices leave out every category that translates`() {
        val all = listOf(*starterCategories.toTypedArray(), translatingCategory, longMeetingCategory)

        val choices = ChangeRecordingTypeUseCase.choices(RecordingMode.SIMPLE_LISTENING, all)

        assertEquals(listOf("voice-note", "meeting", "idea", "todo", "board"), choices.map { it.id })
    }

    @Test
    fun `a translation, or a recording whose mode isn't known yet, has no choices`() {
        val all = listOf(*starterCategories.toTypedArray(), translatingCategory)

        assertTrue(ChangeRecordingTypeUseCase.choices(RecordingMode.REAL_TIME_TRANSLATION, all).isEmpty())
        assertTrue(ChangeRecordingTypeUseCase.choices(null, all).isEmpty())
    }
}
