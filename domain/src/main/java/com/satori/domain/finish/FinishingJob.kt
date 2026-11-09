package com.satori.domain.finish

import com.satori.domain.usecase.finish.FinishStep

/**
 * Where a recording's finishing is: its capture still runs, it waits in the queue, or the
 * finisher works on it. The row is written from the moment its capture starts, so a capture cut
 * off by a crash, an install or the low-memory killer is still finished.
 */
enum class FinishingJobState {
    /** The microphone is live; the job is not to be started yet. */
    CAPTURING,

    /** The last words are saved; the finisher takes the job in queue order. */
    QUEUED,

    /** The finisher works on it, one step after the other. */
    RUNNING
}

/**
 * One recording that is to be finished (see [FinishingJobRepository]). It holds no text: the
 * steps read everything from the recording itself, so each of them can be repeated after a
 * process death.
 *
 * @property sessionId The recording
 * @property state Where it is
 * @property nextStep The step that began last; null before the first
 * @property attempts How often [nextStep] began without the job moving on; a step that began
 *   twice is skipped on the next claim, so a step that kills the process can't do so for ever
 * @property owner The token of the process that wrote [FinishingJobState.CAPTURING] or
 *   [FinishingJobState.RUNNING]: a row of another owner in one of these states belongs to a
 *   process that is gone
 * @property recognizerFailed The live speech recognition failed during the capture
 * @property recovered Its capture ended without a hand-off: the process died while it recorded
 * @property waitsForRecording It was deferred until no capture is live
 * @property queuedAt When it was queued (epoch ms), its place in the queue; null while capturing
 * @property createdAt When the row was written (epoch ms)
 * @property redo It makes a finished recording's AI steps again from its Reading step (0.8.0, design
 *   R9): the backfill of readings queued it for a Malayalam recording whose lines were never read,
 *   and its steps replace what the AI made of them as they were said, but what the owner changed
 *   (FinishRecordingUseCase.Request.redo)
 */
data class FinishingJob(
    val sessionId: String,
    val state: FinishingJobState,
    val nextStep: FinishStep?,
    val attempts: Int,
    val owner: String,
    val recognizerFailed: Boolean,
    val recovered: Boolean,
    val waitsForRecording: Boolean,
    val queuedAt: Long?,
    val createdAt: Long,
    val redo: Boolean = false
)
