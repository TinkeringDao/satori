package com.satori.domain.usecase.transcription

import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.TranscriptionRepository

/**
 * Use case for creating a new transcription session.
 *
 * This is typically called when the user starts a new recording session.
 * The LLM context should be reset after creating a new session to ensure
 * isolation between sessions.
 *
 * @property transcriptionRepository Repository for transcription data
 */
class CreateSessionUseCase(
    private val transcriptionRepository: TranscriptionRepository
) {
    /**
     * Create a new transcription session.
     *
     * @param name Optional user-provided name (can be null or blank)
     * @param mode Recording mode determining insight frequency and format
     * @param outputLanguage Optional target language for translation mode
     * @param insightStrategy Whether to generate insights in real-time or at end of session
     * @param topic Optional main subject/topic for focused AI insights
     * @param categoryId The recording category to record it as, or null (or blank) for none
     * @param zoneId The time zone it is recorded in, or null (or blank) for the phone's current one
     * @param language The language it is recorded in
     * @return Result containing the created session or an error
     */
    suspend operator fun invoke(
        name: String?,
        mode: RecordingMode = RecordingMode.SIMPLE_LISTENING,
        outputLanguage: String? = null,
        insightStrategy: InsightStrategy = InsightStrategy.REAL_TIME,
        topic: String? = null,
        categoryId: String? = null,
        zoneId: String? = null,
        language: RecordingLanguage = RecordingLanguage.ENGLISH_OR_GERMAN
    ): Result<TranscriptionSession> {
        return transcriptionRepository.createSession(
            name = name?.takeIf { it.isNotBlank() },
            mode = mode,
            outputLanguage = outputLanguage,
            insightStrategy = insightStrategy,
            topic = topic?.takeIf { it.isNotBlank() },
            categoryId = categoryId?.takeIf { it.isNotBlank() },
            zoneId = zoneId?.takeIf { it.isNotBlank() },
            language = language
        )
    }
}
