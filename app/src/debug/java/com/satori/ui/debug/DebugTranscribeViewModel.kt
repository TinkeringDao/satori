package com.satori.ui.debug

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.AudioUsage
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.stt.TranscribeAgainUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import javax.inject.Inject

/**
 * Debug builds only: Transcribe again from adb, so speech models can be compared on many recordings
 * without tapping through each one (`DebugToolsActivity --es action transcribe --es session <id>
 * --es model <SpeechModelId.id>`). It runs the same use case as the recording's page, under the
 * same rules and the same service ([DebugTranscription]): the AI model must not be loaded, as
 * after a finished recording, and a recording that starts meanwhile wins. Logs only ids, the
 * outcome and the time.
 */
@HiltViewModel
class DebugTranscribeViewModel @Inject constructor(
    private val transcribeAgain: TranscribeAgainUseCase,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val activeRecorder: ActiveRecorder,
    private val audioUsage: AudioUsage,
    private val processingService: LlmProcessingServiceController
) : ViewModel() {

    suspend fun run(sessionId: String, modelId: String) {
        val model = SpeechModelId.entries.firstOrNull { it.id == modelId }
        if (model == null) {
            Log.w(TAG, "Debug transcribe: no speech model $modelId")
            return
        }
        val start = SystemClock.elapsedRealtime()
        val run = DebugTranscription(activeRecorder.current, aiGate, finishingQueue.busy, audioUsage, processingService)
            .run(sessionId) { transcribeAgain(sessionId, model) }
        val ms = SystemClock.elapsedRealtime() - start
        when (run) {
            is DebugTranscription.Run.Refused -> Log.w(TAG, "Debug transcribe refused: session=$sessionId, ${run.why}")
            DebugTranscription.Run.RecorderStarted ->
                Log.w(TAG, "Debug transcribe cancelled: session=$sessionId model=${model.id} after $ms ms, a recording started; the transcript stays")
            is DebugTranscription.Run.Done -> run.value
                .onSuccess { Log.i(TAG, "Debug transcribe done: session=$sessionId model=${model.id} outcome=$it in $ms ms") }
                .onFailure { Log.w(TAG, "Debug transcribe failed: session=$sessionId model=${model.id} after $ms ms", it) }
        }
    }

    private companion object {
        const val TAG = "DebugTools"
    }
}

/**
 * Transcribe again from adb, under the rules of the recording's page (RecordingAudioViewModel). It
 * starts only while no recorder is busy, and while the AI is free and no recording waits to be
 * finished: a finishing step could otherwise replace the recording's lines under it, or load a
 * model beside its speech model. It holds the AI work gate until it ends, and the recording's
 * audio counts as in use meanwhile, so the rules for older audio leave it be. A recording that
 * starts meanwhile wins: the transcription is cancelled, and the transcript stays as it was.
 *
 * It holds the AI's processing service as the page's run does, under the same key
 * ([ProcessingHolders.transcribe]), so a run whose screen goes off stays in Android's foreground
 * scheduling group (cores 0-6 on the razr), where the page's run would be. Without the service,
 * the debug runs of 2026-10-12 went to the background group, on the three little cores, and took
 * 4 to 7 times as long as a run in front.
 */
internal class DebugTranscription(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val gate: AiWorkGate,
    private val finishingBusy: StateFlow<Boolean>,
    private val audioUsage: AudioUsage,
    private val processing: LlmProcessingServiceController
) {

    /** How a run ended. */
    sealed interface Run<out T> {
        /** It didn't start, for the reason given. */
        data class Refused(val why: String) : Run<Nothing>

        /** A recording started, so it was cancelled. */
        data object RecorderStarted : Run<Nothing>

        /** It ran to the end with [value]. */
        data class Done<T>(val value: T) : Run<T>
    }

    suspend fun <T> run(sessionId: String, work: suspend () -> T): Run<T> {
        if (recorder.value.busy()) return Run.Refused("a recorder is busy")
        if (finishingBusy.value || !gate.tryEnter(AiWorkGate.TRANSCRIBE)) return Run.Refused("the AI is busy")
        audioUsage.begin(sessionId)
        try {
            // Inside the try, so a refused start still ends below, as on the page.
            processing.startProcessing(ProcessingHolders.transcribe(sessionId))
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val done = async { work() }
                select<Run<T>> {
                    done.onAwait { Run.Done(it) }
                    started.onAwait { Run.RecorderStarted }
                }.also {
                    started.cancel()
                    done.cancel()
                }
            }
        } finally {
            audioUsage.end(sessionId)
            processing.stopProcessing(ProcessingHolders.transcribe(sessionId))
            gate.exit(AiWorkGate.TRANSCRIBE)
        }
    }

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE
}
