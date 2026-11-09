package com.satori.presentation.main

import com.satori.domain.usecase.finish.BetterTranscript
import com.satori.domain.usecase.finish.BetterTranscriptRun
import com.satori.domain.usecase.finish.BetterTranscriptStep
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The better transcript after Stop ([step]) as the background finishing runs it: never beside a
 * live capture of another recording. A speech model of that size takes the memory and the
 * processor the live one needs, and Transcribe again on the recording's page can make the better
 * transcript later.
 *
 * A capture that is live as the step would start keeps the model from running: the step is asked
 * as for a recorder that owns the models (mayChangeModel false), so it reads
 * [BetterTranscript.Skipped] for [BetterTranscript.Reason.NewerRecorder] where the model would
 * have run, and still says [BetterTranscript.Replaced] for a transcript the set model made
 * already, whose notes are then rewritten as after a fresh one. One that starts while the step
 * runs stops the model at the stretch of speech it is on ([BetterTranscriptRun.skip]): the live
 * transcript stays, and the result reads the same. A new transcript that is complete by then is
 * stored all the same, since Transcribe again stores it whole once it exists, and the result says
 * so. The capture of the recording itself, whose recorder may still be saving as its finishing
 * begins, doesn't count. A cancel of the finishing is passed on. The same holds for the Malayalam
 * model's transcript of a recording in Malayalam, whose live transcript is that model's too: it
 * never runs beside the live model of another recording.
 *
 * @property step The step that makes the better transcript
 * @property recorder What the open recorder does ([ActiveRecorder.current])
 */
class RecorderAwareBetterTranscript(
    private val step: BetterTranscriptStep,
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>
) : BetterTranscriptStep {

    override suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit
    ): BetterTranscript = run(sessionId, mayChangeModel, onStart, onProgress, mayLackSpeech = false)

    /** As [run], for a recording whose live transcript may lack speech, which [step] is told of. */
    override suspend fun run(
        sessionId: String,
        mayChangeModel: Boolean,
        onStart: (BetterTranscriptRun) -> Unit,
        onProgress: (Float) -> Unit,
        mayLackSpeech: Boolean
    ): BetterTranscript {
        if (recorder.value.isLiveCapture(besides = sessionId)) {
            return step.run(sessionId, mayChangeModel = false, onStart, onProgress, mayLackSpeech)
        }
        return coroutineScope {
            // Each side looks at the other's mark after setting its own, so a capture that starts
            // as the model is handed out still stops it.
            val newer = AtomicBoolean(false)
            val running = AtomicReference<BetterTranscriptRun?>(null)
            val watch = launch {
                recorder.first { it.isLiveCapture(besides = sessionId) }
                newer.set(true)
                running.get()?.skip()
            }
            try {
                val result = step.run(
                    sessionId,
                    mayChangeModel,
                    onStart = { run ->
                        running.set(run)
                        if (newer.get()) run.skip()
                        onStart(run)
                    },
                    onProgress = onProgress,
                    mayLackSpeech = mayLackSpeech
                )
                if (newer.get() && result is BetterTranscript.Skipped && result.reason == BetterTranscript.Reason.SkippedByOwner) {
                    BetterTranscript.Skipped(result.model, BetterTranscript.Reason.NewerRecorder)
                } else {
                    result
                }
            } finally {
                watch.cancel()
            }
        }
    }
}
