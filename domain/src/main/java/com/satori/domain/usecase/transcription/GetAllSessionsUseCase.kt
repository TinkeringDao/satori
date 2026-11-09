package com.satori.domain.usecase.transcription

import com.satori.domain.model.SessionListItem
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case for retrieving all transcription sessions for the recordings list.
 *
 * Sessions are ordered by creation time (most recent first) to show
 * the latest recordings at the top of the list. Empty attempts are included:
 * [RecordingVisibility] decides which of them the list shows.
 *
 * @property transcriptionRepository Repository for transcription data
 */
class GetAllSessionsUseCase(
    private val transcriptionRepository: TranscriptionRepository
) {
    /**
     * Get all transcription sessions.
     *
     * @return Flow emitting list of sessions ordered by creation time (descending)
     */
    operator fun invoke(): Flow<List<SessionListItem>> {
        return transcriptionRepository.getAllSessions()
    }
}
