package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line the recorder stores: a complete one with its Malayalam as its script writes it
 * ([com.satori.domain.language.MalayalamText]), anything else as it came, and none without a
 * letter or a digit. The Malayalam is made up of code points, no line anyone said.
 */
class SaveSegmentUseCaseTest {

    private val saved = mutableListOf<TranscriptionSegment>()
    private val repository = object : TranscriptionRepository by FakeTranscriptionRepository(session = null) {
        override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> {
            saved += segment
            return Result.success(Unit)
        }
    }
    private val save = SaveSegmentUseCase(repository)

    // ka, a virama out of place, aa, la; and the same as the script writes it.
    private val broken = "\u0D15\u0D4D\u0D3E\u0D32"
    private val clean = "\u0D15\u0D3E\u0D32"

    private fun line(text: String, complete: Boolean = true) =
        TranscriptionSegment(id = "line", sessionId = "s1", text = text, timestamp = 1_000L, isComplete = complete)

    @Test
    fun `a complete line is stored with its Malayalam as its script writes it`() = runBlocking {
        save(line(broken)).getOrThrow()

        assertEquals(listOf(line(clean)), saved)
    }

    @Test
    fun `an English or German line, and a line not yet complete, are stored as they came`() = runBlocking {
        save(line("We still have to book the room.")).getOrThrow()
        save(line("Die Regale kommen am Dienstag.")).getOrThrow()
        save(line(broken, complete = false)).getOrThrow()

        assertEquals(listOf(line("We still have to book the room."), line("Die Regale kommen am Dienstag."), line(broken, complete = false)), saved)
    }

    @Test
    fun `a line with no word left is never stored, nor is a blank one`() = runBlocking {
        // Made-up texts of no word: the vowel sign aa and the anusvara, which the clean-up leaves
        // out as no letter carries them (as the start buzz came out in Malayalam), a zero width
        // joiner, a lone ".", spaces and nothing; complete or not.
        for (text in listOf("\u0D3E\u0D02", "\u200D", ".", "   ", "")) {
            assertTrue(text, save(line(text)).isSuccess)
            assertTrue(text, save(line(text, complete = false)).isSuccess)
        }

        assertEquals(emptyList<TranscriptionSegment>(), saved)
    }

    @Test
    fun `a line with one letter or digit is stored`() = runBlocking {
        // The letter ka, and a sign after it, which it carries.
        save(line("\u0D15")).getOrThrow()
        save(line("\u0D15\u0D02")).getOrThrow()
        save(line("7")).getOrThrow()

        assertEquals(listOf(line("\u0D15"), line("\u0D15\u0D02"), line("7")), saved)
    }
}
