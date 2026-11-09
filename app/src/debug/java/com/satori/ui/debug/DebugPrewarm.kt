package com.satori.ui.debug

import android.os.SystemClock
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.satori.data.config.SpeechModels
import com.satori.data.datasource.ModelDownloadManager
import com.satori.data.monitor.LoadPace
import com.satori.data.monitor.ModelLoadPacing
import com.satori.data.monitor.ProbeRead
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.model.SpeechModelId
import com.satori.domain.repository.FinishingModelRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.AiModelPaths
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

/**
 * Debug builds only: the probe of a load's pace from adb, with no model loaded
 * (`DebugToolsActivity --es action prewarm --es model e2b|1b|parakeet|cohere|ml --es rate <MB/s>`),
 * the first run of a memory session: it shows whether the phone takes a model's files in at that
 * rate without direct reclaim, which decides the rate of the paced loads. The model's files lose
 * their cached pages first, so the read comes from flash, as a cold load's would; then they are
 * read in as a paced load's pre-read ([ModelLoadPacing.probe], whose line gives the MB, the ms, the
 * waits and the lowest MemFree). The probe's own line says how much of the files was read, which a
 * cold probe that passes reads all of. `~/satori-audit/tools/memtest.sh probe=<rate> <model>` runs it.
 */
@HiltViewModel
class DebugPrewarmViewModel @Inject constructor(
    private val pacing: ModelLoadPacing,
    private val inferenceEngine: InferenceEngine,
    private val finishingModels: FinishingModelRepository,
    private val modelPaths: AiModelPaths,
    private val settingsRepository: SettingsRepository,
    private val downloads: ModelDownloadManager,
    private val aiGate: AiWorkGate,
    private val finishingQueue: FinishingQueue,
    private val activeRecorder: ActiveRecorder,
    private val processingService: LlmProcessingServiceController
) : DebugRunViewModel() {

    /**
     * The probe of [modelId] at [rate] MB/s, run as the memtest is ([DebugRuns]): a fold or a turn
     * gets the run under way back, and an activity restored after its process ended ([firstLaunch]
     * false) starts none. Null when none runs. Main thread only.
     */
    internal fun start(modelId: String, rate: String, firstLaunch: Boolean): DebugRun? = once.start(firstLaunch) { started -> run(modelId, rate, started) }

    private suspend fun run(modelId: String, rateText: String, started: () -> Unit) {
        val model = DebugPrewarm.Model.of(modelId)
        if (model == null) {
            Log.w(TAG, "Prewarm: no model $modelId, only e2b, 1b, parakeet, cohere or ml")
            return
        }
        val rate = rateText.trim().toIntOrNull()?.takeIf { it in LoadPace.MIN_MB_PER_S..LoadPace.MAX_MB_PER_S }
        if (rate == null) {
            Log.w(TAG, "Prewarm ${model.id}: no rate of ${LoadPace.MIN_MB_PER_S}-${LoadPace.MAX_MB_PER_S} MB/s given (rate=$rateText)")
            return
        }
        val prewarm = DebugPrewarm(activeRecorder.current, aiGate, finishingQueue.busy, processingService)
        val report = try {
            prewarm.run(
                model,
                files = { filesOf(model) },
                drop = { file -> inferenceEngine.dropCachedPages(file.path) },
                read = { files -> pacing.probe(model.id, files, rate) },
                onStarted = started
            )
        } catch (e: CancellationException) {
            Log.w(TAG, "Prewarm ${model.id} cancelled: the screen was closed")
            throw e
        }
        val line = report.line(model)
        if (report.outcome is DebugPrewarm.Outcome.Done) Log.i(TAG, line) else Log.w(TAG, line)
    }

    // The files a load of [model] reads; null when it isn't on the phone.
    private suspend fun filesOf(model: DebugPrewarm.Model): List<File>? = when (model) {
        DebugPrewarm.Model.E2B -> finishingModels.enabledModelPath()?.let { listOf(File(it)) }
        DebugPrewarm.Model.LIVE -> modelPaths.livePath(settingsRepository.getSettings().first())?.let { listOf(File(it)) }
        DebugPrewarm.Model.PARAKEET -> speechFiles(SpeechModelId.PARAKEET_V3)
        DebugPrewarm.Model.COHERE -> speechFiles(SpeechModelId.COHERE_TRANSCRIBE)
        DebugPrewarm.Model.MALAYALAM -> speechFiles(SpeechModelId.INDICCONFORMER_ML)
    }

    private suspend fun speechFiles(id: SpeechModelId): List<File>? = withContext(Dispatchers.IO) {
        downloads.getSttModelPath(id)?.let { dir -> SpeechModels.of(id).specs.map { File(dir, it.name) } }
    }

    private companion object {
        const val TAG = "DebugTools"
    }
}

/**
 * One probe: under the AI's foreground service and the AI work gate, as the memtest runs (so the
 * process is in the same scheduling group, and no model loads beside it), the model's files lose
 * their cached pages and are read in by [read]. It keeps the memtest's rules: it starts only while
 * no recorder is busy, no recording waits to be finished and nobody holds the AI, and a recording
 * that starts meanwhile wins (the read stops at its next piece).
 *
 * @param clock Milliseconds that never jump
 * @param serviceWaitMs How long it waits for the service to be in the foreground
 */
internal class DebugPrewarm(
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val gate: AiWorkGate,
    private val finishingBusy: StateFlow<Boolean>,
    private val processing: LlmProcessingServiceController,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val serviceWaitMs: Long = DebugMemtest.SERVICE_WAIT_MS
) {

    /** The model whose files a probe reads, by the name memtest.sh gives. */
    enum class Model(val id: String, val missing: String) {
        E2B("e2b", "Gemma 4 E2B is switched off or not on the phone"),
        LIVE("1b", "no live model on the phone"),
        PARAKEET("parakeet", "Parakeet v3 is not on the phone"),
        COHERE("cohere", "Cohere Transcribe is not on the phone"),
        MALAYALAM("ml", "the Malayalam model is not on the phone");

        companion object {
            fun of(id: String): Model? = entries.firstOrNull { it.id == id }
        }
    }

    /** How a probe ended. */
    sealed interface Outcome {
        /** It didn't start, for the reason given. */
        data class Refused(val why: String) : Outcome

        /** The model's files aren't on the phone. */
        data object NoFiles : Outcome

        /** A recording started, so it stopped. */
        data object RecorderStarted : Outcome

        /** Looking the files up, dropping their pages or reading them failed with a [kind] of exception. */
        data class Failed(val kind: String) : Outcome

        /**
         * [files] files were read in, in [ms], the cached pages of [dropped] of them dropped first:
         * [read] says how much of them wasn't cached and was read.
         */
        data class Done(val files: Int, val dropped: Int, val ms: Long, val read: ProbeRead) : Outcome

        /** As [Done], but the read stopped after waiting 10 s in all for memory ([read] says how far it came). */
        data class GaveUp(val files: Int, val dropped: Int, val ms: Long, val read: ProbeRead) : Outcome

        fun line(model: Model): String = when (this) {
            is Refused -> "Prewarm ${model.id} refused: $why"
            NoFiles -> "Prewarm ${model.id} not run: ${model.missing}"
            RecorderStarted -> "Prewarm ${model.id} cancelled: a recording started"
            is Failed -> "Prewarm ${model.id} failed: $kind"
            is Done -> "Prewarm ${model.id} done in $ms ms: ${mb(read)}; cached pages dropped first for $dropped of $files files"
            is GaveUp -> "Prewarm ${model.id} gave up after $ms ms: MemFree under ${read.floorMb} MB for 10 s; ${mb(read)}; " +
                "cached pages dropped first for $dropped of $files files"
        }

        private fun mb(read: ProbeRead) = "${read.newBytes / BYTES_PER_MB} MB of ${read.totalBytes / BYTES_PER_MB} MB read"
    }

    /** A probe's [outcome], and whether the service was in the foreground for it (null when it never came to that). */
    data class Report(val outcome: Outcome, val serviceInForeground: Boolean? = null) {
        fun line(model: Model): String = outcome.line(model) + when (serviceInForeground) {
            null -> ""
            true -> "; AI service in the foreground"
            false -> "; AI service not in the foreground"
        }
    }

    /**
     * Probes [model]: [files] are the ones a load of it reads (null when they aren't on the phone),
     * [drop] drops one file's cached pages (true when they went), [read] reads them in at the pace;
     * [onStarted] once it is past its rules and holds the AI's service.
     */
    suspend fun run(
        model: Model,
        files: suspend () -> List<File>?,
        drop: suspend (File) -> Boolean,
        read: suspend (List<File>) -> ProbeRead,
        onStarted: () -> Unit = {}
    ): Report {
        if (recorder.value.busy()) return Report(Outcome.Refused("a recorder is busy"))
        if (finishingBusy.value || !gate.tryEnter(HOLDER)) return Report(Outcome.Refused("the AI is busy"))
        var inForeground: Boolean? = null
        try {
            val accepted = processing.startProcessing(HOLDER)
            onStarted()
            return coroutineScope {
                val started = async { recorder.first { it.busy() } }
                val done = async {
                    inForeground = accepted && withTimeoutOrNull(serviceWaitMs) { processing.protected.first { it } } != null
                    probe(files, drop, read)
                }
                val outcome = select<Outcome> {
                    done.onAwait { it }
                    started.onAwait { Outcome.RecorderStarted }
                }.also {
                    started.cancel()
                    done.cancel()
                }
                Report(outcome, inForeground)
            }
        } finally {
            withContext(NonCancellable) {
                processing.stopProcessing(HOLDER)
                gate.exit(HOLDER)
            }
        }
    }

    private suspend fun probe(files: suspend () -> List<File>?, drop: suspend (File) -> Boolean, read: suspend (List<File>) -> ProbeRead): Outcome =
        try {
            val list = files()
            if (list == null) {
                Outcome.NoFiles
            } else {
                var dropped = 0
                for (file in list) if (drop(file)) dropped++
                val from = clock()
                val done = read(list)
                val ms = clock() - from
                if (done.gaveUp) Outcome.GaveUp(list.size, dropped, ms, done) else Outcome.Done(list.size, dropped, ms, done)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }

    private fun ActiveRecorder.Snapshot?.busy(): Boolean = this != null && activity != ActiveRecorder.Activity.IDLE

    companion object {
        /** The AI work gate's and the AI service's holder of a probe. */
        const val HOLDER = "prewarm"

        private const val BYTES_PER_MB = 1_048_576L
    }
}
