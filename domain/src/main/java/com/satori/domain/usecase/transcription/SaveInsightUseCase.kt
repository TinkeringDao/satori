package com.satori.domain.usecase.transcription

import com.satori.domain.model.LlmInsight
import com.satori.domain.repository.TranscriptionRepository

/**
 * Use case for saving an LLM insight.
 *
 * Typically called automatically when the LLM generates an insight
 * during an active recording session.
 *
 * @property transcriptionRepository Repository for transcription data
 */
class SaveInsightUseCase(
    private val transcriptionRepository: TranscriptionRepository
) {
    /**
     * Save an LLM insight to persistent storage.
     *
     * @param insight The insight to save
     * @return Result indicating success or failure
     */
    suspend operator fun invoke(insight: LlmInsight): Result<Unit> {
        return transcriptionRepository.saveInsight(insight)
    }
}
