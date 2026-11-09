package com.satori.domain.usecase.transcription

import com.satori.domain.repository.TranscriptionRepository

/**
 * Use case for correcting a note by hand: its title, text and action items.
 */
class UpdateInsightContentUseCase(
    private val repository: TranscriptionRepository
) {
    /**
     * Save the title, content text and tasks JSON of an insight together, the owner's edit
     * ([TranscriptionRepository.saveEditedNote]): afterwards the insight has all three as given, and
     * if saving fails it has all three as they were. A note that notes made again replaced while it
     * was edited (design R9) comes back as a note of the recording of its own with what he wrote.
     *
     * @param sessionId The recording the note is of.
     * @param title The new title; null or blank clears it.
     * @param content The new content text.
     * @param tasks The new tasks JSON (an array of strings), or null for none.
     */
    suspend operator fun invoke(sessionId: String, insightId: String, title: String?, content: String, tasks: String?): Result<Unit> =
        repository.saveEditedNote(sessionId, insightId, title, content, tasks)
}
