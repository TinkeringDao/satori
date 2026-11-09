package com.satori.domain.usecase.transcription

import com.satori.domain.finish.FinishingQueue
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.usecase.todo.AnswerLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Use case for deleting a transcription session.
 *
 * Deletes the session and all associated segments and insights (CASCADE delete).
 * This operation is irreversible.
 *
 * A recording that is still being finished in the background is taken out of the finishing queue
 * first, and a step under way is ended before the recording goes: otherwise that step could store
 * its notes, to-dos or mind-map links after the delete, for a recording that is gone. Ending the
 * step can take until a model load ends (5-15 s), so once begun the delete runs to the end also
 * when its caller is cancelled meanwhile (the owner leaves the page): its finishing is gone by
 * then, and a recording left behind would never be finished. A delete that fails gives a
 * recording that was being finished back to the queue, which finishes it from the start. Once the
 * recording is gone, so are the raw to-do answers a debug build kept of it ([AnswerLog.forget]),
 * and the system prompts' states the AI engine keeps on disk ([LlmRepository.forgetPromptCache]):
 * the prompts hold nothing of a recording, so this is the second safeguard. That clear is best
 * effort and never waits: the delete returns as soon as the recording is gone, and the engine
 * clears its files between two of its calls, so a step under way for another recording goes on.
 *
 * @property transcriptionRepository Repository for transcription data
 * @property finishingQueue The recordings being finished after Stop
 * @property llmRepository The AI engine, whose prompt cache goes with a deleted recording
 * @property answerLog Where the to-do model's raw answers are kept, if anywhere
 */
class DeleteSessionUseCase(
    private val transcriptionRepository: TranscriptionRepository,
    private val finishingQueue: FinishingQueue,
    private val llmRepository: LlmRepository,
    private val answerLog: AnswerLog = AnswerLog.NONE
) {
    /**
     * Delete a session and all its data.
     *
     * @param sessionId The session ID to delete
     * @return Result indicating success or failure
     */
    suspend operator fun invoke(sessionId: String): Result<Unit> = withContext(NonCancellable) {
        val wasFinishing = finishingQueue.statuses.value.any { it.sessionId == sessionId }
        finishingQueue.cancel(sessionId)
        transcriptionRepository.deleteSession(sessionId)
            .onSuccess {
                answerLog.forget(sessionId)
                forgetPromptCache()
            }
            .onFailure {
                // The flag counts only for a capture a process death cut off, which a job handed over again is not.
                if (wasFinishing) finishingQueue.handOff(sessionId, recognizerFailed = false)
            }
    }

    /** Best effort: the recording is gone already, and the delete is reported as it went. */
    private fun forgetPromptCache() {
        try {
            llmRepository.forgetPromptCache()
        } catch (e: Exception) {
            // Nothing to undo; the next delete clears the cache again.
        }
    }
}
