package com.satori.domain.usecase.finish

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.KeptAudio
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.RecordingAudioRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardSilentRecordingUseCaseTest {

    private fun repository(
        recording: TranscriptionSession? = session(),
        segments: List<TranscriptionSegment> = emptyList()
    ) = FakeTranscriptionRepository(recording, segments)

    /** The end of a capture this recorder ran, as Stop, a dead microphone or leaving it see it. */
    private fun afterCapture(
        repository: FakeTranscriptionRepository,
        heard: Boolean = false,
        mayLackSpeech: Boolean = false,
        audio: RecordingAudioRepository = KeptAudio()
    ) = runBlocking {
        DiscardSilentRecordingUseCase(repository, audio)("s1", captured = true, heard = heard, mayLackSpeech = mayLackSpeech)
    }

    /** A recorder that closes without having recorded, or a quick-key press that never opened one. */
    private fun withoutCapture(repository: FakeTranscriptionRepository, audio: RecordingAudioRepository = KeptAudio()) =
        runBlocking { DiscardSilentRecordingUseCase(repository, audio)("s1", captured = false) }

    // ─── After a capture ─────────────────────────────────────────────────────────

    @Test
    fun `a recording without a single line is deleted`() {
        val repository = repository(recording = session().copy(durationMs = 4_000L))

        assertEquals(true, afterCapture(repository).getOrNull())

        assertNull(repository.session)
    }

    @Test
    fun `a recording whose lines are blank or punctuation is deleted with its lines and notes`() {
        val repository = repository(
            segments = listOf(segment("", at = 1_000L), segment("  ", at = 2_000L), segment(".", at = 3_000L), segment("?!…", at = 4_000L))
        ).apply { insights = listOf(insight("Note", ".", at = 4_500L)) }

        assertEquals(true, afterCapture(repository).getOrNull())

        assertNull(repository.session)
        assertTrue(repository.segments.isEmpty())
        assertTrue(repository.insights.isEmpty())
    }

    @Test
    fun `a long recording in which nothing was said goes as well, with its audio`() {
        // All its audio was transcribed, so the audio holds nothing the lines lack.
        val repository = repository(recording = session().copy(durationMs = 54_000L), segments = listOf(segment(".", at = 30_000L)))

        assertEquals(true, afterCapture(repository, audio = KeptAudio("s1")).getOrNull())

        assertNull(repository.session)
    }

    @Test
    fun `a line of digits only is speech`() {
        val repository = repository(segments = listOf(segment(".", at = 1_000L), segment("10", at = 2_000L)))

        assertEquals(false, afterCapture(repository).getOrNull())

        assertNotNull(repository.session)
        assertEquals(2, repository.segments.size)
    }

    @Test
    fun `a word in any script is speech`() {
        for (word in listOf("Ja.", "Äh", "ß", "日本")) {
            val repository = repository(segments = listOf(segment(word, at = 1_000L)))

            assertEquals(word, false, afterCapture(repository).getOrNull())
        }
    }

    @Test
    fun `words the recognizer heard keep the recording though no line has them`() {
        // The last line's save was cut short, or the capture was cut off within an utterance.
        val repository = repository(segments = listOf(segment(".", at = 1_000L)))

        assertEquals(false, afterCapture(repository, heard = true).getOrNull())

        assertNotNull(repository.session)
    }

    @Test
    fun `a recording that may lack speech is kept when something was recorded in it`() {
        // A length, or only audio: stopped within the first second, the length saved is 0.
        val timed = repository(recording = session().copy(durationMs = 20_000L))
        val withAudio = repository(segments = listOf(segment(".", at = 400L)))

        assertEquals(false, afterCapture(timed, mayLackSpeech = true).getOrNull())
        assertEquals(false, afterCapture(withAudio, mayLackSpeech = true, audio = KeptAudio("s1")).getOrNull())

        assertNotNull(timed.session)
        assertNotNull(withAudio.session)
    }

    @Test
    fun `a recognizer that failed before anything was recorded leaves nothing to keep`() {
        // No length, no line and no audio: the microphone or the speech model didn't start.
        val repository = repository()

        assertEquals(true, afterCapture(repository, mayLackSpeech = true).getOrNull())

        assertNull(repository.session)
    }

    @Test
    fun `audio that can't be read now still counts as audio`() {
        val repository = repository()

        assertEquals(false, afterCapture(repository, mayLackSpeech = true, audio = KeptAudio("s1", readable = false)).getOrNull())

        assertNotNull(repository.session)
    }

    @Test
    fun `a recording continued after an earlier capture with speech is kept`() {
        val repository = repository(
            recording = session().copy(durationMs = 95_000L),
            segments = listOf(segment("Remind me to call the bank.", at = 1_000L), segment(".", at = 90_000L))
        )

        assertEquals(false, afterCapture(repository).getOrNull())

        assertNotNull(repository.session)
    }

    // ─── Without a capture ───────────────────────────────────────────────────────

    @Test
    fun `a recording that never recorded anything is deleted`() {
        val repository = repository()

        assertEquals(true, withoutCapture(repository).getOrNull())

        assertNull(repository.session)
    }

    @Test
    fun `a recording with a length that this recorder never captured is kept`() {
        // Recorded before the app was killed: its last words may be missing from the transcript.
        val repository = repository(recording = session().copy(durationMs = 54_000L))

        assertEquals(false, withoutCapture(repository).getOrNull())

        assertNotNull(repository.session)
    }

    @Test
    fun `a recording killed before its length was first saved is kept for its audio`() {
        // The length is saved every 20 s; 12 s in, a noise line and words not yet transcribed.
        val repository = repository(segments = listOf(segment(".", at = 4_000L)))

        assertEquals(false, withoutCapture(repository, audio = KeptAudio("s1")).getOrNull())

        assertNotNull(repository.session)
        assertEquals(true, withoutCapture(repository).getOrNull())
    }

    @Test
    fun `a recording stopped within its first second keeps the words that were said`() {
        // The timer steps once a second, so the length saved at Stop is 0.
        val repository = repository(segments = listOf(segment("Remind me to call the bank.", at = 400L)))

        assertEquals(false, withoutCapture(repository).getOrNull())
        assertEquals(false, afterCapture(repository).getOrNull())

        assertNotNull(repository.session)
    }

    // ─── Either way ──────────────────────────────────────────────────────────────

    @Test
    fun `a recording that is not there leaves nothing to delete`() {
        val repository = repository(recording = null)

        assertEquals(false, afterCapture(repository).getOrNull())
        assertEquals(false, withoutCapture(repository).getOrNull())
    }

    @Test
    fun `a silent recording that cannot be deleted is reported and stays`() {
        val repository = repository().apply { deleteFailure = IllegalStateException("disk full") }

        val result = afterCapture(repository)

        assertTrue(result.isFailure)
        assertEquals("disk full", result.exceptionOrNull()?.message)
        assertNotNull(repository.session)
    }

    @Test
    fun `speech is a letter or a digit`() {
        assertTrue(DiscardSilentRecordingUseCase.holdsSpeech("10"))
        assertTrue(DiscardSilentRecordingUseCase.holdsSpeech(" ok "))
        assertFalse(DiscardSilentRecordingUseCase.holdsSpeech(""))
        assertFalse(DiscardSilentRecordingUseCase.holdsSpeech(" .,;:?! - … "))
        assertFalse(DiscardSilentRecordingUseCase.holdsSpeech("♪ 🎵"))
    }
}
