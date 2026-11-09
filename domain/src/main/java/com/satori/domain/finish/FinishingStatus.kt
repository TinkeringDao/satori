package com.satori.domain.finish

import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.finish.FinishStep

/**
 * One recording being finished, as screens and the notification see it.
 *
 * @property sessionId The recording
 * @property phase Whether it waits, waits for a recording to end or for the AI model, or is being
 *   finished
 * @property step The step under way; set while [Phase.RUNNING]
 * @property position Its place among the waiting ones, 1-based, while [Phase.WAITING]; 0 otherwise
 * @property transcript How far the better transcript has come, while [step] is
 *   [FinishStep.Transcript]
 * @property noNotes The finishing found too little text in the recording for any notes, before
 *   it chose a model (FinishRecordingUseCase.TooLittleText.leavesNoNotes): the recording gets
 *   none, which home and its page say from then on, while the steps after the notes run, and
 *   while it waits for the AI model for them ([Phase.WAITS_FOR_MODEL])
 */
data class FinishingStatus(
    val sessionId: String,
    val phase: Phase,
    val step: FinishStep? = null,
    val position: Int = 0,
    val transcript: TranscriptProgress? = null,
    val noNotes: Boolean = false
) {
    enum class Phase {
        /** In the queue, behind the job that runs. */
        WAITING,

        /** Being finished. */
        RUNNING,

        /** Deferred until no capture is live: a Translate capture runs, or there is no room for the model. */
        WAITS_FOR_RECORDING,

        /**
         * Held until the AI model it needs is on the phone: the first run downloads it after the
         * first recording, and a recording saved before it arrives is finished once it has, not
         * finished without notes.
         */
        WAITS_FOR_MODEL
    }
}

/**
 * The better transcript after Stop while it is made.
 *
 * @property model The speech model that makes it
 * @property fraction How much of the audio it has read, 0..1; 0 while the model loads
 * @property skipping The owner skipped it: it stops at the stretch of speech it is on
 */
data class TranscriptProgress(val model: SpeechModelId, val fraction: Float = 0f, val skipping: Boolean = false)
