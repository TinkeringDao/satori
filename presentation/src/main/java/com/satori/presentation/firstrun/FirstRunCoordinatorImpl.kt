package com.satori.presentation.firstrun

import android.os.SystemClock
import android.util.Log
import com.satori.data.firstrun.FirstRunStore
import com.satori.data.firstrun.StoredFirstRun
import com.satori.data.service.DownloadServiceRules
import com.satori.domain.firstrun.FilesCheck
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunDownloads
import com.satori.domain.firstrun.FirstRunEvent
import com.satori.domain.firstrun.FirstRunFiles
import com.satori.domain.firstrun.FirstRunFlow
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunProgress
import com.satori.domain.firstrun.FirstRunQueue
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.FirstRunStage
import com.satori.domain.firstrun.NetworkFacts
import com.satori.domain.firstrun.NetworkPolicy
import com.satori.domain.firstrun.NetworkWatch
import com.satori.domain.firstrun.QueueFacts
import com.satori.domain.firstrun.QueueStep
import com.satori.domain.firstrun.SpaceShortfall
import com.satori.domain.firstrun.SpeechFiles
import com.satori.domain.firstrun.TimeLeft
import com.satori.domain.model.DownloadFailure
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.PauseReason
import com.satori.domain.repository.SettingsRepository
import com.satori.presentation.main.ActiveRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first run's coordinator, the one writer of its stage and plan, for the whole app. It is
 * created as the app starts, which reads the stored stage synchronously, and started at every
 * start ([start]).
 *
 * Every event is handled on one thread, in the order it came: a request from a screen, a
 * download's state, the network, a recorder, the settings, a timer. Each one gathers the facts,
 * asks the queue what to do now ([FirstRunQueue]) and does it through [downloads]; the stage and
 * the plan are stored before the snapshot that shows them is published ([state]). What it does
 * besides:
 * - it holds every model download while a recorder starts, records or saves, and lets them go on
 *   [RESUME_AFTER_RECORDING_MS] after;
 * - it tries again after a failed connect (5, 10, 30 s, then each minute), at once when Android
 *   reports a network, and after a server error in a few minutes; two failed connects in a row on
 *   a validated network ([NetworkPolicy.BLOCKED_AFTER]), or a socket Android refuses, read as
 *   blocked, which waits for Try again or another network;
 * - while a part is held or failed it follows the model folder and the free space every
 *   [POLL_MS], so files put there by hand count as downloaded;
 * - a download of one of its parts that another screen started (Settings' "Get the AI model") is
 *   left to run: a tap that showed the size;
 * - the reader (0.8.0, design R7): an install that has the Malayalam model, AI notes on and no
 *   reader gets its part, at the start and as the Malayalam model lands, by the first run or by
 *   Settings ([planReaderIfDue]). Its files count as there once whole and found with their pinned
 *   SHA-256, which is read once for files that came another way than its download, never while a
 *   recorder is busy ([FirstRunFiles.verify]).
 *
 * Logs, under SatoriFirstRun: stages, parts, states, reasons, bytes in MB and times in ms only.
 */
@Singleton
class FirstRunCoordinatorImpl internal constructor(
    private val store: FirstRunStore,
    private val files: FirstRunFiles,
    private val downloads: FirstRunDownloads,
    private val network: NetworkWatch,
    private val recorder: ActiveRecorder,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    // The field log's lines; logcat in the app, a list in the tests.
    private val logLine: (String) -> Unit = { Log.i(TAG, it) }
) : FirstRunCoordinator {

    @Inject
    constructor(
        store: FirstRunStore,
        files: FirstRunFiles,
        downloads: FirstRunDownloads,
        network: NetworkWatch,
        recorder: ActiveRecorder,
        settings: SettingsRepository
    ) : this(
        store, files, downloads, network, recorder, settings,
        CoroutineScope(SupervisorJob() + serialDispatcher()), SystemClock::elapsedRealtime, System::currentTimeMillis
    )

    // All of what follows is read and changed on the scope's one thread, once the constructor is done.
    private var stored: StoredFirstRun
    private val plan: FirstRunPlan get() = stored.plan ?: NO_PLAN
    private var facts: NetworkFacts = network.facts.value
    private var aiNotesOn = true

    // Whether the Malayalam speech model is on the phone, checked: read at the start and each time a
    // download of it ends, the first run's or Settings'; and the state of that download last seen.
    private var malayalamIn = false
    private var malayalamDownload: DownloadState = DownloadState.Idle

    // The queue: the part it works on, what it does with it, and whose download this started.
    private var current: FirstRunPart? = null
    private var step: QueueStep = QueueStep.Idle
    private var startedHere: FirstRunPart? = null

    // Failures: a refused connection, failed connects in a row without a byte between them, the
    // retry that is due, and the errors already counted.
    private var blocked = false
    private var connectFailures = 0
    private var retryWait: PauseReason? = null
    private var retryJob: Job? = null
    private val counted = mutableMapOf<FirstRunPart, DownloadState.Error>()

    // How often in this process the reader's files were found whole with their pinned SHA-256
    // (FirstRunSnapshot.readerFound).
    private var readerFound = 0

    // The bytes of each part on the phone as last known, the space as last read.
    private val onPhone = mutableMapOf<FirstRunPart, Long>()
    private var shortfall: SpaceShortfall? = null

    // The run of the part under way: its time left, and where it went on from after the app was closed.
    private var timeLeft: TimeLeft? = null
    private var timeLeftTotal = 0L
    private var minutesLeft: Int? = null
    private var resumedFrom: Long? = null

    // A part of the setup is in while the setup downloads: Getting ready's one bar goes on with
    // the next part, and so does its time left, which counts the setup's bytes together.
    private var barGoesOn = false

    // What made the next start of a part go on, for the field log: the app's start (which also
    // says "Resuming from"), a tap, a network that came, a retry that was due. The last one counts.
    private var resumeSource: String? = null

    // The field log's last progress line of each part: a tenth of it, when, at how many bytes.
    private val logged = mutableMapOf<FirstRunPart, ProgressMark>()

    // Whether start() has read the settings and the disk: no decision is taken before.
    private var launched = false
    private var observing = false
    private var ticker: Job? = null

    private val _state: MutableStateFlow<FirstRunSnapshot>
    override val state: StateFlow<FirstRunSnapshot>

    init {
        var read = store.read(files::bytes)
        // Synchronous, with the quick look at the speech files that the quick key's answer needs:
        // files gone after the setup start it over. Their check comes in start().
        val atStart = FirstRunFlow.atLaunch(read.stage, files.speech(check = false), setupDone = false)
        if (atStart != read.stage) read = startedOver(read)
        stored = read
        plan.items.filter { it.status == FirstRunPlan.Status.PENDING }.forEach { onPhone[it.part] = files.bytesOnPhone(it.part) }
        _state = MutableStateFlow(snapshot())
        state = _state.asStateFlow()
    }

    override fun start() {
        scope.launch {
            aiNotesOn = settings.getSettings().first().llmEnabled
            lookAtDisk()
            planReaderIfDue()
            launched = true
            resumeSource = SOURCE_APP_START
            downloads.keepInForeground()
            // Only now: what it follows decides nothing before the settings and the disk are read.
            if (!observing) {
                observing = true
                observe()
            }
            reconcile()
        }
    }

    override fun download(malayalam: Boolean, useMobileData: Boolean) {
        scope.launch {
            if (stored.stage != FirstRunStage.GET_READY) return@launch
            aiNotesOn = settings.getSettings().first().llmEnabled
            val now = wallClock()
            val stage = FirstRunFlow.next(FirstRunStage.GET_READY, FirstRunEvent.DOWNLOAD_TAPPED)
            update {
                it.copy(
                    stage = stage, stageAtMs = now, malayalamWanted = malayalam, consentAtMs = now,
                    mobileOkForSetup = useMobileData, userPaused = false, plan = withFilesOnPhone(initialPlan(malayalam))
                )
            }
            log("stage=$stage from=${FirstRunStage.GET_READY} malayalam=$malayalam mobile=$useMobileData")
            freshTry()
            reconcile()
        }
    }

    override fun pause() {
        scope.launch {
            if (stored.stage != FirstRunStage.DOWNLOADING || stored.userPaused) return@launch
            update { it.copy(userPaused = true) }
            reconcile()
        }
    }

    override fun resume() {
        scope.launch {
            if (!stored.userPaused) return@launch
            update { it.copy(userPaused = false) }
            resumeSource = SOURCE_USER
            log("resume source=user")
            reconcile()
        }
    }

    override fun retry(part: FirstRunPart) {
        scope.launch {
            if (plan.item(part) == null) return@launch
            freshTry()
            update { it.copy(plan = plan.retried(part)) }
            resumeSource = SOURCE_USER
            log("resume part=${part.logName} source=try_again")
            reconcile()
        }
    }

    override fun useMobileData(part: FirstRunPart) {
        scope.launch {
            update {
                if (part.beforeFirstRecording) it.copy(mobileOkForSetup = true) else it.copy(mobileOkParts = it.mobileOkParts + part)
            }
            resumeSource = SOURCE_USER
            log("mobile_data part=${part.logName}")
            reconcile()
        }
    }

    override fun lookAround() {
        scope.launch {
            if (stored.stage != FirstRunStage.READY || stored.lookedAround) return@launch
            update { it.copy(lookedAround = true) }
            log("looked_around")
            publish()
        }
    }

    override fun recordingSaved() {
        scope.launch {
            // Only once speech recognition is in: a recording can't be made before, so one said to
            // be saved then is a capture the app's death cut off before a reset, and moves nothing.
            if (stored.stage < FirstRunStage.READY) {
                log("recording_saved ignored stage=${stored.stage}")
                return@launch
            }
            if (!stored.firstRecordingSaved) {
                val from = stored.stage
                val since = stored.stageAtMs
                val stage = FirstRunFlow.next(from, FirstRunEvent.RECORDING_SAVED)
                val now = wallClock()
                update { it.copy(firstRecordingSaved = true, stage = stage, stageAtMs = if (stage != from) now else it.stageAtMs) }
                if (stage != from) log("stage=$stage from=$from ms=${msSince(since, now)}")
            }
            reconcile()
        }
    }

    override fun getBiggerModel() {
        scope.launch {
            if (plan.item(FirstRunPart.BIGGER_AI_MODEL) != null) return@launch
            update { it.copy(plan = plan.withBiggerModel(files.bytes(FirstRunPart.BIGGER_AI_MODEL))) }
            log("bigger_model planned")
            reconcile()
        }
    }

    // ── What it follows ──────────────────────────────────────────────────────

    private fun observe() {
        scope.launch { downloads.changes.collect { onDownloads() } }
        scope.launch { network.facts.collect(::onNetwork) }
        scope.launch {
            settings.getSettings().map { it.llmEnabled }.distinctUntilChanged().collect { on ->
                aiNotesOn = on
                planReaderIfDue()
                reconcile()
            }
        }
        // A recorder's gate for every model download, the first run's and Settings'.
        scope.launch {
            // Whether the gate holds the downloads now: no recorder is busy as the app starts.
            var holding = false
            recorder.current.map { it != null && it.activity in BUSY }.distinctUntilChanged().collectLatest { busy ->
                // The first run's part that the gate holds or lets go of, for the field log (design 6.6).
                val held = current?.takeIf { part ->
                    val state = downloads.state(part)
                    DownloadServiceRules.isRunning(state) || (state as? DownloadState.Paused)?.reason == PauseReason.RECORDING
                }
                if (busy) {
                    downloads.holdForRecording(true)
                    if (!holding) held?.let { log("part=${it.logName} state=PAUSED reason=${PauseReason.RECORDING}") }
                    holding = true
                } else {
                    delay(RESUME_AFTER_RECORDING_MS)
                    downloads.holdForRecording(false)
                    if (holding) held?.let { log("resume part=${it.logName} source=recording_saved") }
                    holding = false
                }
            }
        }
    }

    /** A download's state changed: what finished is checked, what failed is counted, what runs is measured. */
    private fun onDownloads() {
        malayalamLanded()
        for (item in plan.items) {
            if (item.status != FirstRunPlan.Status.PENDING) continue
            when (val state = downloads.state(item.part)) {
                DownloadState.Completed -> finished(item.part)
                is DownloadState.Error -> if (counted[item.part] !== state) {
                    counted[item.part] = state
                    failed(item.part, state.reason)
                }
                is DownloadState.Downloading -> progressed(item.part, state.progress, verifying = false)
                is DownloadState.Verifying -> progressed(item.part, state.progress, verifying = true)
                DownloadState.Idle, is DownloadState.Paused -> Unit
            }
        }
        reconcile()
    }

    private fun onNetwork(now: NetworkFacts) {
        if (now == facts) return
        facts = now
        if (now.connected) {
            // A network came, or changed: what failed may work now, so it is tried at once.
            val held = step is QueueStep.Hold
            blocked = false
            connectFailures = 0
            if (retryWait == PauseReason.NO_NETWORK) clearRetry()
            if (held) {
                resumeSource = SOURCE_NETWORK
                log("resume source=network" + (current?.let { " part=${it.logName} bytes=${mb(onPhone[it] ?: 0L)} MB" } ?: ""))
            }
        }
        reconcile()
    }

    // ── What it does ─────────────────────────────────────────────────────────

    /** Asks the queue what to do now, and does it; then publishes the snapshot. */
    private fun reconcile() {
        if (!launched) {
            publish()
            return
        }
        if (stored.stage == FirstRunStage.DOWNLOADING && plan.setup.isNotEmpty() && plan.setupDone) moveTo(FirstRunStage.READY)
        // DONE is the first recording saved, also for an install that finished the earlier onboarding.
        val firstRecordingSaved = stored.firstRecordingSaved || stored.stage == FirstRunStage.DONE
        val item = FirstRunQueue.next(plan, stored.stage, firstRecordingSaved, aiNotesOn)
        if (item?.part != current) {
            current?.let(::release)
            current = item?.part
            startedHere = startedHere?.takeIf { it == current }
            newRun()
        }
        if (item == null) {
            step = QueueStep.Idle
            stopTicker()
            publish()
            return
        }
        ensureTicker()
        val part = item.part
        val state = downloads.state(part)
        val running = DownloadServiceRules.isRunning(state)
        val gated = state is DownloadState.Paused && state.reason == PauseReason.RECORDING
        if ((running || gated) && startedHere != part) {
            // Started by another screen with a tap that showed the size: it runs as it is.
            step = QueueStep.Run(part)
            publish()
            return
        }
        if (!running && !gated) {
            if (downloads.busyWithOther(part)) {
                // Settings downloads another model in its place: it waits for that one to end.
                step = QueueStep.Idle
                publish()
                return
            }
            measureSpace(item)
        }
        val next = FirstRunQueue.step(
            item,
            QueueFacts(
                userPaused = stored.userPaused && stored.stage == FirstRunStage.DOWNLOADING,
                network = facts,
                mobileDataAllowed = NetworkPolicy.mayUseMobileData(part, stored.mobileOkForSetup, stored.mobileOkParts),
                spaceShort = !running && !gated && shortfall != null,
                blocked = blocked,
                retryWait = retryWait
            )
        )
        step = next
        // Held after the disk filled up, though the space looked enough: still what it is short of.
        if (next is QueueStep.Hold && next.reason == PauseReason.NOT_ENOUGH_STORAGE && shortfall == null) shortfall = spaceNow(part)
        when (next) {
            is QueueStep.Run -> if (!running && !gated) {
                // Put there meanwhile, by hand or by Settings (another AI model that writes the
                // notes, say): nothing to download.
                if (onPhoneChecked(part)) {
                    update { it.copy(plan = plan.done(part)) }
                    logFound(part)
                    reconcile()
                    return
                }
                startRun(part)
            }
            is QueueStep.Hold -> if ((state as? DownloadState.Paused)?.reason != next.reason) hold(part, next.reason)
            is QueueStep.WaitsForTap -> if (running || gated) downloads.stop(part)
            QueueStep.Idle -> Unit
        }
        publish()
    }

    /**
     * The queue moved off [part]: a download of it this started stops, and a hold this wrote
     * leaves its place, as it would keep the download service; one another screen started goes on.
     */
    private fun release(part: FirstRunPart) {
        val state = downloads.state(part)
        val held = state is DownloadState.Paused && state.reason != PauseReason.RECORDING
        val ours = part == startedHere && (DownloadServiceRules.isRunning(state) || state is DownloadState.Paused)
        if (held || ours) downloads.stop(part)
    }

    private fun startRun(part: FirstRunPart) {
        val bytes = files.bytesOnPhone(part)
        onPhone[part] = bytes
        downloads.start(part)
        startedHere = part
        newRun()
        barGoesOn = false
        val source = resumeSource
        resumeSource = null
        if (source != null && bytes > 0L) {
            // "Resuming from 412 MB" is for a download that went on after the app was closed.
            if (source == SOURCE_APP_START) resumedFrom = barBytes(part, bytes)
            log("resume part=${part.logName} bytes=${mb(bytes)} MB source=$source")
        }
        if (part == FirstRunPart.AI_MODEL && !stored.aiDownloadStarted) update { it.copy(aiDownloadStarted = true) }
        val total = plan.item(part)?.bytes ?: 0L
        log("part=${part.logName} state=RUNNING bytes=${mb(bytes)}/${mb(total)} MB")
        // The next progress line comes at the next tenth, with the rate from here.
        logged[part] = ProgressMark(if (total > 0L) (bytes * 10 / total).toInt() else 0, clock(), bytes, verifying = false)
    }

    private fun hold(part: FirstRunPart, reason: PauseReason) {
        val bytes = files.bytesOnPhone(part)
        onPhone[part] = bytes
        val total = plan.item(part)?.bytes ?: 0L
        downloads.hold(part, reason, DownloadProgress(bytes, total, percentOf(bytes, total)))
        if (startedHere == part) startedHere = null
        barGoesOn = false
        newRun()
        log("part=${part.logName} state=PAUSED reason=$reason")
    }

    private fun progressed(part: FirstRunPart, progress: DownloadProgress, verifying: Boolean) {
        // The first state of a download that starts is a placeholder, without a size.
        if (progress.totalBytes <= 0L) return
        // An AI model the registry knows only roughly: its download says the size.
        val item = plan.item(part)
        if (part.aiModel && item != null && item.bytes != progress.totalBytes) update { it.copy(plan = plan.sized(part, progress.totalBytes)) }
        val before = onPhone[part] ?: 0L
        onPhone[part] = progress.bytesDownloaded
        // Bytes came: the connection works, whatever failed before.
        if (progress.bytesDownloaded > before) connectFailures = 0
        logProgress(part, progress, verifying)
        estimate(part)
    }

    /**
     * The field log's progress: a line as a file's check starts, and one at every tenth of the
     * part, with the rate since the last one, but none once every byte is in: the part's last check
     * is said, and its end comes next. MB, ms and kbit/s only.
     */
    private fun logProgress(part: FirstRunPart, progress: DownloadProgress, verifying: Boolean) {
        val now = clock()
        val mark = logged[part]
        val tenth = (progress.bytesDownloaded * 10 / progress.totalBytes).toInt()
        val bytes = "bytes=${mb(progress.bytesDownloaded)}/${mb(progress.totalBytes)} MB"
        when {
            verifying -> if (mark?.verifying != true) {
                log("part=${part.logName} state=VERIFYING $bytes")
                logged[part] = (mark ?: ProgressMark(tenth, now, progress.bytesDownloaded, verifying = false)).copy(verifying = true)
            }
            (mark == null || tenth > mark.tenth) && progress.bytesDownloaded < progress.totalBytes -> {
                val rate = mark?.takeIf { now > it.ms }?.let { " rate_kbps=${(progress.bytesDownloaded - it.bytes) * 8 / (now - it.ms)}" }
                log("part=${part.logName} state=RUNNING $bytes" + rate.orEmpty())
                logged[part] = ProgressMark(tenth, now, progress.bytesDownloaded, verifying = false)
            }
            mark != null && mark.verifying -> logged[part] = mark.copy(verifying = false)
        }
    }

    /**
     * [part]'s download finished: its files are checked, or it counts as damaged and starts over. A
     * check that has to wait for a recorder (the reader's SHA-256 of files the download kept)
     * leaves the part pending, its download stopped: the queue's next look checks it again, and a
     * download of what is whole fetches nothing.
     */
    private fun finished(part: FirstRunPart) {
        val check = files.verify(part, ::recorderBusy)
        if (check == FilesCheck.NOT_NOW) {
            downloads.stop(part)
            log("part=${part.logName} state=VERIFYING reason=${PauseReason.RECORDING}")
            return
        }
        if (check == FilesCheck.PASSED) {
            update { it.copy(plan = plan.done(part)) }
            if (part == FirstRunPart.READER) readerFound++
            onPhone.remove(part)
            if (startedHere == part) startedHere = null
            connectFailures = 0
            barGoesOn = part.beforeFirstRecording && stored.stage == FirstRunStage.DOWNLOADING &&
                plan.setup.any { it.status == FirstRunPlan.Status.PENDING }
            log("part=${part.logName} state=DONE")
            return
        }
        log("part=${part.logName} state=FAILED reason=check")
        // The files go, so the next download fetches them anew, and the finished state with them.
        files.discard(part)
        downloads.stop(part)
        failed(part, DownloadFailure.OTHER)
    }

    private fun failed(part: FirstRunPart, reason: DownloadFailure) {
        val tries = if (reason == DownloadFailure.NO_CONNECTION) connectFailures + 1 else connectFailures
        val failure = NetworkPolicy.failure(reason, facts, tries)
        update { it.copy(plan = plan.failed(part, failure)) }
        onPhone[part] = files.bytesOnPhone(part)
        if (startedHere == part) startedHere = null
        barGoesOn = false
        log("part=${part.logName} state=FAILED reason=$failure")
        when (failure) {
            DownloadFailure.NO_CONNECTION -> {
                connectFailures = tries
                waitToRetry(PauseReason.NO_NETWORK, NetworkPolicy.retryDelayMs(tries))
            }
            DownloadFailure.BLOCKED -> {
                connectFailures = tries
                blocked = true
            }
            DownloadFailure.SERVER -> waitToRetry(PauseReason.SERVER, NetworkPolicy.SERVER_RETRY_MS)
            DownloadFailure.NOT_ENOUGH_STORAGE -> waitToRetry(PauseReason.NOT_ENOUGH_STORAGE, POLL_MS)
            // Fetched again at once; after the second time in a row it waits for Try again.
            DownloadFailure.OTHER -> Unit
        }
    }

    private fun waitToRetry(reason: PauseReason, afterMs: Long) {
        clearRetry()
        retryWait = reason
        retryJob = scope.launch {
            delay(afterMs)
            retryJob = null
            retryWait = null
            resumeSource = SOURCE_RETRY
            log("resume source=retry after_ms=$afterMs")
            reconcile()
        }
    }

    private fun clearRetry() {
        retryJob?.cancel()
        retryJob = null
        retryWait = null
    }

    /** A tap that asks for a new try (Download, Try again): what failed before isn't held against it. */
    private fun freshTry() {
        blocked = false
        connectFailures = 0
        clearRetry()
    }

    /** The space for what [item] still needs, as it is now. */
    private fun measureSpace(item: FirstRunPlan.Item) {
        val free = files.freeBytes()
        val left = (item.bytes - (onPhone[item.part] ?: files.bytesOnPhone(item.part))).coerceAtLeast(0L)
        shortfall = if (FirstRunPlan.fits(free, left)) null else SpaceShortfall(FirstRunPlan.neededSpace(left), free)
    }

    // ── The disk ─────────────────────────────────────────────────────────────

    /**
     * As the app starts: the speech files' check, the plan of a setup found on the phone, the
     * parts put there by hand, and the stage those facts give ([FirstRunFlow.atLaunch]).
     */
    private fun lookAtDisk() {
        val speech = files.speech(check = true)
        malayalamIn = files.checked(FirstRunPart.MALAYALAM)
        if (stored.plan == null) {
            val found = when {
                // Download was tapped, but no plan was stored (the debug tools set the stage so).
                stored.consentAtMs > 0L -> initialPlan(stored.malayalamWanted)
                // Put there by hand on a fresh install: no switch was shown, so no Malayalam.
                stored.stage == FirstRunStage.GET_READY && speech == SpeechFiles.CHECKED -> initialPlan(malayalam = false)
                // A fresh install has none yet; one that finished the earlier onboarding plans nothing.
                else -> null
            }
            if (found != null) update { it.copy(plan = found) }
        }
        followDisk()
        val stage = FirstRunFlow.atLaunch(stored.stage, speech, plan.setupDone)
        when {
            stage == stored.stage -> Unit
            // Settings downloads speech recognition again: its files are on their way.
            stage == FirstRunStage.GET_READY && speechUnderWay() -> Unit
            stage == FirstRunStage.GET_READY -> {
                // Nothing of the plan before goes on: the downloads and holds it ran go, its failures are forgotten.
                FirstRunPart.entries.forEach(::release)
                current = null
                startedHere = null
                freshTry()
                counted.clear()
                onPhone.clear()
                stored = startedOver(stored)
            }
            else -> moveTo(stage)
        }
    }

    /** Whether a download of speech recognition runs or waits, whichever screen started it. */
    private fun speechUnderWay(): Boolean = downloads.state(FirstRunPart.SPEECH).let {
        DownloadServiceRules.isRunning(it) || it is DownloadState.Paused
    }

    /** The parts of the plan whose files are on the phone and checked count as downloaded. */
    private fun followDisk() {
        val found = withFilesOnPhone(plan)
        if (found != plan) {
            val parts = found.items.filter { it.status == FirstRunPlan.Status.DONE && plan.pending(it.part) }.map { it.part }
            parts.forEach(::logFound)
            update { it.copy(plan = found) }
            if (FirstRunPart.READER in parts) readerFound++
        }
        plan.items.filter { it.status == FirstRunPlan.Status.PENDING }.forEach { onPhone[it.part] = files.bytesOnPhone(it.part) }
    }

    /**
     * The field log's line as [part] is found on the phone, checked: its download's end, which a
     * look at the disk can see before that download's last state is handled, or files that came
     * another way (by hand, or Settings), the one case "model_folder_changed" is for.
     */
    private fun logFound(part: FirstRunPart) {
        if (downloads.state(part) == DownloadState.Completed) {
            log("part=${part.logName} state=DONE")
        } else {
            log("model_folder_changed part=${part.logName} ready=true")
        }
    }

    private fun withFilesOnPhone(plan: FirstRunPlan): FirstRunPlan = plan.items
        .filter { it.status == FirstRunPlan.Status.PENDING && onPhoneChecked(it.part) }
        .fold(plan) { found, item -> found.done(item.part) }

    /**
     * Whether [part] is on the phone, checked; for the AI model, any model that writes the notes;
     * for the reader, its files found with their pinned SHA-256, read once while no recorder is busy
     * ([FirstRunFiles.verify]).
     */
    private fun onPhoneChecked(part: FirstRunPart): Boolean = when (part) {
        FirstRunPart.AI_MODEL -> files.aiModelOnPhone() || files.checked(part)
        FirstRunPart.READER -> files.verify(part, ::recorderBusy) == FilesCheck.PASSED
        else -> files.checked(part)
    }

    /** Whether a recorder starts, records or saves now: the reader's SHA-256 is not read meanwhile. */
    private fun recorderBusy(): Boolean = recorder.current.value?.activity in BUSY

    /**
     * The plan of the setup, with Malayalam or without ([malayalam], Get ready's switch): the AI
     * model after the first recording unless AI notes are off or one is on the phone, and with
     * Malayalam and AI notes the reader after it, unless it is on the phone.
     */
    private fun initialPlan(malayalam: Boolean): FirstRunPlan = FirstRunPlan.initial(
        speechBytes = files.bytes(FirstRunPart.SPEECH),
        malayalamBytes = if (malayalam) files.bytes(FirstRunPart.MALAYALAM) else null,
        aiModelBytes = if (aiNotesOn && !files.aiModelOnPhone()) files.bytes(FirstRunPart.AI_MODEL) else null,
        readerBytes = if (malayalam && aiNotesOn && !files.checked(FirstRunPart.READER)) files.bytes(FirstRunPart.READER) else null
    )

    /**
     * The reader's part for an install that has what it reads for and lacks it (design R7): the
     * Malayalam model on the phone, AI notes on, the reader not on the phone. A plan without it gets
     * it after whatever is ahead of it, and one that had it done, whose files went since, plans it
     * again ([FirstRunPlan.withReader]); files of it on the phone already make it done at once
     * ([followDisk]), so nothing downloads. A pending reader stays as it is, and so does a dropped
     * one, but with [dropped]: the Malayalam model has just landed, a download the owner asked for,
     * and nothing else would plan a reader dropped after its Try again ever again. Nothing is planned
     * before Download was tapped, which plans it with the rest.
     */
    private fun planReaderIfDue(dropped: Boolean = false) {
        if (stored.stage == FirstRunStage.GET_READY || !aiNotesOn || !malayalamIn) return
        val item = plan.item(FirstRunPart.READER)
        when (item?.status) {
            null -> Unit
            FirstRunPlan.Status.DONE -> if (files.checked(FirstRunPart.READER)) return
            FirstRunPlan.Status.PENDING -> return
            FirstRunPlan.Status.DROPPED -> if (!dropped) return
        }
        update { it.copy(plan = plan.withReader(files.bytes(FirstRunPart.READER), dropped)) }
        log("part=${FirstRunPart.READER.logName} planned" + if (item == null) "" else " again")
        followDisk()
    }

    /**
     * A download of the Malayalam model that ended, the first run's or Settings': whether it is on
     * the phone now is read again, and an install that got it plans the reader ([planReaderIfDue]),
     * also one dropped before.
     */
    private fun malayalamLanded() {
        val state = downloads.state(FirstRunPart.MALAYALAM)
        val landed = state == DownloadState.Completed && malayalamDownload != DownloadState.Completed
        malayalamDownload = state
        if (!landed) return
        malayalamIn = files.checked(FirstRunPart.MALAYALAM)
        if (launched) planReaderIfDue(dropped = true)
    }

    // ── Stage and store ──────────────────────────────────────────────────────

    private fun moveTo(stage: FirstRunStage) {
        val from = stored.stage
        if (stage == from) return
        val since = stored.stageAtMs
        val now = wallClock()
        update { it.copy(stage = stage, stageAtMs = now, userPaused = it.userPaused && stage < FirstRunStage.READY) }
        log("stage=$stage from=$from ms=${msSince(since, now)}")
    }

    /** The speech files are gone after the setup: it starts over, from Get ready, with nothing planned. */
    private fun startedOver(from: StoredFirstRun): StoredFirstRun {
        val fresh = StoredFirstRun(stage = FirstRunStage.GET_READY, stageAtMs = wallClock())
        store.write(fresh)
        log("stage=${FirstRunStage.GET_READY} from=${from.stage} reason=speech_gone")
        return fresh
    }

    /** Stores what [change] gives, before anything shows it. */
    private fun update(change: (StoredFirstRun) -> StoredFirstRun) {
        val next = change(stored)
        if (next == stored) return
        store.write(next)
        stored = next
    }

    // ── What the screens see ─────────────────────────────────────────────────

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot(): FirstRunSnapshot = FirstRunSnapshot(
        stage = stored.stage,
        lookedAround = stored.lookedAround,
        malayalamWanted = stored.malayalamWanted,
        plan = plan,
        setup = setupProgress(),
        later = laterProgress(),
        network = facts,
        space = shortfall.takeIf { (step as? QueueStep.Hold)?.reason == PauseReason.NOT_ENOUGH_STORAGE },
        readerFound = readerFound
    )

    /** Getting ready's one bar over the setup's parts; Ready's, full. */
    private fun setupProgress(): FirstRunProgress? {
        val setup = plan.setup
        if (stored.stage == FirstRunStage.GET_READY || setup.isEmpty()) return null
        val total = plan.setupBytes
        if (stored.stage >= FirstRunStage.READY) return FirstRunProgress(setup.last().part, DownloadState.Completed, total, total)
        val item = setup.firstOrNull { it.status == FirstRunPlan.Status.PENDING } ?: setup.last()
        val ours = current == item.part
        return FirstRunProgress(
            part = item.part,
            state = shownState(item.part),
            bytesDone = barBytes(item.part, onPhone[item.part] ?: 0L),
            bytesTotal = total,
            minutesLeft = minutesLeft.takeIf { ours },
            resumedFromBytes = resumedFrom.takeIf { ours }
        )
    }

    /** The status strip's download after the setup, while one is under way or held. */
    private fun laterProgress(): FirstRunProgress? {
        if (stored.stage < FirstRunStage.READY) return null
        val part = current ?: return null
        val item = plan.item(part) ?: return null
        return FirstRunProgress(
            part = part,
            state = shownState(part),
            bytesDone = (onPhone[part] ?: 0L).coerceAtMost(item.bytes),
            bytesTotal = item.bytes,
            minutesLeft = minutesLeft,
            resumedFromBytes = resumedFrom
        )
    }

    /** [part]'s download state, or the hold the queue gives it where nothing has said so yet. */
    private fun shownState(part: FirstRunPart): DownloadState {
        val state = downloads.state(part)
        val held = (step as? QueueStep.Hold)?.takeIf { it.part == part }
        return if (held != null && state !is DownloadState.Paused) {
            val total = plan.item(part)?.bytes ?: 0L
            val bytes = onPhone[part] ?: 0L
            DownloadState.Paused(held.reason, DownloadProgress(bytes, total, percentOf(bytes, total)))
        } else {
            state
        }
    }

    /** The bytes the bar shows with [partBytes] of [part] in: Getting ready's over the setup's parts, the strip's over the part. */
    private fun barBytes(part: FirstRunPart, partBytes: Long): Long {
        val item = plan.item(part) ?: return partBytes
        val own = partBytes.coerceIn(0L, item.bytes)
        if (!part.beforeFirstRecording || stored.stage != FirstRunStage.DOWNLOADING) return own
        return plan.setup.filter { it.status != FirstRunPlan.Status.PENDING }.sumOf { it.bytes } + own
    }

    private fun spaceNow(part: FirstRunPart): SpaceShortfall {
        val left = ((plan.item(part)?.bytes ?: 0L) - (onPhone[part] ?: 0L)).coerceAtLeast(0L)
        return SpaceShortfall(FirstRunPlan.neededSpace(left), files.freeBytes())
    }

    // ── The run under way ────────────────────────────────────────────────────

    /**
     * A new run of the part under way: its time left starts over, but for the next part of the
     * setup right after one finished ([barGoesOn]), whose bar and bytes go on from where they were.
     */
    private fun newRun() {
        if (!barGoesOn) {
            timeLeft = null
            minutesLeft = null
        }
        resumedFrom = null
    }

    private fun estimate(part: FirstRunPart) {
        if (part != current) return
        val item = plan.item(part) ?: return
        val total = if (part.beforeFirstRecording && stored.stage == FirstRunStage.DOWNLOADING) plan.setupBytes else item.bytes
        // One estimate per bar: Getting ready's over the setup, the strip's over its part.
        val estimator = timeLeft?.takeIf { timeLeftTotal == total } ?: TimeLeft(total).also {
            timeLeft = it
            timeLeftTotal = total
        }
        minutesLeft = estimator.update(clock(), barBytes(part, onPhone[part] ?: 0L))
        // "Resuming from 412 MB" is said until the time left shows.
        if (minutesLeft != null) resumedFrom = null
    }

    /**
     * Once a second while a part runs or is held: the time left of a download that gets no bytes
     * fades out, and every [POLL_MS] a held one looks at the disk and the space again.
     */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var ticks = 0L
            while (true) {
                delay(TICK_MS)
                ticks++
                val part = current ?: continue
                if (DownloadServiceRules.isRunning(downloads.state(part))) {
                    estimate(part)
                    publish()
                } else if (ticks % (POLL_MS / TICK_MS) == 0L) {
                    followDisk()
                    reconcile()
                }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun log(line: String) {
        logLine(line)
    }

    /** Where the field log's last progress line of a part was: its tenth, when, at how many bytes, and whether a file was being checked. */
    private data class ProgressMark(val tenth: Int, val ms: Long, val bytes: Long, val verifying: Boolean)

    companion object {
        private const val TAG = "SatoriFirstRun"

        /** After a recording is saved, the downloads it held go on this much later. */
        const val RESUME_AFTER_RECORDING_MS = 3_000L

        /** How often a held part looks at the model folder and the space again. */
        const val POLL_MS = 5_000L

        private const val TICK_MS = 1_000L

        // What made a part go on, in the field log's resume lines.
        private const val SOURCE_APP_START = "app_start"
        private const val SOURCE_USER = "user"
        private const val SOURCE_NETWORK = "network"
        private const val SOURCE_RETRY = "retry"

        private val BUSY = setOf(ActiveRecorder.Activity.STARTING, ActiveRecorder.Activity.RECORDING, ActiveRecorder.Activity.SAVING)

        private val NO_PLAN = FirstRunPlan(emptyList())

        private val FirstRunPart.logName: String get() = name.lowercase()

        private fun mb(bytes: Long): Long = bytes / 1_000_000L

        /** The ms from [since] to [now], 0 when [since] isn't known. */
        private fun msSince(since: Long, now: Long): Long = if (since > 0L) now - since else 0L

        private fun percentOf(bytes: Long, total: Long): Int = if (total <= 0L) 0 else (bytes * 100 / total).toInt().coerceIn(0, 100)

        /** One event after the other, off the main thread. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private fun serialDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    }
}
