package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreateSessionUseCaseTest {

    /** Records the category, zone and language createSession is given; nothing else is used. */
    private class RecordingRepository : TranscriptionRepository by FakeTranscriptionRepository(session = null) {
        var categoryId: String? = "not called"
        var zoneId: String? = "not called"
        var language: RecordingLanguage? = null

        override suspend fun createSession(
            name: String?,
            mode: RecordingMode,
            outputLanguage: String?,
            insightStrategy: InsightStrategy,
            topic: String?,
            categoryId: String?,
            zoneId: String?,
            language: RecordingLanguage
        ): Result<TranscriptionSession> {
            this.categoryId = categoryId
            this.zoneId = zoneId
            this.language = language
            return Result.success(TranscriptionSession(id = "new", name = name, createdAt = 0L, lastModifiedAt = 0L))
        }
    }

    @Test
    fun `records the session in the category and zone given`() = runBlocking {
        val repository = RecordingRepository()

        CreateSessionUseCase(repository)(name = null, categoryId = "meeting", zoneId = "Europe/Berlin")

        assertEquals("meeting", repository.categoryId)
        assertEquals("Europe/Berlin", repository.zoneId)
    }

    @Test
    fun `records the session in the language given, English or German unless one is`() = runBlocking {
        val malayalam = RecordingRepository()
        val plain = RecordingRepository()

        CreateSessionUseCase(malayalam)(name = null, categoryId = "meeting", language = RecordingLanguage.MALAYALAM)
        CreateSessionUseCase(plain)(name = null, categoryId = "meeting")

        assertEquals(RecordingLanguage.MALAYALAM, malayalam.language)
        assertEquals(RecordingLanguage.ENGLISH_OR_GERMAN, plain.language)
    }

    @Test
    fun `a blank category or zone counts as none`() = runBlocking {
        val repository = RecordingRepository()

        CreateSessionUseCase(repository)(name = null, categoryId = " ", zoneId = "")

        assertNull(repository.categoryId)
        assertNull(repository.zoneId)
    }
}
