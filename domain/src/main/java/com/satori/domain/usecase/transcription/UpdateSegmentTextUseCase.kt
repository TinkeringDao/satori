package com.satori.domain.usecase.transcription

import com.satori.domain.repository.TranscriptionRepository

/**
 * Use case for updating the text of an existing transcription segment.
 *
 * A blank text is refused and the line stays as it was: a line is never stored empty or with only
 * spaces. The edit sheet's Save is off for one, so this only holds that for every other caller.
 */
class UpdateSegmentTextUseCase(
    private val repository: TranscriptionRepository
) {
    suspend operator fun invoke(segmentId: String, newText: String): Result<Unit> =
        if (newText.isBlank()) {
            Result.failure(IllegalArgumentException("A line can't be stored without text"))
        } else {
            repository.updateSegmentText(segmentId, newText)
        }
}
