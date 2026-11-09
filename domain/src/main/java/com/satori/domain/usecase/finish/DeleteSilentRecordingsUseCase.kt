package com.satori.domain.usecase.finish

import com.satori.domain.model.TranscriptionSession
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase.Companion.holdsSpeech
import kotlinx.coroutines.flow.first

/**
 * Deletes the recordings in which nothing was said that versions before
 * [DiscardSilentRecordingUseCase] kept; run once. Only those that hold nothing at all go: no
 * transcript line with a letter or digit ([holdsSpeech]), no note, no to-do, and no audio kept of
 * them. A silent recording with audio stays, as the speech model of the time may have missed
 * speech in it that Transcribe again can find; an audio file counts as soon as it is there
 * ([RecordingAudioRepository.hasAudio]), so one that can't be read at the time is never deleted.
 *
 * Only recordings that ended more than [MIN_AGE_MS] ago are looked at, so the one being made or
 * finished is never touched: a recorder saves the length as it records, and lines and notes stamp
 * the recording as they are saved.
 *
 * @property transcriptionRepository Where the recordings are read and deleted
 * @property todoRepository The to-dos, which keep a recording they came from
 * @property audioRepository The audio kept of recordings
 * @property now The wall clock, in milliseconds since the epoch
 */
class DeleteSilentRecordingsUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val todoRepository: TodoRepository,
    private val audioRepository: RecordingAudioRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    /**
     * @return How many recordings were deleted
     * @throws Exception if the recordings can't be read or one can't be deleted; those deleted
     *   before stay deleted, and the next run looks at the rest again
     */
    suspend operator fun invoke(): Int {
        val endedBefore = now() - MIN_AGE_MS
        var deleted = 0
        for (item in transcriptionRepository.getAllSessions().first()) {
            val session = item.session
            // The list row answers the cheapest questions; each one after it costs a read.
            if (item.hasNotes || lastChange(session) > endedBefore) continue
            if (transcriptionRepository.getSegmentsBySession(session.id).first().any { holdsSpeech(it.text) }) continue
            if (todoRepository.observeForSession(session.id).first().isNotEmpty()) continue
            if (audioRepository.hasAudio(session.id)) continue
            transcriptionRepository.deleteSession(session.id).getOrThrow()
            deleted++
        }
        return deleted
    }

    /** When [session] last changed: as its capture ended, or as a line or note was saved later. */
    private fun lastChange(session: TranscriptionSession): Long =
        maxOf(session.createdAt + session.durationMs, session.lastModifiedAt)

    companion object {
        /** How long ago a recording must have ended to be deleted. */
        const val MIN_AGE_MS = 60 * 60 * 1000L
    }
}
