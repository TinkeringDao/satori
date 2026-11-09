package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A line edited on its recording's page: stored with its new text, never with a blank one. */
class UpdateSegmentTextUseCaseTest {

    private val updated = mutableListOf<Pair<String, String>>()
    private val repository = object : TranscriptionRepository by FakeTranscriptionRepository(session = null) {
        override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> {
            updated += segmentId to newText
            return Result.success(Unit)
        }
    }
    private val update = UpdateSegmentTextUseCase(repository)

    @Test
    fun `an edited line is stored with its new text`() = runBlocking {
        update("line", "We still have to book the room.").getOrThrow()

        assertEquals(listOf("line" to "We still have to book the room."), updated)
    }

    @Test
    fun `a blank edit is refused, and the line stays as it was`() = runBlocking {
        for (text in listOf("", "   ", "\n\t ")) {
            assertTrue("\"$text\"", update("line", text).isFailure)
        }

        assertTrue(updated.isEmpty())
    }
}
