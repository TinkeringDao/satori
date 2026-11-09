package com.satori.domain.usecase.transcription

import com.satori.domain.language.MalayalamText
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase

/**
 * Use case for saving a transcription segment.
 *
 * Typically called automatically when STT produces a complete segment
 * during an active recording session. A complete segment's Malayalam is stored as the rules of
 * its script have it ([MalayalamText]), as the recorder shows it from the database.
 *
 * A segment with no word in it, not a letter or a digit
 * ([DiscardSilentRecordingUseCase.holdsSpeech]), is never stored: no empty line, nor one of only
 * spaces or signs. The clean-up leaves out a sign that no letter carries, so a line of such signs
 * alone comes out empty, as the start buzz of a Malayalam recording did in 0.7.0.
 *
 * @property transcriptionRepository Repository for transcription data
 */
class SaveSegmentUseCase(
    private val transcriptionRepository: TranscriptionRepository
) {
    /**
     * Save a transcription segment to persistent storage.
     *
     * @param segment The segment to save
     * @return Result indicating success or failure; a segment with no word in it is a success
     *   that stores nothing, as there is nothing of it to keep
     */
    suspend operator fun invoke(segment: TranscriptionSegment): Result<Unit> {
        val stored = if (segment.isComplete) segment.copy(text = MalayalamText.clean(segment.text)) else segment
        if (!DiscardSilentRecordingUseCase.holdsSpeech(stored.text)) return Result.success(Unit)
        return transcriptionRepository.saveSegment(stored)
    }
}
