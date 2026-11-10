package com.satori.presentation.main

import com.satori.domain.finish.FinishingJob
import com.satori.domain.finish.FinishingJobRepository
import com.satori.domain.finish.FinishingJobState
import com.satori.domain.finish.FinishingWake
import com.satori.domain.model.AppSettings
import com.satori.domain.model.AudioSpan
import com.satori.domain.model.CaptureLanguage
import com.satori.domain.model.CaptureState
import com.satori.domain.model.InsightStrategy
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.RecordingPlace
import com.satori.domain.model.SearchResult
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.SpokenLine
import com.satori.domain.model.ThermalThrottle
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.reading.ReaderAvailability
import com.satori.domain.reading.ReaderStatus
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.NotEnoughMemoryException
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.ReaderNotLoadedException
import com.satori.domain.repository.RecordingAudioRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SttRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.finish.BetterTranscriptRun
import com.satori.domain.usecase.finish.FinishRecordingUseCase
import com.satori.domain.usecase.finish.FinishStep
import com.satori.domain.usecase.finish.ReadingRun
import com.satori.domain.usecase.finish.ReadingStep
import com.satori.presentation.ai.AiWorkGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

// The doubles of the finisher's tests (Part A): the stored queue, the AI's service, the wake job,
// the models and the use case, each as small as the finisher needs it.

/**
 * The finishing queue in memory, under the rules of the finishing_jobs DAO. A recording in
 * [goneSessions] has no row to write to, as after its delete. As with Room, a write asked for by
 * a caller that is cancelled doesn't begin. The writes named in [failing] ("beginStep",
 * "claimNext", "delete", ...) throw as many more times as it says, as SQLite does on a full disk,
 * and a hand-off's write waits for [queueWrite] while it is set, a capture start's for
 * [captureWrite].
 */
internal class InMemoryFinishingJobs : FinishingJobRepository {
    private val rows = MutableStateFlow<Map<String, FinishingJob>>(emptyMap())
    val goneSessions = mutableSetOf<String>()
    val failing = mutableMapOf<String, Int>()
    var queueWrite: CompletableDeferred<Unit>? = null
    var captureWrite: CompletableDeferred<Unit>? = null
    var recoveries = 0
        private set

    /** Writes [job] as it is, as a process before this one left it. */
    fun seed(job: FinishingJob) {
        rows.value = rows.value + (job.sessionId to job)
    }

    /** Takes the row away as its recording's delete does (the foreign key cascades). */
    fun drop(sessionId: String) {
        rows.value = rows.value - sessionId
    }

    fun row(sessionId: String): FinishingJob? = rows.value[sessionId]

    override fun observeAll(): Flow<List<FinishingJob>> = rows.map { all ->
        all.values.sortedWith(
            compareBy<FinishingJob>(
                {
                    when (it.state) {
                        FinishingJobState.RUNNING -> 0
                        FinishingJobState.QUEUED -> 1
                        FinishingJobState.CAPTURING -> 2
                    }
                },
                { it.redo },
                { it.queuedAt },
                { it.createdAt },
                { it.sessionId }
            )
        )
    }

    override suspend fun get(sessionId: String): FinishingJob? = rows.value[sessionId]

    override suspend fun captureStarted(sessionId: String, owner: String, now: Long) {
        write("captureStarted")
        captureWrite?.await()
        val job = rows.value[sessionId]
        when {
            job != null -> put(job.copy(state = FinishingJobState.CAPTURING, owner = owner))
            sessionId !in goneSessions -> put(FinishingJob(sessionId, FinishingJobState.CAPTURING, null, 0, owner, false, false, false, null, now))
        }
    }

    override suspend fun queue(sessionId: String, recognizerFailed: Boolean, now: Long): Boolean {
        write("queue")
        queueWrite?.await()
        val job = rows.value[sessionId]
        if (job != null) {
            put(job.copy(state = FinishingJobState.QUEUED, queuedAt = now, recognizerFailed = recognizerFailed))
            return true
        }
        if (sessionId in goneSessions) return false
        put(FinishingJob(sessionId, FinishingJobState.QUEUED, null, 0, "", recognizerFailed, false, false, now, now))
        return true
    }

    override suspend fun recoverForeign(owner: String, now: Long): Int {
        write("recoverForeign")
        recoveries++
        val foreign = rows.value.values.filter { it.state != FinishingJobState.QUEUED && it.owner != owner }
        for (job in foreign) {
            put(
                job.copy(
                    state = FinishingJobState.QUEUED,
                    recovered = job.recovered || job.state == FinishingJobState.CAPTURING,
                    queuedAt = job.queuedAt ?: now
                )
            )
        }
        return foreign.size
    }

    override suspend fun claimNext(owner: String): FinishingJob? {
        write("claimNext")
        val next = rows.value.values
            .filter { it.state == FinishingJobState.QUEUED && !it.waitsForRecording }
            .sortedWith(compareBy<FinishingJob>({ it.redo }, { it.queuedAt }, { it.createdAt }, { it.sessionId }))
            .firstOrNull() ?: return null
        return next.copy(state = FinishingJobState.RUNNING, owner = owner).also(::put)
    }

    override suspend fun beginStep(sessionId: String, step: FinishStep): Boolean {
        write("beginStep")
        val job = rows.value[sessionId] ?: return false
        put(job.copy(nextStep = step, attempts = if (job.nextStep == step) job.attempts + 1 else 1))
        return true
    }

    override suspend fun giveBackAttempt(sessionId: String) {
        write("giveBackAttempt")
        val job = rows.value[sessionId] ?: return
        put(job.copy(attempts = maxOf(job.attempts - 1, 0)))
    }

    override suspend fun requeue(sessionId: String) {
        write("requeue")
        val job = rows.value[sessionId]?.takeIf { it.state == FinishingJobState.RUNNING } ?: return
        put(job.copy(state = FinishingJobState.QUEUED))
    }

    override suspend fun defer(sessionId: String) {
        write("defer")
        val job = rows.value[sessionId]?.takeIf { it.state != FinishingJobState.CAPTURING } ?: return
        put(job.copy(state = FinishingJobState.QUEUED, waitsForRecording = true))
    }

    override suspend fun releaseDeferred(): Int {
        write("releaseDeferred")
        val deferred = rows.value.values.filter { it.waitsForRecording }
        deferred.forEach { put(it.copy(waitsForRecording = false)) }
        return deferred.size
    }

    override suspend fun releaseDeferred(sessionId: String): Boolean {
        write("releaseDeferred")
        val job = rows.value[sessionId]?.takeIf { it.waitsForRecording } ?: return false
        put(job.copy(waitsForRecording = false))
        return true
    }

    // As the DAO's queueRedo: a new job of a recording without one, at its Reading step.
    override suspend fun queueRedo(sessionId: String, now: Long): Boolean {
        write("queueRedo")
        if (rows.value[sessionId] != null || sessionId in goneSessions) return false
        put(FinishingJob(sessionId, FinishingJobState.QUEUED, FinishStep.Reading, 0, "", false, false, false, now, now, redo = true))
        return true
    }

    override suspend fun delete(sessionId: String) {
        write("delete")
        drop(sessionId)
    }

    private fun put(job: FinishingJob) {
        rows.value = rows.value + (job.sessionId to job)
    }

    private suspend fun write(call: String) {
        currentCoroutineContext().ensureActive()
        val left = failing[call] ?: return
        if (left <= 0) return
        failing[call] = left - 1
        throw IllegalStateException("database or disk is full")
    }
}

/**
 * The AI's foreground service: the holders, and whether Android has the service in the
 * foreground ([protected]), which a start makes so unless it is [refused].
 */
internal class FakeProcessing : LlmProcessingServiceController {
    val holders = mutableSetOf<String>()
    val log = mutableListOf<String>()
    val shown = mutableListOf<Pair<Int, FinishStep?>>()
    var refused = false
    override val protected = MutableStateFlow(false)

    override fun startProcessing(holder: String): Boolean {
        holders += holder
        log += "start $holder"
        if (!refused) protected.value = true
        return !refused
    }

    override fun stopProcessing(holder: String) {
        if (!holders.remove(holder)) return
        log += "stop $holder"
        if (holders.isEmpty()) protected.value = false
    }

    override fun showFinishing(recordings: Int, step: FinishStep?) {
        shown += recordings to step
    }
}

internal class FakeWake : FinishingWake {
    var scheduled = 0
    var cancelled = 0

    override fun schedule() {
        scheduled++
    }

    override fun cancel() {
        cancelled++
    }
}

/**
 * A speech repository whose capture is at [captureState], with the live speech model in memory
 * while [liveModelLoaded] says so, which may still grow by [growthBytes]; nothing else is asked of it.
 */
internal class FakeCapture : SttRepository {
    override val captureState = MutableStateFlow(CaptureState.IDLE)
    override val audioLevel = MutableStateFlow(0f)
    override val liveModelLoaded = MutableStateFlow(false)

    @Volatile
    var growthBytes = 0L

    override fun liveModelGrowthBytes(): Long = growthBytes

    override suspend fun initialize(): Result<Unit> = unused()
    override fun startStreaming(sessionId: String, keepAudio: Boolean, language: CaptureLanguage): Flow<TranscriptionSegment> = unused()
    override fun mayHaveHeardSpeech(): Boolean = unused()
    override suspend fun stopStreaming(): Unit = unused()
    override suspend fun releaseModel(): Unit = unused()
    override suspend fun transcribeAudio(
        audio: RecordingAudio,
        model: SpeechModelId,
        language: String?,
        onProgress: (Float) -> Unit
    ): List<SpokenLine> = unused()
}

/**
 * The engine as the finisher touches it: the live model it registers, and the release at the
 * end, each logged with who held the [gate] then. The phone's heat is [thermal]; a model is in
 * memory while [inMemory] says so.
 */
internal class EngineLog(private val gate: AiWorkGate) : LlmRepository {
    val log = mutableListOf<String>()
    val thermal = MutableStateFlow<ThermalThrottle>(ThermalThrottle.Normal)
    var inMemory = false
    override val thermalThrottleFlow: Flow<ThermalThrottle> = thermal
    override val isModelInMemory: Boolean get() = inMemory

    override suspend fun initialize(modelPath: String, systemPrompt: String?, loadImmediately: Boolean): Result<Unit> {
        log += "register $modelPath"
        return Result.success(Unit)
    }

    override suspend fun cleanup() {
        log += "cleanup under ${gate.holder.value}"
    }

    override suspend fun forgetModel() {
        log += "forget under ${gate.holder.value}"
    }

    override suspend fun reloadModel(): Result<Unit> = unused()
    override suspend fun switchModel(modelPath: String): Result<Unit> = unused()
    override fun isMemoryConstrained(): Boolean = unused()
    override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = unused()
    override suspend fun updateSystemPrompt(systemPrompt: String): Result<Unit> = unused()
    override fun useConservativeThreads(): Unit = unused()
    override suspend fun checkAndCacheMemoryConstraint(): Unit = unused()
    override suspend fun recordConstrainedInference(inputChars: Int): Unit = unused()
    override fun isLargeContext(inputChars: Int): Boolean = unused()
    override val isGenerating: Boolean get() = unused()
    override fun beginInference(): Unit = unused()
    override fun endInference(): Unit = unused()
}

/**
 * The reader's availability as the first run would give it: [status], which a test sets, asked
 * [asks] times; [changed] is what it emits when it may have changed ([ReaderAvailability.changes]).
 */
internal class HeldReaderAvailability(@Volatile var status: ReaderStatus = ReaderStatus.NOT_COMING) : ReaderAvailability {
    val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var asks = 0

    override suspend fun status(): ReaderStatus {
        asks++
        return status
    }

    override val changes: Flow<Unit> = changed
}

/** Holds [settings] in memory. */
internal class HeldSettings(settings: AppSettings = AppSettings()) : SettingsRepository {
    val settings = MutableStateFlow(settings)
    val languageWrites = java.util.concurrent.CopyOnWriteArrayList<RecordingLanguage>()

    override fun getSettings(): Flow<AppSettings> = settings

    override suspend fun updateSettings(settings: AppSettings) {
        this.settings.value = settings
    }

    override suspend fun setHomeLanguage(language: RecordingLanguage) {
        languageWrites += language
        this.settings.value = this.settings.value.copy(homeLanguage = language)
    }

    override suspend fun setHomeCategoryId(id: String): Unit = unused()
    override suspend fun setMemoryConstrainedDetected(detected: Boolean): Unit = unused()
    override suspend fun lowerConservativeThreadsCharThreshold(chars: Int): Unit = unused()
    override suspend fun clearLlmModelPath(): Unit = unused()
}

/** The audio of the recordings in [durations], each as long as it says. */
internal class HeardAudio(private val durations: Map<String, Long> = emptyMap()) : RecordingAudioRepository {
    override suspend fun find(sessionId: String): RecordingAudio? =
        durations[sessionId]?.let { RecordingAudio(sessionId, "$sessionId.wav", durationMs = it) }

    override suspend fun hasAudio(sessionId: String): Boolean = unused()
    override suspend fun replaceLines(sessionId: String, lines: Map<String, AudioSpan>): Unit = unused()
    override suspend fun setKept(sessionId: String, kept: Boolean): Unit = unused()
    override suspend fun delete(sessionId: String): Unit = unused()
    override suspend fun deleteAll(): Unit = unused()
}

/**
 * The stored lengths of the recordings, which is most of what the finisher reads and writes of
 * them; their [notes], which it reads once a recording is finished; the [languages] of those not
 * in English or German, which decide whether the better transcript loads a model; and the
 * [lines] of a capture that was cut off, which say whether it had words.
 */
internal class StoredLengths(vararg lengths: Pair<String, Long>) : TranscriptionRepository {
    val lengths = mutableMapOf(*lengths)
    val notes = mutableMapOf<String, List<LlmInsight>>()
    val languages = mutableMapOf<String, RecordingLanguage>()
    val lines = mutableMapOf<String, List<String>>()

    /** The failures counted against each recording's backfill of readings (noteBackfillFailed). */
    val backfillFailures = mutableMapOf<String, Int>()

    override suspend fun noteBackfillFailed(sessionId: String, failures: Int): Result<Unit> {
        backfillFailures.merge(sessionId, failures, Int::plus)
        return Result.success(Unit)
    }

    /** Whether each recording is due to be made again over its readings, as last stored (noteRedoDue). */
    val redoDue = mutableMapOf<String, Boolean>()

    override suspend fun noteRedoDue(sessionId: String, due: Boolean): Result<Unit> {
        redoDue[sessionId] = due
        return Result.success(Unit)
    }

    override fun getSession(sessionId: String): Flow<TranscriptionSession?> = flowOf(
        if (sessionId in lengths || sessionId in languages) {
            TranscriptionSession(
                sessionId, null, 0L, 0L,
                durationMs = lengths[sessionId] ?: 0L,
                language = languages[sessionId] ?: RecordingLanguage.ENGLISH_OR_GERMAN
            )
        } else {
            null
        }
    )

    override suspend fun updateSessionDuration(sessionId: String, durationMs: Long): Result<Unit> {
        lengths[sessionId] = durationMs
        return Result.success(Unit)
    }

    override suspend fun createSession(
        name: String?,
        mode: RecordingMode,
        outputLanguage: String?,
        insightStrategy: InsightStrategy,
        topic: String?,
        categoryId: String?,
        zoneId: String?,
        language: RecordingLanguage
    ): Result<TranscriptionSession> = unused()
    override fun getAllSessions(): Flow<List<SessionListItem>> = unused()
    override fun getSessionWithDetails(sessionId: String): Flow<SessionWithDetails?> = unused()
    override suspend fun deleteSession(sessionId: String): Result<Unit> = unused()
    override suspend fun renameSession(sessionId: String, newName: String?): Result<Unit> = unused()
    override suspend fun nameSessionIfUnnamed(sessionId: String, name: String): Result<Boolean> = unused()
    override suspend fun replaceSessionName(sessionId: String, expected: String, name: String): Result<Boolean> = unused()
    override suspend fun setOutputLanguageIfUnset(sessionId: String, language: String): Result<Boolean> = unused()
    override suspend fun setPlace(sessionId: String, place: RecordingPlace?): Result<Unit> = unused()
    override suspend fun setTranscriptModel(sessionId: String, modelId: String?): Result<Unit> = unused()
    override suspend fun deleteAllSessions(): Result<Unit> = unused()
    override suspend fun saveSegment(segment: TranscriptionSegment): Result<Unit> = unused()
    override suspend fun updateSegmentText(segmentId: String, newText: String): Result<Unit> = unused()
    override fun getSegmentsBySession(sessionId: String): Flow<List<TranscriptionSegment>> = flowOf(
        lines[sessionId].orEmpty().mapIndexed { index, text ->
            TranscriptionSegment(id = "$sessionId-$index", sessionId = sessionId, text = text, timestamp = index * 1_000L, isComplete = true)
        }
    )
    override suspend fun replaceSegments(
        sessionId: String,
        segments: List<TranscriptionSegment>,
        insightSources: Map<String, List<String>>,
        todoSources: Map<String, List<String>>,
        linkSources: Map<String, List<String>>
    ): Result<Unit> = unused()
    override suspend fun getLinkSources(sessionId: String): Map<String, List<String>> = unused()
    override suspend fun saveInsight(insight: LlmInsight): Result<Unit> = unused()
    override suspend fun replaceInsights(sessionId: String, insights: List<LlmInsight>, strategy: InsightStrategy): Result<Unit> = unused()
    override suspend fun updateInsight(insightId: String, newTitle: String?, newContent: String, newTasks: String?): Result<Unit> = unused()
    override fun getInsightsBySession(sessionId: String): Flow<List<LlmInsight>> = flowOf(notes[sessionId].orEmpty())
    override fun searchTranscriptions(query: String): Flow<List<SearchResult>> = unused()
}

/**
 * FinishRecordingUseCase as the finisher sees it: each step it is due for, in order, after the
 * finisher's hook, logged as "<session> <Step>" with [now] in [times]. [holds] keep a step of a
 * recording waiting until completed (a cancel ends the wait); [loads] do so as a model load does,
 * which a cancel can't cut short. A step in [transcriptRuns] hands out a better transcript's run
 * as it begins. With [deferWithoutRoom] the Notes step defers while a capture is live, as when
 * Gemma 4 E2B doesn't fit beside it. A recording in [modelSteps] runs it at its Notes step, as its
 * first AI step chooses the model there; with [deferOnOpening] that step then defers if a capture
 * is live, as the use case does for one that went live during the wait for the speech model. The
 * next runs of a recording in [failing] throw as they begin, as a read of a recording that fails
 * does, as many as it says. A recording in [tooLittle] has too little text for its notes: its
 * Notes step says so and doesn't begin, as the use case's doesn't, and the time it said so is in
 * [times] under "<session> too little". With [noModelNeeded] a run says, as it ends, that no AI
 * step had anything to do. A run with the AI model on its way and no AI (the request's
 * modelPending) stops as its first AI step would begin, before the hook, as the use case's does:
 * at Notes, or after them when it had too little text for notes. A recording in [discarded] had
 * nothing said in it, and its run deletes it before any step.
 *
 * A recording in [reads] has lines only the reader reads, and its runs come to the Reading step
 * (with the AI, or its model on its way), as the use case's do; the others skip it. Without the
 * reader's files ([readerMissing]) the run stops there before the hook: for the reader while it is
 * on its way (the request's readerPending), else without the AI steps ([FinishRecordingUseCase.Outcome.Unread]).
 * After the hook a live capture defers it; [heldOff] runs are held off; then it hands out its
 * reading (onReadingStart) and reads, until [readingHolds] completes, and a stop of the reading
 * defers the run, as a capture that opens does ([stoppedReadings]).
 */
internal class ScriptedFinish(private val gate: AiWorkGate, private val now: () -> Long) {
    val events = mutableListOf<String>()
    val times = mutableMapOf<String, Long>()
    val requests = mutableListOf<FinishRecordingUseCase.Request>()
    val gateHolders = mutableSetOf<String?>()
    val holds = mutableMapOf<Pair<String, FinishStep>, CompletableDeferred<Unit>>()
    val loads = mutableMapOf<Pair<String, FinishStep>, CompletableDeferred<Unit>>()
    val transcriptRuns = mutableMapOf<String, BetterTranscriptRun>()
    val failing = mutableMapOf<String, Int>()
    val modelSteps = mutableMapOf<String, suspend (FinishRecordingUseCase.Progress) -> Unit>()
    val tooLittle = mutableMapOf<String, FinishRecordingUseCase.TooLittleText>()
    val discarded = mutableSetOf<String>()
    var deferWithoutRoom = false
    var deferOnOpening = false
    var noModelNeeded = false
    val reads = mutableSetOf<String>()
    val readerMissing = mutableSetOf<String>()
    val heldOff = mutableMapOf<String, Int>()
    val readingHolds = mutableMapOf<String, CompletableDeferred<Unit>>()
    val stoppedReadings = mutableListOf<String>()

    /** Recordings whose reader fails as it reads ([FinishRecordingUseCase.Outcome.Unread.Why.NOT_READ]). */
    val readerFails = mutableSetOf<String>()

    /** Recordings whose runs end with their notes failed ([FinishRecordingUseCase.Outcome.NotesFailed]), every step run. */
    val notesFail = mutableSetOf<String>()

    /**
     * Run as the reading of a recording is about to be handed out (onReadingStart), after the
     * step's own look for a live capture: what happens in between, a recorder that starts then.
     */
    val beforeReadingStart = mutableMapOf<String, suspend () -> Unit>()

    /**
     * Recordings in [reads] whose lines were never read: a run that goes on past their Reading step
     * finds them so ([FinishRecordingUseCase.Outcome.Unread.Why.SKIPPED]), as the use case's does.
     */
    val neverRead = mutableSetOf<String>()

    /** What a recording's reading came to, said once it has read (onReading); one line kept by default. */
    val readingCounts = mutableMapOf<String, ReadingStep.Counts>()

    /** How often more the run of a recording ends at its Notes step, the phone's memory having stopped the model's load. */
    val loadStops = mutableMapOf<String, Int>()

    /**
     * What the run of a recording throws as a step begins, as what the use case passes on would
     * (an error that is no step's alone, or one thrown between steps): the next of the list, one
     * a run; [throwsAlways] in every run.
     */
    val throwsAt = mutableMapOf<Pair<String, FinishStep>, MutableList<Throwable>>()
    val throwsAlways = mutableMapOf<Pair<String, FinishStep>, () -> Throwable>()

    suspend fun run(request: FinishRecordingUseCase.Request, progress: FinishRecordingUseCase.Progress): FinishRecordingUseCase.Outcome {
        requests += request
        val id = request.sessionId
        val failures = failing[id] ?: 0
        if (failures > 0) {
            failing[id] = failures - 1
            throw IllegalStateException("the recording could not be read")
        }
        if (id in discarded) return FinishRecordingUseCase.Outcome.Discarded
        val from = request.resumeFrom
        if (id in reads && id in neverRead && from != null && from > FinishStep.Reading) {
            return FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.SKIPPED)
        }
        for (step in FinishStep.entries) {
            if (from != null && step < from) continue
            if (step == FinishStep.Reading) {
                if (id !in reads || (!request.aiAvailable && !request.modelPending)) continue
                read(request, progress)?.let { return it }
                continue
            }
            if (step == FinishStep.Notes) {
                val found = tooLittle[id]
                if (found != null) {
                    times["$id too little"] = now()
                    progress.onTooLittleText(found)
                    continue
                }
            }
            if (step >= FinishStep.Notes && !request.aiAvailable && request.modelPending) {
                return FinishRecordingUseCase.Outcome.WaitsForModel(step)
            }
            if (!progress.beforeStep(step)) return FinishRecordingUseCase.Outcome.Deferred(step)
            throwsAlways[id to step]?.let { throw it() }
            throwsAt[id to step]?.removeFirstOrNull()?.let { throw it }
            if (step == FinishStep.Notes && (loadStops[id] ?: 0) > 0) {
                loadStops[id] = loadStops.getValue(id) - 1
                times["$id stopped"] = now()
                return FinishRecordingUseCase.Outcome.LoadStopped(step)
            }
            if (step == FinishStep.Notes && deferWithoutRoom && progress.captureLive()) {
                return FinishRecordingUseCase.Outcome.Deferred(step, NotEnoughMemoryException(3_000L * MB, 3_700L * MB))
            }
            if (step == FinishStep.Transcript) transcriptRuns[id]?.let(progress.onTranscriptStart)
            if (step == FinishStep.Notes) modelSteps[id]?.invoke(progress)
            if (step == FinishStep.Notes && deferOnOpening && progress.captureLive()) {
                return FinishRecordingUseCase.Outcome.Deferred(step, captureOpening = true)
            }
            events += "$id $step"
            times["$id $step"] = now()
            gateHolders += gate.holder.value
            holds[id to step]?.await()
            loads[id to step]?.let { load -> withContext(NonCancellable) { load.await() } }
            // As the use case does after each step: a cancel ends the run before the next one.
            currentCoroutineContext().ensureActive()
        }
        if (noModelNeeded) progress.onNoModelNeeded()
        return if (id in notesFail) FinishRecordingUseCase.Outcome.NotesFailed else FinishRecordingUseCase.Outcome.Finished
    }

    /** The Reading step of a recording in [reads], as the use case runs it; null once it has read. */
    private suspend fun read(request: FinishRecordingUseCase.Request, progress: FinishRecordingUseCase.Progress): FinishRecordingUseCase.Outcome? {
        val id = request.sessionId
        val step = FinishStep.Reading
        if (id in readerMissing) {
            return if (request.readerPending) {
                FinishRecordingUseCase.Outcome.WaitsForModel(step, reader = true)
            } else {
                FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NO_READER)
            }
        }
        if (!progress.beforeStep(step)) return FinishRecordingUseCase.Outcome.Deferred(step)
        if (progress.captureLive()) return FinishRecordingUseCase.Outcome.Deferred(step, captureOpening = true)
        val heldOffTimes = heldOff[id] ?: 0
        if (heldOffTimes > 0) {
            heldOff[id] = heldOffTimes - 1
            return FinishRecordingUseCase.Outcome.ReaderHeldOff(step, ReaderNotLoadedException.Why.SPEECH_MODEL)
        }
        if (id in readerFails) {
            events += "$id $step"
            return FinishRecordingUseCase.Outcome.Unread(FinishRecordingUseCase.Outcome.Unread.Why.NOT_READ)
        }
        val stopped = CompletableDeferred<Unit>()
        beforeReadingStart[id]?.invoke()
        progress.onReadingStart(ReadingRun { stopped.complete(Unit) })
        events += "$id $step"
        times["$id $step"] = now()
        gateHolders += gate.holder.value
        readingHolds[id]?.let { hold ->
            select<Unit> {
                hold.onAwait {}
                stopped.onAwait {}
            }
        }
        if (stopped.isCompleted) {
            stoppedReadings += id
            return FinishRecordingUseCase.Outcome.Deferred(step, captureOpening = true)
        }
        progress.onReading(readingCounts[id] ?: ReadingStep.Counts(lines = 1, kept = 1))
        currentCoroutineContext().ensureActive()
        return null
    }

    private companion object {
        const val MB = 1_048_576L
    }
}

/** The field log, as lines. */
internal class RecordedLog : FinisherLog {
    val lines = mutableListOf<String>()

    override fun info(message: String) {
        lines += message
    }

    override fun warn(message: String, cause: Throwable?) {
        lines += "W $message"
    }
}

private fun unused(): Nothing = error("not used by these tests")
