package com.satori.domain.usecase.stt

import com.satori.domain.repository.SttRepository

/**
 * Use case to stop STT streaming and release resources.
 *
 * @property sttRepository Repository for STT operations
 */
class StopSttStreamingUseCase(
    private val sttRepository: SttRepository
) {
    /**
     * Stop STT streaming.
     */
    suspend operator fun invoke() {
        sttRepository.stopStreaming()
    }
}
