package com.satori.presentation.main

import android.util.Log
import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.finish.FinishingJobState
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.finish.FinishingWake
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.finish.TranscriptProgress
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.model.AppSettings
import com.satori.domain.model.CaptureState
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.monitor.DeviceMemory
import com.satori.domain.reading.ReaderAvailability
import com.satori.domain.reading.ReaderStatus
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.finish.BetterTranscript
import com.satori.domain.usecase.finish.BetterTranscriptRun
import com.satori.domain.usecase.finish.DiscardSilentRecordingUseCase
import com.satori.domain.usecase.finish.FinishRecordingUseCase
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.finish.ReadingRun
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.stt.SpeechModelChoice
import com.satori.domain.usecase.transcription.UpdateSessionDurationUseCase
import com.satori.presentation.ai.AiWorkGate
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishes recordings in the background after Stop, one at a time, while the owner may already
 * record the next one: the app's [FinishingQueue].
 *
 * A recording's job is stored from the moment its capture starts ([captureStarted]), queued at the
 * hand-off ([handOff]) and deleted once the recording is finished, so a capture cut off by a
 * crash, an install or the low-memory killer is still finished, by the next process ([resume]).
 * One worker takes the jobs in queue order and runs [FinishRecordingUseCase] for each: the better
 * transcript, the joined lines, the notes, the name, the to-dos and the map. It holds the AI work
 * gate ([AiWorkGate.FINISHER]) across consecutive jobs, so Gemma 4 E2B stays loaded between them,
 * and once the queue is empty it frees the model (cleanup, then forgetModel, so no later load
 * brings E2B back) and lets go of the gate.
 *
 * Each step's start is stored with the job ([FinishingJobRepository.beginStep]); a step that began
 * twice without the job moving on is skipped at the next claim, so a step that ends the process
 * can't do so for ever. Before each step the worker may wait. The job is at that step from the
 * start of the waits, also for the next process, but its start counts only once they are over:
 * only a step that ran can have ended the process. It waits:
 * - before a step that may load a model, for a new recording's speech model ([HAND_OFF_GRACE_MS]
 *   after the last hand-off or resume, then while another recorder starts or its capture opens,
 *   at most [CAPTURE_OPENING_WAIT_MS]), so its start isn't slowed by a model load beside it. The
 *   joining of lines loads none, nor does the better transcript without a speech model for it
 *   (none set for after Stop, and the recording not in Malayalam, which the Malayalam model
 *   transcribes again whatever is set), so then the steps up to the notes run right after Stop.
 *   A capture that takes longer
 *   to open is not waited for again by the steps after: each would lose the whole wait;
 * - while the phone is hot and a capture is live;
 * - for a keep-alive;
 * - with no capture live, as the first AI step loads Gemma 4 E2B: for the speech model of the
 *   recorder that closed to be freed, at most [SPEECH_MODEL_FREE_WAIT_MS], and for the memory E2B
 *   needs, the same as beside a capture (FinishRecordingUseCase's model step).
 * A Translate capture defers the job until it ends, as its live translation needs the AI model all
 * through; so does a capture beside which E2B doesn't fit
 * ([com.satori.domain.usecase.llm.LoadFinishingModelUseCase.E2B_MARGIN_BYTES] beyond its
 * file), and one that starts while the first AI step waits for the speech model to be freed or for
 * room for E2B, as nothing loads while it opens. The capture ends at the latest with its hand-off,
 * and the jobs it deferred go on in their place in the queue, ahead of its own job.
 *
 * The keep-alive: each queued job holds the AI's foreground service under its own key
 * ([ProcessingHolders.finishing]), released when its job is gone. If Android doesn't have the
 * service in the foreground [KEEP_ALIVE_WAIT_MS] after a start, the persisted wake job
 * ([FinishingWake]) is scheduled, and no step starts until one of the two keeps the process. The
 * queue never schedules the wake job while it runs under it: scheduling a job that runs stops it.
 *
 * When something fails: a job whose run fails (a read of its recording, or a write of its job,
 * throws) is run once more, and one that fails again, or whose end can't be stored, is left for
 * the next process. A step that throws fails alone and the next one runs (FinishRecordingUseCase),
 * also on an error of code that could not be loaded or set up, or of memory. An error that fails
 * the run is a failed run too, never the end of the worker, and a run that throws an error again
 * ends its job with what its steps made, as it would in every process (since 0.7.4: on 2026-10-19
 * an ExceptionInInitializerError of the to-dos step ended the worker, and the recording stayed in
 * "Finishing up"). A cancel is never a step that failed. A run whose model load the phone's
 * memory stopped
 * ([FinishRecordingUseCase.Outcome.LoadStopped]: MemFree under the pacing floor for 10 s as the
 * live model loaded) goes back to the queue with its step not counted, and the queue runs again
 * [MEMORY_PAUSE_MS] later, with the gate and the model let go of meanwhile; after
 * [MAX_MEMORY_STOPS] such stops in a process the job is left for the next one. Its row stays as it is, nothing holds the service for it, and screens don't
 * show it until then. A queue that can't be read at all lets go of every holder, and is taken up
 * at the next hand-off or start; what screens see of it is read again a moment after a read that
 * failed. A step that the wake job's end cuts short every time (it outlasts each run the wake job
 * gets) is skipped once that has happened [MAX_WAKE_JOB_CUTS] times in this process, as one that
 * began twice is; not the Reading step, whose every run stores what it read and goes on from the
 * next line.
 *
 * A recording saved while the AI model is on its way is not finished without notes: AI notes are
 * on, no live model is on the phone, and the first run downloads one
 * ([com.satori.domain.firstrun.FirstRunSnapshot.aiModelPending]; it does so after the first
 * recording). Its run goes up to its first AI step with something to do and stops there
 * ([FinishRecordingUseCase.Outcome.WaitsForModel]): the better transcript, the joined lines, and
 * whether there is too little text for notes come at once. The job then waits for the model in the
 * queue, out of the claims and holding nothing, and screens see it wait
 * ([FinishingStatus.Phase.WAITS_FOR_MODEL]); the jobs after it run up to their own first AI step
 * meanwhile. Once the model is in (the first run's plan no longer waits for it, or it is found on
 * the phone, looked for every [MODEL_RECHECK_MS]), or AI notes are switched off, the waiting jobs
 * take the service again and go on from that step, in their place in the queue. Rows that a process
 * left so are deferred ones to the next process, whose runs find that they wait for the model again.
 *
 * The reader (0.8.0), which reads a Malayalam recording's lines into English before its AI steps,
 * is waited for the same way while it is on its way ([ReaderAvailability], [ReaderStatus.ON_ITS_WAY]):
 * the run stops at its Reading step ([FinishRecordingUseCase.Outcome.WaitsForModel] for the reader).
 * Each job waits for what it needs, the AI model or the reader, and goes on once that wait is over,
 * whatever the other one's is: a job that waits for the reader is not let go while the AI model is
 * on the phone (design K3), nor one that waits for the AI model when the reader comes. Screens show
 * both as waiting for the AI model. A reader that isn't coming lets its jobs go, and their runs
 * finish without the AI steps (design D7). The Reading step never runs beside a capture: one that
 * starts stops the reader at once (design D14), from the capture-open path ([captureStarted]) and
 * from a watch on the recorder, as the reader's own watch on the capture state does.
 *
 * A finished Malayalam recording due to be made again over its readings is queued again by the
 * backfill of readings ([redo]; design R9, [ReadingBackfill]): its job starts at its Reading step and
 * runs as a hand-off's does, under the same rules, with the same grace before its first load for a
 * recording started right then; its AI steps are made again over the readings ([FinishingJob.redo]).
 * Nobody waits for it, so it comes after every other job in the queue, and it never runs beside a
 * capture: none of its steps begins beside one, and one that starts stops the step under way, which
 * runs again once the capture is over ([stopForCapture]). A run, this one or a hand-off's, that ends
 * with lines still never read leaves the recording due to be made again
 * ([TranscriptionRepository.noteRedoDue]) and counts against it, so the backfill stops trying it
 * ([ReadingBackfill.MAX_FAILURES]); one whose step this process skipped after it began twice counts
 * as all of them at once, as the reader went down with the process there twice. A run made again
 * that finishes leaves it done; one whose notes failed counts one failure.
 *
 * Each hand-off, and the finishing of a capture that was cut off and had words in it, tells the
 * first run that a recording is saved ([FirstRunCoordinator.recordingSaved]); a finishing that
 * leaves its recording with notes tells the owner ([NotesReadyCue]), but not one made again, which
 * he isn't waiting for.
 *
 * "A capture is live" means here what [liveCapture] says: a recorder starts, records or saves,
 * and its recording has not been handed off yet. Logs go to logcat under SatoriFinishing, with
 * ids, counts, ms and MB only.
 */
@Singleton
class RecordingFinisher internal constructor(
    private val finish: suspend (FinishRecordingUseCase.Request, FinishRecordingUseCase.Progress) -> FinishRecordingUseCase.Outcome,
    private val jobs: FinishingJobRepository,
    private val gate: AiWorkGate,
    private val controller: LlmProcessingServiceController,
    private val wake: FinishingWake,
    private val recorder: StateFlow<ActiveRecorder.Snapshot?>,
    private val stt: SttRepository,
    private val llm: LlmRepository,
    private val memory: DeviceMemory,
    private val settingsRepository: SettingsRepository,
    private val modelPaths: AiModelPaths,
    private val transcriptionRepository: TranscriptionRepository,
    private val audioRepository: RecordingAudioRepository,
    private val updateSessionDuration: UpdateSessionDurationUseCase,
    // Lazy: the first run's coordinator may itself depend on the queue; it is asked only once the
    // queue works.
    private val firstRun: Lazy<FirstRunCoordinator>,
    // Whether the reader is on the phone, on its way or not coming, for the jobs that need it.
    private val reader: ReaderAvailability,
    private val notesReady: NotesReadyCue,
    // When the queue looks again whether the AI model is on the phone, while jobs wait for it.
    private val modelRechecks: Flow<Unit>,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val log: FinisherLog,
    private val owner: String
) : FinishingQueue {

    @Inject constructor(
        finishRecording: FinishRecordingUseCase,
        jobs: FinishingJobRepository,
        gate: AiWorkGate,
        controller: LlmProcessingServiceController,
        wake: FinishingWake,
        activeRecorder: ActiveRecorder,
        stt: SttRepository,
        llm: LlmRepository,
        memory: DeviceMemory,
        settingsRepository: SettingsRepository,
        modelPaths: AiModelPaths,
        transcriptionRepository: TranscriptionRepository,
        audioRepository: RecordingAudioRepository,
        updateSessionDuration: UpdateSessionDurationUseCase,
        firstRun: Lazy<FirstRunCoordinator>,
        reader: ReaderAvailability,
        notesReady: NotesReadyCue
    ) : this(
        finish = finishRecording::invoke,
        jobs = jobs,
        gate = gate,
        controller = controller,
        wake = wake,
        recorder = activeRecorder.current,
        stt = stt,
        llm = llm,
        memory = memory,
        settingsRepository = settingsRepository,
        modelPaths = modelPaths,
        transcriptionRepository = transcriptionRepository,
        audioRepository = audioRepository,
        updateSessionDuration = updateSessionDuration,
        firstRun = firstRun,
        reader = reader,
        notesReady = notesReady,
        modelRechecks = flow {
            while (true) {
                delay(MODEL_RECHECK_MS)
                emit(Unit)
            }
        },
        now = System::currentTimeMillis,
        // No call of the queue throws, and nothing it does in the background may end the app.
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> FinisherLog.Logcat.warn("Finishing failed", e) }
        ),
        log = FinisherLog.Logcat,
        owner = UUID.randomUUID().toString()
    )

    /** Why the job that runs was stopped from outside its steps' work. */
    private enum class Stop {
        /** [cancel]: the job and its holder go. */
        CANCEL,

        /** No keep-alive: the job goes back to the queue, its step to run again. */
        KEEP_ALIVE,

        /** The job's row is gone (its recording was deleted). */
        GONE,

        /** A write of the job as a step began failed ([Running.failure]). */
        FAILED,

        /**
         * A capture started beside a recording made again ([FinishingJob.redo]), whose steps never
         * run beside one (design R9): the job waits for that capture to end, its step to run again.
         */
        CAPTURE
    }

    /** The job the worker runs, and what can stop it from outside. */
    private class Running(val job: FinishingJob, val work: Job, val startedAt: Long) {
        @Volatile var stop: Stop? = null
        @Volatile var failure: Exception? = null
        @Volatile var transcript: BetterTranscriptRun? = null

        /** The reader's reading of the Reading step under way, which a capture that starts stops at once (D14). */
        @Volatile var reading: ReadingRun? = null
        @Volatile var stepBeganAt: Long = startedAt

        /** The step under way, from the start of its waits. */
        @Volatile var step: FinishStep? = null

        /** The start of the step under way is counted: its waits are over, and its work runs. */
        @Volatile var counted: Boolean = false

        /**
         * The better transcript after Stop has a speech model, so the Transcript step may load
         * one: one is set for it, or the recording is stored in Malayalam, which the Malayalam
         * model transcribes again whatever is set ([SpeechModelChoice.afterStopModel]), or its live
         * recognizer left audio untranscribed, which the live model transcribes then
         * ([FinishingJob.recognizerFailed]). Without, it loads nothing and waits for no capture; set
         * from the job, the settings and the recording as the job's run starts ([betterTranscriptLoads]).
         */
        @Volatile var betterTranscriptSet: Boolean = true
    }

    /**
     * A capture on its way, as a step waits for it to open ([awaitCaptureOpened]): the recording of
     * its recorder, if any, and whether its microphone opens already (or the recorder only starts).
     */
    private data class Opening(val sessionId: String?, val microphone: Boolean)

    /**
     * What a job that waits holds out for: the [reader] (its Reading step's), or the AI model; and
     * whether it found too little text for notes ([FinishingStatus.noNotes]).
     */
    private data class Waits(val noNotes: Boolean, val reader: Boolean = false)

    /**
     * What screens see of the job that runs, beyond its row: the step, the better transcript, and
     * whether the recording gets no notes for want of text ([FinishingStatus.noNotes]).
     */
    private data class Live(
        val sessionId: String,
        val step: FinishStep? = null,
        val transcript: TranscriptProgress? = null,
        val noNotes: Boolean = false
    )

    /** How a job ended, for the worker. */
    private sealed interface JobEnd {
        /**
         * Its run ended with [outcome]; [noNotes] when it found too little text for notes,
         * [recovered] for a job whose capture was cut off, and [redo] for one made again over its
         * readings ([FinishingJob.redo]).
         */
        data class Done(
            val outcome: FinishRecordingUseCase.Outcome,
            val startedAt: Long,
            val noNotes: Boolean = false,
            val recovered: Boolean = false,
            val redo: Boolean = false
        ) : JobEnd
        data object Cancelled : JobEnd
        data object Gone : JobEnd
        data object KeepAliveLost : JobEnd

        /** Its run failed, [cause] thrown; null when it was cut short from inside. */
        data class Failed(val cause: Throwable?) : JobEnd
    }

    private val resumed = AtomicBoolean(false)
    private val resumedForeground = AtomicBoolean(false)

    // The take-up of the jobs a process that is gone left, while it runs. Until it is done they
    // still read as capturing or running, so the worker waits for it.
    @Volatile private var takeUp: Deferred<Unit>? = null

    // Restarts the worker; a kick while it works makes it look at the queue once more after.
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    // The sessions whose processing holder this queue took.
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The starts of captures being stored, by recording; a hand-off of the recording waits for its own.
    private val captureStarts = ConcurrentHashMap<String, Job>()

    // How often the run of each job failed in this process.
    private val failures = ConcurrentHashMap<String, Int>()

    // How often in this process the phone's memory stopped a model load of each job's run.
    private val memoryStops = ConcurrentHashMap<String, Int>()

    // Not before then does the queue run again: the phone was short of memory for a model load.
    @Volatile private var memoryPauseUntil: Long? = null

    // By recording, the step the wake job's end last cut short in this process, and how often in a
    // row it did.
    private val wakeJobCuts = ConcurrentHashMap<String, Pair<FinishStep, Int>>()

    // The recordings whose Reading step this process skipped after it began twice: the process
    // ended in it each time, which counts as every failure of the backfill at once.
    private val readingCrashed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // The jobs left for the next process: their rows stay running under this owner meanwhile.
    private val setAside = MutableStateFlow<Set<String>>(emptySet())

    // The jobs that wait for the AI model or the reader, by recording, each with what it waits for
    // and whether it found too little text for notes. Their rows are deferred, so no claim takes them.
    private val waitingForModel = MutableStateFlow<Map<String, Waits>>(emptyMap())

    private val underWakeJob = MutableStateFlow(false)
    private val wakeDone = AtomicReference<(() -> Unit)?>(null)
    private val live = MutableStateFlow<Live?>(null)

    @Volatile private var running: Running? = null
    @Volatile private var lastStartAt: Long? = null

    // The last hand-off, or the take-up of jobs: a recording may start right then (the quick key).
    @Volatile private var graceFrom: Long? = null
    @Volatile private var waitingForCapture = false

    // The capture on its way that a step waited for the longest, and went on without.
    @Volatile private var waitedOut: Opening? = null

    // The recording handed off last. Its recorder still reads saving for a moment after the
    // hand-off, but its capture is over ([liveCapture]); a new capture of it takes this back.
    @Volatile private var lastHandedOff: String? = null

    // The recording whose capture the deferred jobs wait for; null for jobs a process that is gone
    // deferred, which wait for the capture live now, if any. Read and written by the worker only.
    @Volatile private var deferredFor: String? = null

    // Read again after a failed read: what screens see, and the holders let go of, follow it.
    private val rows: SharedFlow<List<FinishingJob>> = jobs.observeAll()
        .retryWhen { e, attempt ->
            val again = minOf(QUEUE_READ_RETRY_MS shl attempt.coerceAtMost(QUEUE_READ_RETRY_DOUBLINGS).toInt(), QUEUE_READ_RETRY_MAX_MS)
            log.warn("Finishing queue could not be read; reading it again in $again ms", e)
            delay(again)
            true
        }
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    override val statuses: StateFlow<List<FinishingStatus>> =
        combine(rows, live, setAside, waitingForModel) { all, state, aside, waiting ->
            statusesOf(all.filter { it.sessionId !in aside }, state, waiting)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // A job that waits for the AI model doesn't count: nothing runs for it until the model is in,
    // which may take long without Wi-Fi, and the work that gives way to the queue (Transcribe
    // again, say) may run meanwhile.
    override val busy: StateFlow<Boolean> = statuses.map { all -> all.any { it.phase != FinishingStatus.Phase.WAITS_FOR_MODEL } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // Read as each step begins, so it has to be followed all the time.
    private val thermal: StateFlow<ThermalThrottle> = llm.thermalThrottleFlow
        .catch { e -> log.warn("Thermal state could not be read", e) }
        .stateIn(scope, SharingStarted.Eagerly, ThermalThrottle.Normal)

    private val keptAlive: Flow<Boolean> = combine(controller.protected, underWakeJob) { protected, wakeJob -> protected || wakeJob }

    init {
        scope.launch { kicks.consumeEach { drainSafely() } }
        scope.launch {
            // A job that waits for the AI model is no work of the AI's service, which doesn't run for it.
            statuses.map { all -> all.filter { it.phase != FinishingStatus.Phase.WAITS_FOR_MODEL } }
                .map { it.size to it.firstOrNull { status -> status.phase == FinishingStatus.Phase.RUNNING }?.step }
                .distinctUntilChanged()
                .collect { (recordings, step) -> controller.showFinishing(recordings, step) }
        }
        scope.launch { rows.collect { releaseHoldersOfGoneJobs(it) } }
        scope.launch { kickWhenModelMayBeIn() }
        // A recorder that starts (the quick key, say) stops the reader at once (D14), and the steps
        // of a recording made again.
        scope.launch { recorder.collect { capture -> stopBeside(capture) } }
    }

    /**
     * Stops what may not run beside [capture] when it is a live capture of another recording than
     * the job's (its recorder starts, records or saves): [stopForCapture].
     */
    private fun stopBeside(capture: ActiveRecorder.Snapshot?) {
        val run = running ?: return
        if (liveCapture(capture, besides = run.job.sessionId) != null) stopForCapture(run)
    }

    /**
     * A capture of another recording than [run]'s starts: the Reading step under way stops at once,
     * the reader released ([ReadingRun.stop]), and the step defers the job until that capture is
     * over (D14, O5). A job made again over its readings ([FinishingJob.redo]) stops whatever step
     * of it runs, as nobody waits for it and its steps never run beside a capture (design R9): its
     * work is cancelled ([Stop.CAPTURE]), what the step would have stored is not, and the job waits
     * for the capture to end, that step to run again. A step still in its waits is deferred by the
     * check that ends them ([beforeStep]).
     */
    private fun stopForCapture(run: Running) {
        run.reading?.stop()
        if (run.job.redo && run.counted && run.step != FinishStep.Reading && run.stop == null) {
            run.stop = Stop.CAPTURE
            run.work.cancel()
        }
    }

    // Runs to the end once begun, like cancel(): a capture whose row isn't written is not finished
    // after a crash, and the join of a job of the recording lasts as long as a model load. Stored
    // in the queue's own scope from its first suspension on, so a hand-off that waits for it on the
    // main thread (the recorder's onCleared, inside runBlocking) doesn't hold it up.
    override suspend fun captureStarted(sessionId: String) {
        // The capture opens: the reader of another recording stops at once (D14), before anything
        // here, and so does a job made again.
        running?.takeIf { it.job.sessionId != sessionId }?.let(::stopForCapture)
        // This capture is live until its own hand-off, also once its recorder saves.
        if (lastHandedOff == sessionId) lastHandedOff = null
        val store = scope.launch(start = CoroutineStart.UNDISPATCHED) { storeCaptureStart(sessionId) }
        captureStarts[sessionId] = store
        store.invokeOnCompletion { captureStarts.remove(sessionId, store) }
        withContext(NonCancellable) { store.join() }
    }

    private suspend fun storeCaptureStart(sessionId: String) {
        try {
            // A job of the recording that waits or runs is one of an earlier capture of it: this
            // capture's hand-off queues all of it again.
            val job = jobs.get(sessionId)
            if (job != null && job.state != FinishingJobState.CAPTURING) cancel(sessionId)
            jobs.captureStarted(sessionId, owner, now())
            takeBack(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not store the capture of $sessionId", e)
        }
    }

    // Runs to the end once begun: a job queued without its holder and its kick would wait for
    // the next hand-off. The start of the recording's capture, if it is still being stored, is
    // stored first: a recorder that closes as its capture starts hands off before then, and the
    // start's later write would take the queued job back to capturing. The hand-off ends the
    // capture for the queue, before its recorder reads idle: jobs deferred for it go on first.
    override suspend fun handOff(sessionId: String, recognizerFailed: Boolean) {
        withContext(NonCancellable) {
            captureStarts[sessionId]?.join()
            val queued = try {
                jobs.queue(sessionId, recognizerFailed, now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Finishing could not queue $sessionId", e)
                false
            }
            if (!queued) {
                log.warn("Finishing has no job for $sessionId: the recording is gone, or its job couldn't be written")
                release(sessionId)
                return@withContext
            }
            takeBack(sessionId)
            lastHandedOff = sessionId
            graceFrom = now()
            hold(sessionId)
            log.info("Finishing queued $sessionId")
            // The recorder handed it off kept: it has words in it, and the first of them ends the first run.
            tellRecordingSaved(sessionId)
            kick()
        }
    }

    // Runs to the end once begun: a job queued without its holder and its kick would wait for the
    // next hand-off. A recording may start right then (the app opens, the backfill queues), so the
    // first load waits the grace a hand-off gives.
    override suspend fun redo(sessionId: String): Boolean = withContext(NonCancellable) {
        val queued = try {
            jobs.queueRedo(sessionId, now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not queue $sessionId again", e)
            false
        }
        if (queued) {
            takeBack(sessionId)
            graceFrom = now()
            hold(sessionId)
            log.info("Finishing queued $sessionId again from its Reading step, to be made again over its readings")
            kick()
        }
        queued
    }

    // Runs to the end once begun, also when its caller is cancelled meanwhile: a job stopped but
    // left in its row would stay running until the next process.
    override suspend fun cancel(sessionId: String) {
        withContext(NonCancellable) {
            val run = running?.takeIf { it.job.sessionId == sessionId }
            if (run != null) {
                run.stop = Stop.CANCEL
                run.work.cancelAndJoin()
            }
            try {
                jobs.delete(sessionId)
                takeBack(sessionId)
            } catch (e: Exception) {
                log.warn("Finishing could not delete the job of $sessionId", e)
            }
            release(sessionId)
            if (run != null) log.info("Finishing of $sessionId cancelled")
        }
    }

    override fun skipBetterTranscript(sessionId: String) {
        val transcript = running?.takeIf { it.job.sessionId == sessionId }?.transcript ?: return
        updateLive(sessionId) { it.copy(transcript = it.transcript?.copy(skipping = true)) }
        transcript.skip()
    }

    /**
     * Takes up the jobs a process that is gone left, once per process. The jobs get their holders
     * at the first call that may start the service, also when an earlier one couldn't
     * (BOOT_COMPLETED, then the app opens), the one the wake job runs meanwhile too. A call that
     * may not start it takes no holder and schedules nothing itself: the worker schedules the wake
     * job if nothing keeps the process, but not while one runs, as FinishingWakeJob calls this as
     * it starts.
     */
    override fun resume(mayStartForeground: Boolean) {
        val first = resumed.compareAndSet(false, true)
        val holders = mayStartForeground && resumedForeground.compareAndSet(false, true)
        if (!first && !holders) return
        val takenUp = if (first) CompletableDeferred<Unit>().also { takeUp = it } else null
        scope.launch {
            try {
                if (takenUp != null) takeUpForeign() else takeUp?.await()
                if (holders) {
                    val taken = holdUnfinished()
                    if (takenUp == null && taken > 0) log.info("Finishing takes the AI's service for $taken unfinished")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Finishing could not take up the queue", e)
            } finally {
                takenUp?.complete(Unit)
            }
            kick()
        }
    }

    override fun runUnderWakeJob(done: () -> Unit) {
        wakeDone.set(done)
        underWakeJob.value = true
        log.info("Finishing runs under the wake job")
        // Nothing can run while the queue waits for a capture to end, and the job need not wait with it.
        if (waitingForCapture) finishWakeJob() else kick()
    }

    override fun wakeJobStopped() {
        wakeDone.set(null)
        underWakeJob.value = false
        if (controller.protected.value) return
        val run = running ?: return
        // A step still in its waits is stopped by the check that ends them.
        if (!run.counted) return
        run.step?.let { step -> countCut(run.job.sessionId, step) }
        run.stop = Stop.KEEP_ALIVE
        run.work.cancel()
    }

    /** The wake job's end cuts [step] of the job of [sessionId] short, once more in a row. */
    private fun countCut(sessionId: String, step: FinishStep) {
        wakeJobCuts.merge(sessionId, step to 1) { before, _ -> if (before.first == step) step to before.second + 1 else step to 1 }
    }

    private fun kick() {
        kicks.trySend(Unit)
    }

    /** Queues the jobs a process that is gone left capturing or running. */
    private suspend fun takeUpForeign() {
        jobs.recoverForeign(owner, now())
        val queued = queuedJobs()
        if (queued.isEmpty()) return
        log.info("Finishing resumed ${queued.size} (recovered ${queued.count { it.recovered }})")
        graceFrom = now()
    }

    /**
     * Takes the AI's service for each job that doesn't hold it yet and waits, or runs in this
     * process (one the wake job claimed before the app could start the service); returns how
     * many. Not for a job set aside for the next process, which nothing holds the service for.
     */
    private suspend fun holdUnfinished(): Int {
        val unfinished = jobs.observeAll().first().filter { job ->
            job.sessionId !in held && when (job.state) {
                FinishingJobState.QUEUED -> true
                FinishingJobState.RUNNING -> job.owner == owner && job.sessionId !in setAside.value
                FinishingJobState.CAPTURING -> false
            }
        }
        for (job in unfinished) {
            hold(job.sessionId)
            // The worker may have finished it since it was read, and let go of a holder it hadn't yet.
            if (jobs.get(job.sessionId) == null) release(job.sessionId)
        }
        return unfinished.size
    }

    private suspend fun queuedJobs(): List<FinishingJob> =
        jobs.observeAll().first().filter { it.state == FinishingJobState.QUEUED }

    private fun hold(sessionId: String) {
        held += sessionId
        lastStartAt = now()
        controller.startProcessing(ProcessingHolders.finishing(sessionId))
    }

    private fun release(sessionId: String) {
        if (held.remove(sessionId)) controller.stopProcessing(ProcessingHolders.finishing(sessionId))
    }

    /** The job of [sessionId] waits for the next process, which takes up its row as it is left. */
    private fun putAside(sessionId: String) {
        setAside.update { it + sessionId }
        waitingForModel.update { it - sessionId }
        release(sessionId)
    }

    /** The recording's job starts afresh: what this process kept against the old one is forgotten. */
    private fun takeBack(sessionId: String) {
        failures.remove(sessionId)
        memoryStops.remove(sessionId)
        wakeJobCuts.remove(sessionId)
        readingCrashed.remove(sessionId)
        setAside.update { it - sessionId }
        waitingForModel.update { it - sessionId }
    }

    /**
     * Tells the first run that the recording [sessionId], with words in it, is saved
     * ([FirstRunCoordinator.recordingSaved]); asked again for later recordings, which changes
     * nothing there. No call of the queue throws.
     */
    private fun tellRecordingSaved(sessionId: String) {
        try {
            firstRun.get().recordingSaved()
        } catch (e: Exception) {
            log.warn("Finishing could not tell the first run that $sessionId is saved", e)
        }
    }

    /**
     * Lets go of the holders of jobs that went without the queue: a recording deleted elsewhere
     * takes its job with it. A job [rows] lack is looked up once more, as they can be older than a
     * hand-off that has just queued it. A job that waited for the AI model and went so no longer
     * waits.
     */
    private suspend fun releaseHoldersOfGoneJobs(rows: List<FinishingJob>) {
        for (sessionId in held.toList()) {
            if (rows.none { it.sessionId == sessionId } && isGone(sessionId)) release(sessionId)
        }
        for (sessionId in waitingForModel.value.keys) {
            if (rows.none { it.sessionId == sessionId } && isGone(sessionId)) waitingForModel.update { it - sessionId }
        }
    }

    /** Whether the job of [sessionId] is gone; a read that fails says no. */
    private suspend fun isGone(sessionId: String): Boolean = try {
        jobs.get(sessionId) == null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun finishWakeJob() {
        underWakeJob.value = false
        wakeDone.getAndSet(null)?.invoke()
    }

    private fun updateLive(sessionId: String, change: (Live) -> Live) {
        live.update { state -> if (state?.sessionId == sessionId) change(state) else state }
    }

    private suspend fun drainSafely() {
        try {
            drain()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Nothing runs until the next hand-off or start, so nothing is kept alive meanwhile;
            // the jobs keep their rows. An error too (0.7.4): one that went on from here ended the
            // worker, whose kicks then went nowhere until the process ended.
            held.toList().forEach { release(it) }
            finishWakeJob()
            log.warn("Finishing stopped on an error; the queue is taken up again at the next hand-off or start", e)
        }
    }

    /**
     * Works the queue down: holds the gate while jobs run, lets go of it while the jobs left wait
     * for a capture to end or for a keep-alive, and frees the model whenever it lets go. Jobs that
     * wait for the AI model go on once it is in ([goOnWithWaitingIfModelIn]); while every job left
     * waits for it, the queue rests as an empty one does, and [kickWhenModelMayBeIn] wakes it.
     */
    private suspend fun drain() {
        takeUp?.await()
        var ranJobs = false
        while (true) {
            goOnWithWaitingIfModelIn()
            val queued = queuedJobs()
            if (queued.isEmpty() || queued.all { it.sessionId in waitingForModel.value }) break
            if (queued.all { it.waitsForRecording }) {
                // Deferred in this run, or by a process that is gone.
                awaitNoCapture()
                continue
            }
            awaitKeepAlive()
            awaitMemoryPause()
            gate.enter(AiWorkGate.FINISHER)
            try {
                runQueue()
            } finally {
                withContext(NonCancellable) { releaseEngine() }
            }
            ranJobs = true
        }
        if (ranJobs) log.info("Finishing queue empty, model released")
        finishWakeJob()
        wake.cancel()
    }

    /**
     * Runs the queued jobs one after the other, holding the gate, until none may run now. Jobs
     * deferred for a capture that is over go on first, so that each keeps its place in the queue,
     * also ahead of the job of the very capture it waited for.
     */
    private suspend fun runQueue() {
        while (true) {
            goOnWithDeferredIfOver()
            val job = jobs.claimNext(owner) ?: return
            val end = runJob(job)
            val goOn = try {
                settle(job.sessionId, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Each write that changes the row's state comes last, so the row is still running.
                log.warn("Finishing could not store how ${job.sessionId} ended; its job waits for the next start", e)
                putAside(job.sessionId)
                true
            }
            if (!goOn) return
        }
    }

    /**
     * Stores how the job of [sessionId] ended. A write that changes its state comes last, so one
     * that fails leaves the row running, which only the next process takes up.
     *
     * @return whether the next job may run now
     */
    private suspend fun settle(sessionId: String, end: JobEnd): Boolean {
        when (end) {
            is JobEnd.Done -> {
                val outcome = end.outcome
                // A capture cut off had no hand-off. It was kept unless nothing was said in it, but
                // also when its audio may hold words its lines lack: only one with words counts.
                if (end.recovered && outcome != FinishRecordingUseCase.Outcome.Discarded && hasWords(sessionId)) {
                    tellRecordingSaved(sessionId)
                }
                if (outcome is FinishRecordingUseCase.Outcome.WaitsForModel) {
                    return settleWaitsForModel(sessionId, outcome.step, Waits(end.noNotes, reader = outcome.reader))
                }
                if (outcome is FinishRecordingUseCase.Outcome.Deferred) {
                    // The step was not done, so its start doesn't count.
                    jobs.giveBackAttempt(sessionId)
                    jobs.defer(sessionId)
                    // Null when that capture was handed off meanwhile: the job goes on at the next claim.
                    deferredFor = liveCapture(besides = sessionId)?.sessionId
                    log.info("Finishing deferred $sessionId at ${outcome.step}: ${reasonOf(outcome, end.redo)}")
                    return false
                }
                if (outcome is FinishRecordingUseCase.Outcome.LoadStopped) return settleLoadStopped(sessionId, outcome.step)
                if (outcome is FinishRecordingUseCase.Outcome.ReaderHeldOff) return settleLoadStopped(sessionId, outcome.step, heldOff = outcome.why)
                if (outcome is FinishRecordingUseCase.Outcome.Unread) {
                    log.info("Finishing $sessionId without its AI steps: ${unreadWhy(outcome.why)}")
                    countBackfillFailure(sessionId, outcome.why)
                    // The backfill makes its AI steps once its lines can be read.
                    noteRedoDue(sessionId, due = true)
                }
                if (end.redo) settleRedo(sessionId, outcome)
                endJob(sessionId)
                log.info("Finishing done $sessionId in ${now() - end.startedAt} ms: $outcome")
                // The owner waits for the notes of a recording he made, not for those made again.
                if (outcome == FinishRecordingUseCase.Outcome.Finished && !end.redo) announceNotes(sessionId)
            }
            // cancel() deletes the job and lets go of its holder.
            JobEnd.Cancelled -> Unit
            JobEnd.Gone -> {
                wakeJobCuts.remove(sessionId)
                waitingForModel.update { it - sessionId }
                release(sessionId)
                log.info("Finishing of $sessionId stopped: its job is gone")
            }
            JobEnd.KeepAliveLost -> {
                jobs.giveBackAttempt(sessionId)
                jobs.requeue(sessionId)
                log.info("Finishing of $sessionId stopped for want of a keep-alive; its step runs again")
                return false
            }
            is JobEnd.Failed -> settleFailed(sessionId, end.cause)
        }
        return true
    }

    /**
     * A run of [sessionId], a hand-off's or one made again over its readings ([FinishingJob.redo]),
     * ended with lines still never read, for [why], which counts against the recording's backfill
     * ([ReadingBackfill.MAX_FAILURES]): the reader failed, or the run went on past the Reading step
     * (one failure each); but a step this process skipped after it began twice ended the process
     * each time, which counts as all of them, so the backfill doesn't try it again
     * ([readingCrashed]). A run that went on past it for another reason (a job of an older version
     * at its Notes step, say) is no crash. A reader that isn't on the phone is no failure of the
     * recording's. Never throws but a cancel; one that can't be stored is logged.
     */
    private suspend fun countBackfillFailure(sessionId: String, why: FinishRecordingUseCase.Outcome.Unread.Why) {
        val crashed = readingCrashed.remove(sessionId)
        val failures = when (why) {
            FinishRecordingUseCase.Outcome.Unread.Why.NO_READER -> return
            FinishRecordingUseCase.Outcome.Unread.Why.NOT_READ -> 1
            FinishRecordingUseCase.Outcome.Unread.Why.SKIPPED -> if (crashed) ReadingBackfill.MAX_FAILURES else 1
        }
        countBackfillFailures(sessionId, failures)
    }

    /** [failures] more count against the backfill of [sessionId]. Never throws but a cancel; one that can't be stored is logged. */
    private suspend fun countBackfillFailures(sessionId: String, failures: Int) {
        try {
            transcriptionRepository.noteBackfillFailed(sessionId, failures)
                .onFailure { log.warn("Finishing could not count the backfill of $sessionId that failed", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not count the backfill of $sessionId that failed", e)
        }
    }

    /**
     * How a run made again over the readings of [sessionId] ([FinishingJob.redo]) ended, for the
     * backfill: finished, its notes written or left deliberately, it is done and never made again;
     * notes that failed (the old ones stay) count one failure against it, and it is tried again.
     * Never throws but a cancel.
     */
    private suspend fun settleRedo(sessionId: String, outcome: FinishRecordingUseCase.Outcome) {
        when (outcome) {
            FinishRecordingUseCase.Outcome.Finished -> noteRedoDue(sessionId, due = false)
            FinishRecordingUseCase.Outcome.NotesFailed -> countBackfillFailures(sessionId, 1)
            else -> Unit
        }
    }

    /** Whether the backfill is to make the AI steps of [sessionId] again. Never throws but a cancel; one that can't be stored is logged. */
    private suspend fun noteRedoDue(sessionId: String, due: Boolean) {
        try {
            transcriptionRepository.noteRedoDue(sessionId, due)
                .onFailure { log.warn("Finishing could not store whether $sessionId is to be made again", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not store whether $sessionId is to be made again", e)
        }
    }

    /** The job of [sessionId] is over: its row goes, and what this process kept against it, and its holder. */
    private suspend fun endJob(sessionId: String) {
        jobs.delete(sessionId)
        failures.remove(sessionId)
        memoryStops.remove(sessionId)
        wakeJobCuts.remove(sessionId)
        readingCrashed.remove(sessionId)
        waitingForModel.update { it - sessionId }
        release(sessionId)
    }

    /**
     * The run of the job of [sessionId] failed, [cause] thrown. A failure is no process death: the
     * attempts rule stays out of it. The run goes once more, [MAX_RUN_FAILURES] runs in all in this
     * process. Then a job whose run threw an exception waits for the next process, where a read
     * of its recording or a write of its job may work. One whose run threw an error again ends, as
     * it would throw again in every process: code that could not be loaded or set up, memory, a
     * bug (0.7.4). Its row goes with what its steps made, and screens stop showing it finishing.
     */
    private suspend fun settleFailed(sessionId: String, cause: Throwable?) {
        when {
            failures.merge(sessionId, 1, Int::plus)!! < MAX_RUN_FAILURES -> {
                jobs.requeue(sessionId)
                log.info("Finishing of $sessionId runs once more")
            }
            cause is Error -> {
                endJob(sessionId)
                log.warn("Finishing of $sessionId failed again on ${cause.javaClass.simpleName}; its job ends with what it made")
            }
            else -> {
                putAside(sessionId)
                log.warn("Finishing of $sessionId failed again; its job waits for the next start")
            }
        }
    }

    /**
     * The model's load as [step] of the job of [sessionId] began didn't happen: the phone's memory
     * stopped it, or, with [heldOff], something the reader never loads beside was still in memory
     * ([FinishRecordingUseCase.Outcome.ReaderHeldOff]). The step didn't run, so its start doesn't
     * count. The job goes back to the queue, and the queue runs again after [MEMORY_PAUSE_MS], with
     * the model and the gate let go of meanwhile; the [MAX_MEMORY_STOPS]th such stop in this
     * process leaves it for the next one, as a run that failed twice is (its row stays running,
     * nothing holds the service for it).
     *
     * @return false: the queue lets go of the gate and the model now
     */
    private suspend fun settleLoadStopped(sessionId: String, step: FinishStep, heldOff: ReaderNotLoadedException.Why? = null): Boolean {
        jobs.giveBackAttempt(sessionId)
        val stops = memoryStops.merge(sessionId, 1, Int::plus)!!
        if (stops < MAX_MEMORY_STOPS) {
            jobs.requeue(sessionId)
            memoryPauseUntil = now() + MEMORY_PAUSE_MS
            val why = heldOff?.let { "the reader can't load beside ${besideWhat(it)} now" } ?: "the phone stayed short of memory as the model loaded"
            log.info("Finishing of $sessionId stopped at $step: $why; it runs again in ${MEMORY_PAUSE_MS / MS_PER_SECOND} s")
        } else {
            putAside(sessionId)
            val often = heldOff?.let { "$stops times, the reader held off by ${besideWhat(it)}" } ?: "for want of memory $stops times"
            log.warn("Finishing of $sessionId stopped at $step $often; its job waits for the next start")
        }
        return false
    }

    /** What kept the reader from loading, for the field log. */
    private fun besideWhat(why: ReaderNotLoadedException.Why): String = when (why) {
        ReaderNotLoadedException.Why.SPEECH_MODEL -> "the speech model"
        ReaderNotLoadedException.Why.AI_MODEL -> "the AI model"
        ReaderNotLoadedException.Why.CAPTURE -> "a capture"
        ReaderNotLoadedException.Why.NOT_ON_PHONE -> "files that aren't whole"
    }

    /** Why a recording got no AI steps (D7), for the field log. */
    private fun unreadWhy(why: FinishRecordingUseCase.Outcome.Unread.Why): String = when (why) {
        FinishRecordingUseCase.Outcome.Unread.Why.NO_READER -> "the reader isn't on the phone and isn't coming"
        FinishRecordingUseCase.Outcome.Unread.Why.NOT_READ -> "the reader failed"
        FinishRecordingUseCase.Outcome.Unread.Why.SKIPPED -> "its Reading step was skipped, and lines were never read"
    }

    /**
     * The run of the job of [sessionId] stopped at [step] for the AI model, or the reader
     * ([Waits.reader]), which is on its way: the job waits for it in the queue, deferred so that no
     * claim takes it, holding nothing, at [step] for this process and the next, its start not
     * counted. Screens see it wait for the AI model either way, and that it gets no notes
     * ([Waits.noNotes]) when it found too little text for them.
     *
     * @return true: the next job may run now, up to its own first AI step
     */
    private suspend fun settleWaitsForModel(sessionId: String, step: FinishStep, waits: Waits): Boolean {
        // First, so that screens see the wait while the row is still the running one.
        waitingForModel.update { it + (sessionId to waits) }
        if (!jobs.beginStep(sessionId, step)) {
            waitingForModel.update { it - sessionId }
            release(sessionId)
            log.info("Finishing of $sessionId stopped: its job is gone")
            return true
        }
        jobs.giveBackAttempt(sessionId)
        jobs.defer(sessionId)
        release(sessionId)
        log.info("Finishing of $sessionId waits for ${waitedFor(waits)} at $step")
        return true
    }

    /**
     * Lets the jobs that wait go on once their own wait is over: those that wait for the AI model
     * once [modelWaitOver], those that wait for the reader once [readerWaitOver]. They take the AI's
     * service again and may be claimed, in their place in the queue. When every waiting job goes on,
     * every deferred row is let go, so the jobs a capture deferred go on with them, and are deferred
     * again if that capture is still live; otherwise only theirs, and the others' rows stay deferred.
     */
    private suspend fun goOnWithWaitingIfModelIn() {
        val waiting = waitingForModel.value
        if (waiting.isEmpty()) return
        val modelIn = waiting.values.any { !it.reader } && modelWaitOver()
        val readerIn = waiting.values.any { it.reader } && readerWaitOver()
        val over = waiting.filterValues { if (it.reader) readerIn else modelIn }
        if (over.isEmpty()) return
        waitingForModel.update { it - over.keys }
        if (over.size == waiting.size) {
            jobs.releaseDeferred()
        } else {
            over.keys.forEach { jobs.releaseDeferred(it) }
        }
        for (sessionId in over.keys) {
            if (jobs.get(sessionId) != null) hold(sessionId)
        }
        for ((forReader, jobsOf) in over.values.groupBy { it.reader }) {
            log.info("Finishing goes on with ${jobsOf.size} that waited for ${waitedFor(Waits(noNotes = false, reader = forReader))}")
        }
    }

    /** What a waiting job waits for, for the field log. */
    private fun waitedFor(waits: Waits): String = if (waits.reader) "the reader" else "the AI model"

    /**
     * Whether a job that waits for the reader may go on: AI notes are off, or the reader is no
     * longer on its way ([ReaderStatus.ON_ITS_WAY]): on the phone, or not coming, when its run
     * finishes without the AI steps. A look that fails says no; the next one asks again.
     */
    private suspend fun readerWaitOver(): Boolean = try {
        !settingsRepository.getSettings().first().llmEnabled || reader.status() != ReaderStatus.ON_ITS_WAY
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Finishing could not tell whether the reader is in", e)
        false
    }

    /** Whether the wait of a job that waits is over, for the AI model or for the reader. */
    private suspend fun aWaitIsOver(): Boolean {
        val waiting = waitingForModel.value.values
        return (waiting.any { !it.reader } && modelWaitOver()) || (waiting.any { it.reader } && readerWaitOver())
    }

    /**
     * Whether a job that waits for the AI model may go on: AI notes are off, the model is on the
     * phone, or the first run no longer has it on its way ([waitsForModel]). A look that fails says
     * no; the next one, or the next hand-off, asks again.
     */
    private suspend fun modelWaitOver(): Boolean = try {
        val settings = settingsRepository.getSettings().first()
        !waitsForModel(settings, modelPaths.livePath(settings))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Finishing could not tell whether the AI model is in", e)
        false
    }

    /**
     * Whether a run waits for the AI model at its first AI step instead of going without the AI:
     * AI notes are on ([settings]), no live model is on the phone ([livePath]), and the first run
     * has one on its way. Read as each run starts: a recording saved before the first run planned
     * the model, or one whose download was dropped from the plan, is finished without notes, as
     * before.
     */
    private fun waitsForModel(settings: AppSettings, livePath: String?): Boolean =
        settings.llmEnabled && livePath == null && firstRun.get().state.value.aiModelPending

    /**
     * Whether a run waits for the reader at its Reading step instead of going without its AI steps
     * (D7): AI notes are on ([settings]) and the reader is on its way ([ReaderStatus.ON_ITS_WAY]).
     * Read as each run starts; it matters only to a recording with lines only the reader reads, and
     * only while the reader's files aren't on the phone. A look that fails says no, and the
     * recording is finished without its AI steps, as with no reader coming.
     */
    private suspend fun readerOnItsWay(settings: AppSettings): Boolean = try {
        settings.llmEnabled && reader.status() == ReaderStatus.ON_ITS_WAY
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Finishing could not tell whether the reader is on its way", e)
        false
    }

    /**
     * Wakes the queue while jobs wait for the AI model or the reader, whenever a wait may be over:
     * the first run's plan changes whether the model is on its way (it is in, or was dropped), the
     * reader's availability may have changed ([ReaderAvailability.changes]), AI notes are switched on
     * or off, and at each of [modelRechecks] (every [MODEL_RECHECK_MS] in the app), for a model or a
     * reader put on the phone another way (Settings' download, files copied in by hand). The queue
     * looks at the waits itself.
     */
    private suspend fun kickWhenModelMayBeIn() {
        waitingForModel.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { waiting ->
            if (!waiting) return@collectLatest
            merge(
                firstRun.get().state.map { it.aiModelPending }.distinctUntilChanged().map { },
                reader.changes,
                settingsRepository.getSettings().map { it.llmEnabled }.distinctUntilChanged().map { },
                modelRechecks
            )
                .catch { e -> log.warn("Finishing stopped looking for the AI model and the reader; the next hand-off looks again", e) }
                .collect { if (aWaitIsOver()) kick() }
        }
    }

    /**
     * Tells the owner that the notes of [sessionId] are ready ([NotesReadyCue]), once its finishing
     * has ended with notes on the recording: written now, or the live ones. Never throws.
     */
    private suspend fun announceNotes(sessionId: String) {
        try {
            if (transcriptionRepository.getInsightsBySession(sessionId).first().isNotEmpty()) notesReady.notesReady(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not say that the notes of $sessionId are ready", e)
        }
    }

    /**
     * Waits out the pause after a run whose model load the phone's memory stopped
     * ([settleLoadStopped]), holding neither the gate nor a model: a run right away would find the
     * phone as short, and its load would wait for memory again.
     */
    private suspend fun awaitMemoryPause() {
        val until = memoryPauseUntil ?: return
        // At most the pause, should the wall clock have gone back.
        val left = (until - now()).coerceIn(0L, MEMORY_PAUSE_MS)
        if (left > 0) {
            log.info("Finishing waits $left ms for memory before the next run")
            delay(left)
        }
        memoryPauseUntil = null
    }

    /** Runs [job] from where it is, as [FinishRecordingUseCase] with this queue's hooks. */
    private suspend fun runJob(job: FinishingJob): JobEnd {
        val sessionId = job.sessionId
        val startedAt = now()
        var from = job.nextStep
        val cuts = wakeJobCuts[sessionId]?.takeIf { it.first == from }?.second ?: 0
        val began = job.attempts >= MAX_STEP_BEGINS
        // The Reading step is never skipped for the wake job's cuts: each run of it stores what it
        // read, and the next goes on from the next line, so it ends; skipped, it would leave the
        // recording without its AI steps (D7) while the reader works.
        val cutShort = cuts >= MAX_WAKE_JOB_CUTS && from != FinishStep.Reading
        if (from != null && (began || cutShort)) {
            val why = if (began) "began twice" else "the wake job's end cut it short $cuts times"
            log.info("Finishing skipped $from of $sessionId: $why")
            wakeJobCuts.remove(sessionId)
            if (began && from == FinishStep.Reading) readingCrashed += sessionId
            from = FinishStep.entries.getOrNull(from.ordinal + 1)
                ?: return JobEnd.Done(FinishRecordingUseCase.Outcome.Finished, startedAt, recovered = job.recovered, redo = job.redo)
        }
        val attempt = if (from != null && from == job.nextStep) job.attempts + 1 else 1
        var run: Running? = null
        return try {
            coroutineScope {
                val current = Running(job, coroutineContext.job, startedAt)
                run = current
                running = current
                live.value = Live(sessionId)
                if (job.recovered) restoreLength(sessionId)
                val settings = settingsRepository.getSettings().first()
                // A recording whose live recognizer left audio untranscribed has it transcribed in that step.
                current.betterTranscriptSet = job.recognizerFailed || betterTranscriptLoads(sessionId, settings)
                val livePath = modelPaths.livePath(settings)
                // Registered, not loaded: the model when Gemma 4 E2B is off, and the fallback when it
                // can't be loaded. forgetModel() at the end of the queue clears it again.
                if (livePath != null) {
                    llm.initialize(livePath, loadImmediately = false)
                        .onFailure { log.warn("Finishing could not register the live model", it) }
                }
                val request = FinishRecordingUseCase.Request(
                    sessionId = sessionId,
                    aiAvailable = settings.llmEnabled && livePath != null,
                    mayChangeModel = true,
                    resumeFrom = from,
                    recovered = job.recovered,
                    recognizerFailed = job.recognizerFailed,
                    modelPending = waitsForModel(settings, livePath),
                    readerPending = readerOnItsWay(settings),
                    redo = job.redo
                )
                val again = if (job.redo) ", made again over its readings" else ""
                log.info("Finishing started $sessionId at ${from ?: "the start"}, attempt $attempt$again")
                when {
                    request.modelPending -> log.info("Finishing $sessionId up to its first AI step: the AI model is on its way")
                    !request.aiAvailable -> {
                        val why = if (settings.llmEnabled) "no live model on the phone" else "AI notes off"
                        log.info("Finishing $sessionId without the AI: $why")
                    }
                }
                val outcome = finish(request, progressOf(current))
                val noNotes = live.value?.takeIf { it.sessionId == sessionId }?.noNotes == true
                JobEnd.Done(outcome, startedAt, noNotes = noNotes, recovered = job.recovered, redo = job.redo)
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            endOfStop(run) ?: JobEnd.Failed(null).also { log.warn("Finishing of $sessionId was cancelled from inside", e) }
        } catch (e: Throwable) {
            // An error too (0.7.4): one that ended the worker left the job in "Finishing up" until
            // the process ended, and with it every job queued after it. A cancel is caught above.
            // A stop asked for still decides how the run ended when its work throws something else
            // as it stops: a cancelled job is no failed run that runs once more.
            val stopped = endOfStop(run)
            if (stopped != null) {
                log.warn("Finishing of $sessionId threw as it was stopped", e)
                stopped
            } else {
                log.warn("Finishing of $sessionId failed", e)
                JobEnd.Failed(e)
            }
        } finally {
            running = null
            live.value = null
        }
    }

    /**
     * How the run of [run] ended when it was stopped from outside its steps' work ([Running.stop]):
     * cancelled, for want of a keep-alive, its job gone, or a write of its job that failed; null
     * when nothing stopped it.
     */
    private fun endOfStop(run: Running?): JobEnd? = when (run?.stop) {
        Stop.CANCEL -> JobEnd.Cancelled
        Stop.KEEP_ALIVE -> JobEnd.KeepAliveLost
        Stop.GONE -> JobEnd.Gone
        Stop.FAILED -> {
            log.warn("Finishing of ${run.job.sessionId} failed", run.failure)
            JobEnd.Failed(run.failure)
        }
        // Deferred at the step it was stopped in, as a step a capture defers before it begins.
        Stop.CAPTURE -> JobEnd.Done(
            FinishRecordingUseCase.Outcome.Deferred(run.step ?: FinishStep.Reading),
            run.startedAt,
            recovered = run.job.recovered,
            redo = run.job.redo
        )
        null -> null
    }

    private fun progressOf(run: Running): FinishRecordingUseCase.Progress {
        val sessionId = run.job.sessionId
        return FinishRecordingUseCase.Progress(
            beforeStep = { step -> beforeStep(run, step) },
            onModel = { model -> logModel(sessionId, model, now() - run.stepBeganAt) },
            onTranscriptStart = { transcript ->
                run.transcript = transcript
                live.value = Live(sessionId, FinishStep.Transcript, TranscriptProgress(transcript.model))
            },
            onTranscriptProgress = { fraction ->
                updateLive(sessionId) { it.copy(transcript = it.transcript?.copy(fraction = fraction)) }
            },
            onBetterTranscript = { better ->
                run.transcript = null
                updateLive(sessionId) { it.copy(transcript = null) }
                logBetterTranscript(sessionId, better)
            },
            captureLive = { liveCapture(besides = sessionId) != null },
            awaitSpeechModelFreed = { awaitSpeechModelFreed(sessionId) },
            onModelWait = { wait -> logModelWait(sessionId, wait, now() - run.stepBeganAt) },
            onTodos = { outcome -> log.info("Finishing to-dos of $sessionId: $outcome") },
            onStepFailed = { step, e -> log.warn("Finishing: $step of $sessionId failed", e) },
            onTooLittleText = { tooLittle ->
                // At once, so home and the recording's page say it while the steps after it run.
                if (tooLittle.leavesNoNotes) updateLive(sessionId) { it.copy(noNotes = true) }
                logTooLittleText(sessionId, tooLittle)
            },
            onNoModelNeeded = { log.info("Finishing $sessionId: no AI step had anything to do, no model loaded") },
            awaitCaptureSettled = { awaitCaptureSettled(sessionId) },
            onLanguages = { languages -> logLanguages(sessionId, languages) },
            captureGrowthBytes = { captureGrowthBytes(sessionId) },
            onReadingStart = { reading ->
                run.reading = reading
                // A capture that went live as the run was handed out still stops it: the watch looks first.
                if (liveCapture(besides = sessionId) != null) reading.stop()
            },
            onReading = { counts -> logReading(sessionId, counts) },
            onRedo = { redo -> logRedo(sessionId, redo) }
        )
    }

    /**
     * One line for the field log as the AI steps of [sessionId], made again over its readings, come:
     * what of the owner's stays, counts only.
     */
    private fun logRedo(sessionId: String, redo: FinishRecordingUseCase.Redo) {
        val notes = if (redo.notesKept) "his notes stay" else "its notes are written again"
        val name = if (redo.nameKept) "his name stays" else "its name is the app's"
        log.info(
            "Finishing $sessionId made again over its readings: $notes, $name, " +
                "${redo.todosKept} to-dos he changed stay, ${redo.todosReplaced} of the app's give way"
        )
    }

    /**
     * One line for the field log as the Reading step of [sessionId] has read, counts only: the
     * lines it set out to read, how long it took, the readings kept, and those left empty, by
     * rule (D5, with the floor it ran with) or because the reader couldn't read the line.
     */
    private fun logReading(sessionId: String, counts: ReadingStep.Counts) {
        log.info("Finishing $sessionId: ${counts.line()}")
    }

    /**
     * The memory the speech model of a capture of another recording than [sessionId] may still take
     * on top of what it holds, as Gemma 4 E2B's room beside it is measured
     * ([SttRepository.liveModelGrowthBytes]): with the Malayalam model in, a switch of that
     * recording back to English or German loads Parakeet v3 in its place, a load of its recorder's
     * that never waits, so E2B is let in only with that room kept as well. 0 with no capture live.
     * The field log says how much was kept.
     */
    private fun captureGrowthBytes(sessionId: String): Long {
        if (liveCapture(besides = sessionId) == null) return 0L
        val growth = stt.liveModelGrowthBytes()
        if (growth > 0) {
            log.info("Finishing $sessionId: Gemma 4 E2B's room beside the capture keeps ${growth / BYTES_PER_MB} MB for its speech model")
        }
        return growth
    }

    /**
     * One line for the field log as the AI steps of [sessionId] read its transcript: the language
     * it is in ("unknown" when the detector isn't sure), the one the recording is made in, and the
     * one the notes are written in. A Malayalam recording's: `transcript in ml, recorded in
     * MALAYALAM, notes in en`.
     */
    private fun logLanguages(sessionId: String, languages: FinishRecordingUseCase.Languages) {
        log.info(
            "Finishing $sessionId: transcript in ${languages.spoken ?: "unknown"}, recorded in ${languages.recording}, " +
                "notes in ${languages.notes ?: "the settings' language"}"
        )
    }

    /**
     * Right before Gemma 4 E2B's room beside a capture of another recording is measured: waits
     * while that capture's speech model is on its way, at most [CAPTURE_OPENING_WAIT_MS]. It is
     * while its recorder starts or its microphone opens (as [awaitCaptureOpened] waits before the
     * step), and while its live model is changed after a switch of its language, which frees the
     * one before the other loads: memory measured then would count the room that model takes in a
     * second or three. The step's own wait came before the step began, and either can begin after it.
     *
     * @return Whether the capture's speech model is in; false defers the job without a load
     */
    private suspend fun awaitCaptureSettled(sessionId: String): Boolean {
        if (!isOpening(stt.captureState.value, recorder.value, sessionId)) return true
        val since = now()
        val settled = withTimeoutOrNull(CAPTURE_OPENING_WAIT_MS) {
            combine(stt.captureState, recorder) { state, capture -> isOpening(state, capture, sessionId) }.first { !it }
        } != null
        val how = if (settled) "" else ", deferring"
        log.info("Finishing waited ${now() - since} ms for a capture's speech model before Gemma 4 E2B$how")
        return settled
    }

    /**
     * One line for the field log as the Notes step of [sessionId] finds too little text for its
     * notes, before any model is chosen: what it skipped, and the words there were and needed.
     */
    private fun logTooLittleText(sessionId: String, tooLittle: FinishRecordingUseCase.TooLittleText) {
        val what = when {
            tooLittle.keepsNotes -> "a final note"
            tooLittle.translation -> "a translation"
            else -> "notes"
        }
        val kept = if (tooLittle.keepsNotes) "; the notes it has stay" else ""
        log.info(
            "Finishing $sessionId: too little text for $what (${tooLittle.words} words, " +
                "${tooLittle.neededWords} needed), no model chosen for it$kept"
        )
    }

    /**
     * The hooks before [step] of [run]. The job is at this step from their start, also for the
     * next process should this one die, but the start counts only once the waits are over; a
     * deferral or a stop after that gives it back. False defers the job.
     */
    private suspend fun beforeStep(run: Running, step: FinishStep): Boolean {
        val sessionId = run.job.sessionId
        run.counted = false
        run.step = step
        // A reading of an earlier step is over; the Reading step hands out its own as the reader loads.
        run.reading = null
        begin(run, step)
        stored(run) { jobs.giveBackAttempt(sessionId) }
        // The better transcript shows once its model runs (onTranscriptStart); it may not run at all.
        if (step != FinishStep.Transcript) updateLive(sessionId) { it.copy(step = step, transcript = null) }
        if (mayLoadModel(run, step)) awaitCaptureOpened(sessionId)
        pauseWhileHot(sessionId)
        begin(run, step)
        run.counted = true
        if (translationLive(sessionId)) return false
        // Nobody waits for a recording made again: none of its steps runs beside a capture (design R9).
        if (run.job.redo && liveCapture(besides = sessionId) != null) return false
        if (!keptAliveNow()) {
            run.stop = Stop.KEEP_ALIVE
            throw CancellationException("No keep-alive for the finishing of $sessionId")
        }
        run.stepBeganAt = now()
        log.info("Finishing: $step of $sessionId after ${run.stepBeganAt - run.startedAt} ms${besideCapture(sessionId)}")
        return true
    }

    /**
     * Whether [step] of [run] may load a model, and so waits for a capture on its way first
     * ([awaitCaptureOpened]). Joining lines loads none, nor does the better transcript without a
     * speech model for it ([Running.betterTranscriptSet]): the steps up to the notes then run
     * right after Stop, so a recording
     * with too little text for notes says so at once, and the first step that may load a model
     * waits what is left of [HAND_OFF_GRACE_MS].
     */
    private fun mayLoadModel(run: Running, step: FinishStep): Boolean = when (step) {
        FinishStep.Lines -> false
        FinishStep.Transcript -> run.betterTranscriptSet
        else -> true
    }

    /** Stores that [step] of [run] begins; a job that is gone stops the run. */
    private suspend fun begin(run: Running, step: FinishStep) {
        if (!stored(run) { jobs.beginStep(run.job.sessionId, step) }) {
            run.stop = Stop.GONE
            throw CancellationException("The job of ${run.job.sessionId} is gone")
        }
    }

    /**
     * A write of [run]'s job as a step begins. One that fails stops the run as a cancel, which
     * the use case passes on, so it isn't taken for a failure of the step's own work.
     */
    private suspend fun <T> stored(run: Running, write: suspend () -> T): T = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        run.failure = e
        run.stop = Stop.FAILED
        throw CancellationException("The job of ${run.job.sessionId} could not be stored", e)
    }

    /**
     * Gives a recording started right after Stop (the quick key) its start: [HAND_OFF_GRACE_MS]
     * after the last hand-off or take-up, and while another recorder starts, or a capture opens
     * its microphone and loads its speech model, at most [CAPTURE_OPENING_WAIT_MS]. A capture on
     * its way that wasn't there within that wait ([waitedOut]), a recorder held in its start by
     * the lock screen say, isn't waited for again; once its microphone opens, it is once more.
     */
    private suspend fun awaitCaptureOpened(sessionId: String) {
        graceFrom?.let { from ->
            // At most the grace, should the wall clock have gone back.
            val left = (from + HAND_OFF_GRACE_MS - now()).coerceIn(0L, HAND_OFF_GRACE_MS)
            if (left > 0) delay(left)
        }
        if (!isOpening(stt.captureState.value, recorder.value, sessionId)) return
        if (openingNow() == waitedOut) return
        val since = now()
        val opened = withTimeoutOrNull(CAPTURE_OPENING_WAIT_MS) {
            combine(stt.captureState, recorder) { state, capture -> isOpening(state, capture, sessionId) }.first { !it }
        }
        val waited = now() - since
        if (opened != null) {
            log.info("Finishing waited $waited ms for a capture to open")
        } else {
            waitedOut = openingNow()
            log.info("Finishing waited $waited ms for a capture to open, going on")
        }
    }

    /** The capture on its way now, as [awaitCaptureOpened] tells one from the next. */
    private fun openingNow(): Opening {
        val state = stt.captureState.value
        return Opening(recorder.value?.sessionId, microphone = state == CaptureState.OPENING || state == CaptureState.LISTENING)
    }

    /**
     * Whether a capture of another recording than [besides] is on its way: its recorder starts
     * (the speech model's files are checked, the microphone asked for), or its microphone opens
     * and its first words are listened for (OPENING, LISTENING).
     */
    private fun isOpening(state: CaptureState, capture: ActiveRecorder.Snapshot?, besides: String): Boolean =
        state == CaptureState.OPENING || state == CaptureState.LISTENING ||
            (capture != null && capture.activity == ActiveRecorder.Activity.STARTING && capture.sessionId != besides)

    /**
     * Pauses while the phone is hot ([ThermalThrottle.Reduced]) and a capture is live beside it.
     * Not for a Translate capture, for which the job is deferred instead: it would hold the gate
     * that capture's live translation needs.
     */
    private suspend fun pauseWhileHot(sessionId: String) {
        fun mustPause(heat: ThermalThrottle, capture: ActiveRecorder.Snapshot?): Boolean =
            heat is ThermalThrottle.Reduced && liveCapture(capture, besides = sessionId)?.translation == false
        if (!mustPause(thermal.value, recorder.value)) return
        log.info("Finishing paused: phone hot")
        val since = now()
        combine(thermal, recorder) { heat, capture -> mustPause(heat, capture) }.first { !it }
        log.info("Finishing goes on after a pause of ${now() - since} ms")
    }

    /** Whether a Translate capture of another recording than [sessionId] is live. */
    private fun translationLive(sessionId: String): Boolean = liveCapture(besides = sessionId)?.translation == true

    /**
     * Before Gemma 4 E2B loads with no capture live: waits for the live speech model to be out of
     * memory ([SttRepository.liveModelLoaded]), which the recorder that closed frees as its
     * capture ends, at most [SPEECH_MODEL_FREE_WAIT_MS]. A recorder frees it before its hand-off,
     * so this waits only when a release came late. A capture of another recording that goes live
     * ends the wait: the speech model is that capture's own then, and the model step defers the
     * job without a load ([FinishRecordingUseCase.Outcome.Deferred.captureOpening]).
     */
    private suspend fun awaitSpeechModelFreed(sessionId: String) {
        fun over(loaded: Boolean, capture: ActiveRecorder.Snapshot?) = !loaded || liveCapture(capture, besides = sessionId) != null
        if (over(stt.liveModelLoaded.value, recorder.value)) return
        val since = now()
        val ended = withTimeoutOrNull(SPEECH_MODEL_FREE_WAIT_MS) {
            combine(stt.liveModelLoaded, recorder) { loaded, capture -> over(loaded, capture) }.first { it }
        }
        val how = when {
            ended == null -> ", going on"
            liveCapture(besides = sessionId) != null -> ", a capture started"
            else -> ""
        }
        log.info("Finishing waited ${now() - since} ms for the speech model to be freed$how")
    }

    /**
     * One line for the field log as the model step of [sessionId] waits for memory with no
     * capture live, or as that wait ends; [sinceStepMs] is the time from the step's start. What
     * loaded comes next ([logModel]), or the deferral.
     */
    private fun logModelWait(sessionId: String, wait: FinishRecordingUseCase.ModelWait, sinceStepMs: Long) {
        when (wait) {
            is FinishRecordingUseCase.ModelWait.Short -> log.info(
                "Finishing $sessionId: no room for Gemma 4 E2B with no capture live (${figures(wait.noRoom)}), " +
                    "trying again for up to ${FinishRecordingUseCase.IDLE_RETRY_MS / MS_PER_SECOND} s"
            )
            is FinishRecordingUseCase.ModelWait.Room ->
                log.info("Finishing $sessionId: room for Gemma 4 E2B at try ${wait.tries}, after $sinceStepMs ms")
            is FinishRecordingUseCase.ModelWait.StillShort -> log.info(
                "Finishing $sessionId: still no room for Gemma 4 E2B after $sinceStepMs ms (${figures(wait.noRoom)}), " +
                    "the live model does the work"
            )
            FinishRecordingUseCase.ModelWait.CaptureStarted ->
                log.info("Finishing $sessionId: a capture started while Gemma 4 E2B didn't fit, after $sinceStepMs ms")
        }
    }

    /** The memory there was and the memory a model needed, for the field log. */
    private fun figures(noRoom: NotEnoughMemoryException): String =
        "${noRoom.availableBytes / BYTES_PER_MB} MB available, ${noRoom.neededBytes / BYTES_PER_MB} MB needed"

    /**
     * The recorder whose capture is live, as everything in the finishing reads it, unless it is
     * the recorder of [besides]: it starts, records or saves ([isLiveCapture]), and its recording
     * has not been handed off yet. A recorder still reads saving for a moment after the hand-off
     * ([lastHandedOff]), but its capture is over then: its last words are saved and its speech
     * model released. Null when no capture is live.
     */
    private fun liveCapture(capture: ActiveRecorder.Snapshot? = recorder.value, besides: String? = null): ActiveRecorder.Snapshot? =
        capture?.takeIf {
            it.isLiveCapture(besides) && !(it.activity == ActiveRecorder.Activity.SAVING && it.sessionId == lastHandedOff)
        }

    private fun keptAliveNow(): Boolean = controller.protected.value || underWakeJob.value

    /**
     * For the field log of a step that begins beside a capture of another recording while a model
     * is in memory: the memory free then. The margin is checked only as Gemma 4 E2B loads, and
     * this shows what a capture that started after the load left.
     */
    private fun besideCapture(sessionId: String): String {
        if (liveCapture(besides = sessionId) == null || !llm.isModelInMemory) return ""
        val available = try {
            memory.availableBytes()
        } catch (e: Exception) {
            return ""
        }
        return ", beside a capture with a model in memory, ${available / BYTES_PER_MB} MB available"
    }

    /**
     * Waits until something keeps the process alive: the AI's service in the foreground, or the
     * wake job running. The service is given [KEEP_ALIVE_WAIT_MS] from the last start before the
     * wake job is scheduled. In a process that never asked for the service (started by a boot,
     * or by the wake job), the wake job that started it reports itself right after its resume,
     * which [WAKE_JOB_SETTLE_MS] leaves room for.
     */
    private suspend fun awaitKeepAlive() {
        if (keptAliveNow()) return
        val started = lastStartAt
        val left = if (started == null) WAKE_JOB_SETTLE_MS else (started + KEEP_ALIVE_WAIT_MS - now()).coerceIn(0L, KEEP_ALIVE_WAIT_MS)
        if (left > 0 && withTimeoutOrNull(left) { keptAlive.first { it } } != null) return
        // A wake job may have begun meanwhile: scheduling it again would stop it.
        if (keptAliveNow()) return
        wake.schedule()
        log.info("Finishing waits for a keep-alive")
        val since = now()
        keptAlive.first { it }
        log.info("Finishing keep-alive gained after ${now() - since} ms")
    }

    /**
     * The jobs left all wait for a capture to end: the one they were deferred for ([deferredFor]).
     * Once it has ended every deferred job may run again, beside a capture that started since if
     * need be, which defers them anew if it has to. A hand-off ends the wait as well: its job may
     * run beside the capture, and the deferred ones wait on, but the hand-off of the capture they
     * wait for is its end ([liveCapture]). A capture's end is seen also when the next one starts
     * right after it, before its recorder reported idle: the recorder then reports another
     * recording.
     */
    private suspend fun awaitNoCapture() {
        val waitFor = awaitedCapture()
        if (waitFor != null && isLiveCaptureOf(waitFor)) {
            waitingForCapture = true
            finishWakeJob()
            try {
                awaitCaptureEndOrKick(waitFor)
            } finally {
                waitingForCapture = false
            }
            if (isLiveCaptureOf(waitFor)) return
        }
        goOnWithDeferred()
    }

    /**
     * Before a claim: the deferred jobs go on if the capture they wait for is over, so the claim
     * takes them in their place in the queue. A capture that is still live keeps them waiting. Jobs
     * that wait for the AI model alone are no reason to: their wait ends with the model.
     */
    private suspend fun goOnWithDeferredIfOver() {
        if (queuedJobs().none { it.waitsForRecording && it.sessionId !in waitingForModel.value }) return
        val waitFor = awaitedCapture()
        if (waitFor == null || !isLiveCaptureOf(waitFor)) goOnWithDeferred()
    }

    /** The capture the deferred jobs wait for: [deferredFor], or for jobs a process that is gone deferred, the one live now. */
    private fun awaitedCapture(): String? = deferredFor ?: liveCapture()?.sessionId

    private suspend fun goOnWithDeferred() {
        deferredFor = null
        val released = jobs.releaseDeferred()
        if (released > 0) log.info("Finishing goes on with $released deferred")
    }

    private fun isLiveCaptureOf(sessionId: String): Boolean = liveCapture()?.sessionId == sessionId

    /** Returns once the capture of [sessionId] has ended, or the queue is kicked. */
    private suspend fun awaitCaptureEndOrKick(sessionId: String) = coroutineScope {
        val ended = async { recorder.first { liveCapture(it)?.sessionId != sessionId } }
        try {
            select<Unit> {
                ended.onAwait {}
                kicks.onReceive {}
            }
        } finally {
            ended.cancel()
        }
    }

    /**
     * Frees the model and the gate; also when the jobs were cut short. Run it NonCancellable. The
     * gate goes also when freeing the model throws an error, which then ends the drain (0.7.4):
     * the gate is not reentrant, so a gate this queue kept would leave its next drain waiting at
     * enter for ever, and every job queued after it holding the service and showing as finishing.
     */
    private suspend fun releaseEngine() {
        try {
            try {
                llm.cleanup()
            } catch (e: Exception) {
                log.warn("Finishing could not free the model", e)
            }
            try {
                llm.forgetModel()
            } catch (e: Exception) {
                log.warn("Finishing could not forget the model", e)
            }
        } finally {
            gate.exit(AiWorkGate.FINISHER)
        }
    }

    /**
     * A recording whose capture was cut off kept its length only as of the last save while it
     * recorded (every 20 s): its audio says how long it ran.
     */
    private suspend fun restoreLength(sessionId: String) {
        try {
            val stored = transcriptionRepository.getSession(sessionId).first()?.durationMs ?: return
            val heard = audioRepository.find(sessionId)?.durationMs ?: return
            if (heard <= stored) return
            updateSessionDuration(sessionId, heard)
                .onSuccess { log.info("Finishing took the length of $sessionId from its audio: $heard ms, $stored ms stored") }
                .onFailure { log.warn("Finishing could not store the length of $sessionId", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Finishing could not read the length of $sessionId", e)
        }
    }

    /**
     * Whether the better transcript after Stop of [sessionId] has a speech model to load: one set
     * for it in [settings], or, for a recording stored in Malayalam, the Malayalam model, whatever
     * is set ([SpeechModelChoice.afterStopModel]). Its Transcript step then waits the hand-off
     * grace and for a capture that opens, as every step that loads a model does. A read of the
     * recording that fails says yes: the step waits rather than load beside a new recording.
     */
    private suspend fun betterTranscriptLoads(sessionId: String, settings: AppSettings): Boolean = try {
        val language = transcriptionRepository.getSession(sessionId).first()?.language ?: RecordingLanguage.ENGLISH_OR_GERMAN
        SpeechModelChoice.afterStopModel(language, settings.transcribeAfterStop) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Finishing could not read the language of $sessionId; its better transcript waits as one that loads a model", e)
        true
    }

    /**
     * Whether a saved line of [sessionId] holds speech ([DiscardSilentRecordingUseCase.holdsSpeech]):
     * a capture that was cut off is kept also without a word in it, when its audio may hold some,
     * and only one with words ends the first run. A read that fails says no.
     */
    private suspend fun hasWords(sessionId: String): Boolean = try {
        transcriptionRepository.getSegmentsBySession(sessionId).first().any { DiscardSilentRecordingUseCase.holdsSpeech(it.text) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Finishing could not read the lines of $sessionId", e)
        false
    }

    private fun reasonOf(deferred: FinishRecordingUseCase.Outcome.Deferred, redo: Boolean): String {
        deferred.noRoom?.let { noRoom -> return "no room for Gemma 4 E2B beside a capture (${figures(noRoom)})" }
        return when {
            deferred.step == FinishStep.Reading && deferred.captureOpening -> "a capture is live, which the reader never reads beside"
            deferred.captureOpening -> "a capture started as Gemma 4 E2B was to load"
            redo -> "a capture is live, which a recording made again never runs beside"
            else -> "a Translate capture is live"
        }
    }

    /**
     * One line for the field log: the model that finishes the recording, and how long it took to
     * have it ready from the start of its first AI step (unloading the live model and loading
     * E2B, or nothing when the live model stays).
     */
    private fun logModel(sessionId: String, model: FinishingModel, readyAfterMs: Long) {
        when (model) {
            FinishingModel.Live -> log.info("Finishing $sessionId on the live model")
            FinishingModel.Switched -> log.info("Finishing $sessionId on Gemma 4 E2B, ready after $readyAfterMs ms")
            is FinishingModel.FellBack -> log.warn(
                "Gemma 4 E2B could not be loaded ($readyAfterMs ms), finishing $sessionId on the live model",
                model.cause
            )
        }
    }

    /**
     * One line for the field log: the speech model that made the recording's transcript after
     * Stop, or why the live one stays. Model ids and reasons only, never text.
     */
    private fun logBetterTranscript(sessionId: String, better: BetterTranscript) {
        when (better) {
            is BetterTranscript.Replaced -> log.info("Better transcript after Stop of $sessionId by ${better.model.id}")
            is BetterTranscript.Skipped -> if (better.reason != BetterTranscript.Reason.Off) {
                log.info("No better transcript after Stop of $sessionId with ${better.model?.id}: ${better.reason}")
            }
            is BetterTranscript.Failed ->
                log.warn("Better transcript after Stop of $sessionId with ${better.model.id} failed", better.cause)
        }
    }

    /**
     * The statuses of the jobs in [rows] (queue order), with the step of the job that runs
     * ([state]). A job that waits for the AI model or the reader ([forModel]) says it waits for the
     * AI model, also while it runs again before any step of it begins: a run that only finds it
     * still waits.
     */
    private fun statusesOf(rows: List<FinishingJob>, state: Live?, forModel: Map<String, Waits>): List<FinishingStatus> {
        var waiting = 0
        return rows.mapNotNull { job ->
            val now = state?.takeIf { it.sessionId == job.sessionId }
            val waitsForModel = forModel[job.sessionId]
            when {
                job.state == FinishingJobState.CAPTURING -> null
                waitsForModel != null && (job.state == FinishingJobState.QUEUED || now?.step == null) ->
                    FinishingStatus(job.sessionId, FinishingStatus.Phase.WAITS_FOR_MODEL, noNotes = waitsForModel.noNotes || now?.noNotes == true)
                job.state == FinishingJobState.RUNNING -> FinishingStatus(
                    sessionId = job.sessionId,
                    phase = FinishingStatus.Phase.RUNNING,
                    step = now?.step,
                    transcript = now?.transcript?.takeIf { now.step == FinishStep.Transcript },
                    noNotes = now?.noNotes == true
                )
                job.waitsForRecording -> FinishingStatus(job.sessionId, FinishingStatus.Phase.WAITS_FOR_RECORDING)
                else -> FinishingStatus(job.sessionId, FinishingStatus.Phase.WAITING, position = ++waiting)
            }
        }
    }

    companion object {
        /** How long the AI's service may take to be in the foreground after a start before the wake job is scheduled. */
        const val KEEP_ALIVE_WAIT_MS = 5_000L

        /**
         * How long a queue that never asked for the service waits for a wake job to report itself
         * ([runUnderWakeJob]) before it schedules one: FinishingWakeJob resumes the queue and then
         * reports itself, and a job scheduled while it runs is stopped.
         */
        const val WAKE_JOB_SETTLE_MS = 1_000L

        /**
         * How long after a hand-off, or the take-up of jobs as the process starts, a step waits
         * before it may load a model: a quick-key press 1-2 s after Stop, or one that started the
         * process, starts the next recording, whose speech model needs the memory first.
         */
        const val HAND_OFF_GRACE_MS = 3_000L

        /**
         * The longest a step waits for a recorder that starts, or a capture that opens (OPENING or
         * LISTENING), to transcribe.
         */
        const val CAPTURE_OPENING_WAIT_MS = 10_000L

        /**
         * The longest Gemma 4 E2B waits, with no capture live, for the live speech model to be
         * freed: the release waits only for the decode under way, 1.1 s at most on 2026-10-13.
         */
        const val SPEECH_MODEL_FREE_WAIT_MS = 5_000L

        /** How often a step may begin without the job moving on before it is skipped. */
        const val MAX_STEP_BEGINS = 2

        /**
         * How often the run of a job may fail in a process. One that failed as often waits for the
         * next process, or ends when its last run threw an error, which every process would throw
         * again (since 0.7.4, [settleFailed]).
         */
        const val MAX_RUN_FAILURES = 2

        /**
         * How long the queue waits before it runs again after the phone's memory stopped a model
         * load of a run: the paced load had waited 10 s for MemFree to come back above the pacing
         * floor.
         */
        const val MEMORY_PAUSE_MS = 60_000L

        /** How often in a process the phone's memory may stop a job's run before the job waits for the next process. */
        const val MAX_MEMORY_STOPS = 3

        /**
         * How often in a row, in a process, the wake job's end may cut the same step short before
         * the step is skipped: a step that outlasts every run of the wake job (the whole-pass notes
         * of a long meeting, say) would otherwise begin again for ever.
         */
        const val MAX_WAKE_JOB_CUTS = 2

        /** How long after a failed read of the queue it is read again; doubled for each failure after, up to [QUEUE_READ_RETRY_MAX_MS]. */
        const val QUEUE_READ_RETRY_MS = 1_000L

        /** The longest wait before the queue is read again after a failed read. */
        const val QUEUE_READ_RETRY_MAX_MS = 60_000L

        /**
         * How often, while jobs wait for the AI model, the queue looks whether it is on the phone,
         * also when nothing said so: the design's promise is that they go on within 30 s of it.
         */
        const val MODEL_RECHECK_MS = 30_000L

        // 1 s doubled six times is past the longest wait.
        private const val QUEUE_READ_RETRY_DOUBLINGS = 6L

        private const val BYTES_PER_MB = 1_048_576L
        private const val MS_PER_SECOND = 1_000L
    }
}

/**
 * Whether this recorder's capture is live, as everything in the finishing reads it: the recorder
 * starts, records or saves, anything but [ActiveRecorder.Activity.IDLE]. The recorder of
 * [besides] doesn't count: a recording's own recorder may still be saving as its job begins.
 */
internal fun ActiveRecorder.Snapshot?.isLiveCapture(besides: String? = null): Boolean =
    this != null && activity != ActiveRecorder.Activity.IDLE && sessionId != besides

/** Where the finisher's field-log lines go: logcat in the app; a list in the tests, which have no logcat. */
internal interface FinisherLog {
    fun info(message: String)

    fun warn(message: String, cause: Throwable? = null)

    /** Logcat, under SatoriFinishing. */
    object Logcat : FinisherLog {
        private const val TAG = "SatoriFinishing"

        override fun info(message: String) {
            Log.i(TAG, message)
        }

        override fun warn(message: String, cause: Throwable?) {
            Log.w(TAG, message, cause)
        }
    }
}
