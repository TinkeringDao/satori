package com.satori.presentation.mindmap

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.data.config.modelConfigForVariant
import com.satori.data.datasource.ModelDownloadManager
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.ProcessingHolders
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.model.RecordingSessionState
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionListItem
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.MindMapRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TranscriptionRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.service.RecordingServiceController
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.mindmap.MapOlderRecordingsUseCase
import com.satori.domain.usecase.mindmap.MapRecordingUseCase
import com.satori.domain.usecase.mindmap.RefreshThemeSummariesUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.runUntilCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * One view of the mind map: the top level, a theme or topic, or a recording's own map, chosen by
 * its navigation arguments (`nodeId`, `sessionId`, neither) and reached through `trail`
 * ([MapTrail]).
 *
 * It reads the map from [MindMapRepository] and puts the page and drawing together with
 * [MindMapRules], and it runs the map's AI work from this view: "Map N older recordings" at the top
 * level, Update on a theme's page, "Map this recording" on a recording's map. That work runs here,
 * like the rewrite of notes on a recording's page: under the LLM processing service, so it goes on
 * with the screen off, and it ends when the view is left. One piece of it runs at a time
 * ([AiWorkGate]), never while a recording is being made or finished, also in the background
 * ([FinishingQueue.busy]), and the model it loaded is freed afterwards. A run of the older
 * recordings gives way between two recordings when a recording is handed over to be finished.
 *
 * The recorder wins: a recorder that leaves IDLE (the quick key, say) stops the work at its next
 * recording or summary ([MapYield]), what is mapped stays, and the same view says so once, opened
 * again ([StoppedWork]): the start closes this one.
 *
 * Nothing the AI wrote (labels, points, summaries) is logged, only counts and timings.
 *
 * Since 0.8.0 the map reads a recording as the finishing's AI steps read it, each line only the
 * reader reads (a Malayalam line) as its English reading: the readings a recording lacks are made
 * first, holding the AI, before the model loads ([EnsureReadingsUseCase]). One whose readings can't
 * be made for now is left for later; without the reader on the phone, it is mapped from what it
 * has, as before ([readsMap]). A recorder that leaves IDLE stops the readings at once, not at a
 * safe point, and the model then doesn't load ([prepareModel]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MindMapViewModel @Inject constructor(
    private val mindMapRepository: MindMapRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val mapOlderRecordings: MapOlderRecordingsUseCase,
    private val refreshThemeSummaries: RefreshThemeSummariesUseCase,
    private val mapRecording: MapRecordingUseCase,
    private val initializeLlmUseCase: InitializeLlmUseCase,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val modelDownloadManager: ModelDownloadManager,
    private val llmProcessingService: LlmProcessingServiceController,
    private val activeRecorder: ActiveRecorder,
    private val recordingService: RecordingServiceController,
    private val finishingQueue: FinishingQueue,
    private val aiGate: AiWorkGate,
    private val viewChoice: MindMapViewChoice,
    private val stoppedWork: StoppedWork,
    private val ensureReadings: EnsureReadingsUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "MindMapVM"

        /** Navigation argument: the theme or topic in the centre. */
        const val ARG_NODE = "nodeId"

        /** Navigation argument: the recording in the centre. */
        const val ARG_SESSION = "sessionId"

        /** Navigation argument: the views that led here ([MapTrail]). */
        const val ARG_TRAIL = "trail"

        private const val KEY_FILTER = "filter"
        private const val KEY_QUERY = "query"
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    private val key: MapKey = savedStateHandle.get<String>(ARG_SESSION)?.let { MapKey.Recording(it) }
        ?: savedStateHandle.get<String>(ARG_NODE)?.let { MapKey.Node(it) }
        ?: MapKey.Root

    private val trail: List<MapKey> = MapTrail.decode(savedStateHandle[ARG_TRAIL])

    private val aiAvailable = MutableStateFlow<Boolean?>(null)
    private val job = MutableStateFlow<MapJob?>(null)
    private val error = MutableStateFlow<MindMapError?>(null)
    private val _notices = Channel<MindMapNotice>(Channel.BUFFERED)

    /** Short confirmations to show once; buffered while the view is not on screen. */
    val notices: Flow<MindMapNotice> = _notices.receiveAsFlow()

    init {
        viewModelScope.launch {
            stoppedWork.stopsOn(key, setOf(StoppedWork.Kind.MAP), taker = this@MindMapViewModel).collect {
                _notices.trySend(if (key is MapKey.Node) MindMapNotice.UpdateStopped else MindMapNotice.MappingStopped)
            }
        }
    }

    /** The AI work running here, so Cancel can stop it. */
    private var running: Job? = null

    /** Whether the user chose the list or the map; null when they haven't ([MindMapViewChoice]). */
    val listChoice: StateFlow<Boolean?> = viewChoice.list

    private val filter: Flow<ThemeFilter> = savedStateHandle.getStateFlow(KEY_FILTER, ThemeFilter.CORE.name)
        .map { name -> ThemeFilter.entries.firstOrNull { it.name == name } ?: ThemeFilter.CORE }

    private val query: Flow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    /** A recording is being made, or finished in the background: it needs the AI and the memory. */
    private val recorderBusy: Flow<Boolean> = combine(
        activeRecorder.current,
        recordingService.sessionState,
        finishingQueue.busy
    ) { recorder, state, finishing ->
        recorder != null || state != RecordingSessionState.Idle || finishing
    }

    private val ai: Flow<AiState> = combine(aiAvailable, recorderBusy, aiGate.busy, job) { available, busy, gate, mine ->
        AiState(available = available, recorderBusy = busy, otherJob = gate && mine == null)
    }

    private val sessions: Flow<List<SessionListItem>> = transcriptionRepository.getAllSessions().orEmptyOnFailure()

    private val nodes: Flow<List<MindNode>> = mindMapRepository.allNodes().orEmptyOnFailure()

    val uiState: StateFlow<MindMapUiState> = combine(loadedPage(), ai, job, error) { loaded, aiState, running, failure ->
        MindMapUiState(
            key = key,
            page = loaded.page,
            scene = loaded.scene,
            crumbs = MapTrail.crumbs(trail, key).map { crumb ->
                CrumbView(labelOf(crumb.key, loaded.labels, loaded.titles), crumb.back, crumb.key)
            },
            childTrail = MapTrail.childTrail(trail, key),
            titles = loaded.titles,
            ai = aiState,
            job = running,
            error = failure
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        MindMapUiState(key = key, childTrail = MapTrail.childTrail(trail, key))
    )

    // ─── Reading the map ─────────────────────────────────────────────────────

    /** What the page and drawing are made from, with the names the crumbs need. */
    private class Loaded(
        val page: MapPage,
        val scene: MapScene?,
        val titles: Map<String, RecordingTitle>,
        val labels: Map<String, String>
    )

    private fun loadedPage(): Flow<Loaded> = when (key) {
        MapKey.Root -> rootPage()
        is MapKey.Node -> nodePage(key.id)
        is MapKey.Recording -> recordingPage(key.sessionId)
    }

    private fun rootPage(): Flow<Loaded> = combine(
        mindMapRepository.themes().orEmptyOnFailure(),
        nodes,
        mindMapRepository.unmappedSessionIds().orEmptyOnFailure(),
        sessions,
        combine(filter, query) { chosen, typed -> chosen to typed }
    ) { themes, allNodes, unmapped, sessionList, (chosen, typed) ->
        val now = System.currentTimeMillis()
        val topicLabels = MindMapRules.topicLabelsByTheme(allNodes)
        val found = MindMapRules.searchThemes(MindMapRules.selectThemes(themes, chosen, now), topicLabels, typed)
        val elsewhere = if (found.isEmpty() && typed.isNotBlank() && chosen != ThemeFilter.ALL) {
            MindMapRules.searchThemes(themes, topicLabels, typed).size
        } else {
            0
        }
        // "You" stands for every recording that holds something, mapped or not.
        val recordings = sessionList.count { it.hasTranscript || it.hasNotes }
        Loaded(
            page = MapPage.Root(chosen, typed, found, themes.size, elsewhere, recordings, unmapped.size),
            scene = MindMapRules.rootScene(found, recordings, now),
            titles = titlesOf(sessionList),
            labels = emptyMap()
        )
    }

    private fun nodePage(id: String): Flow<Loaded> = mindMapRepository.node(id).orOnFailure(null).flatMapLatest { node ->
        when {
            node == null -> flowOf(Loaded(MapPage.Missing, null, emptyMap(), emptyMap()))
            node.kind == MindNodeKind.THEME -> themePage(node)
            else -> topicPage(node)
        }
    }

    private fun themePage(theme: MindNode): Flow<Loaded> = combine(
        mindMapRepository.children(theme.id).orEmptyOnFailure(),
        mindMapRepository.links(theme.id).orEmptyOnFailure(),
        sessions,
        nodes
    ) { topics, links, sessionList, allNodes ->
        val now = System.currentTimeMillis()
        val titles = titlesOf(sessionList)
        val labels = allNodes.associate { it.id to it.label }
        val shown = MindMapRules.visibleTopics(theme, topics)
        val byId = allNodes.associateBy { it.id }
        val recordings = MindMapRules.recordingsOf(links)
        Loaded(
            page = MapPage.Theme(
                theme = theme,
                recordings = recordings.size,
                lastAt = recordings.firstOrNull()?.second,
                summary = MindMapRules.summaryState(theme),
                topics = shown,
                byRecording = MindMapRules.byRecording(links) { topicId ->
                    byId[topicId]?.takeUnless { MindMapRules.repeatsTheme(theme, it) }?.label
                }
            ),
            scene = MindMapRules.themeScene(theme, shown, recordings, titles, now),
            titles = titles,
            labels = labels
        )
    }

    private fun topicPage(topic: MindNode): Flow<Loaded> = combine(
        topic.parentId?.let { mindMapRepository.node(it).orOnFailure(null) } ?: flowOf(null),
        mindMapRepository.links(topic.id).orEmptyOnFailure(),
        sessions,
        nodes
    ) { theme, links, sessionList, allNodes ->
        val now = System.currentTimeMillis()
        val titles = titlesOf(sessionList)
        val recordings = MindMapRules.recordingsOf(links)
        Loaded(
            page = MapPage.Topic(
                topic = topic,
                theme = theme,
                recordings = recordings.size,
                lastAt = recordings.firstOrNull()?.second,
                // The topic is the page's subject; its name needn't follow each recording.
                byRecording = MindMapRules.byRecording(links) { null }
            ),
            scene = MindMapRules.topicScene(topic, recordings, titles, now),
            titles = titles,
            labels = allNodes.associate { it.id to it.label }
        )
    }

    private fun recordingPage(sessionId: String): Flow<Loaded> = combine(
        transcriptionRepository.getSessionWithDetails(sessionId).orOnFailure(null),
        mindMapRepository.nodesForSession(sessionId).orEmptyOnFailure(),
        sessions,
        nodes
    ) { details, topics, sessionList, allNodes ->
        val titles = titlesOf(sessionList)
        if (details == null) return@combine Loaded(MapPage.Missing, null, titles, emptyMap())
        val session = details.session
        val title = titles[sessionId] ?: RecordingTitle.of(session.name, null, session.mode)
        Loaded(
            page = MapPage.Recording(details, topics, MindMapRules.hasEnoughTranscript(details.segments)),
            scene = MindMapRules.recordingScene(sessionId, title, session.createdAt, topics, System.currentTimeMillis()),
            titles = titles,
            labels = allNodes.associate { it.id to it.label }
        )
    }

    private fun titlesOf(items: List<SessionListItem>): Map<String, RecordingTitle> =
        items.associate { it.session.id to it.title }

    /** The name of a crumb; a node or recording gone meanwhile shows as an ellipsis. */
    private fun labelOf(crumb: MapKey, labels: Map<String, String>, titles: Map<String, RecordingTitle>): MapLabel =
        when (crumb) {
            MapKey.Root -> MapLabel.You
            is MapKey.Node -> MapLabel.Text(labels[crumb.id] ?: GONE)
            is MapKey.Recording -> titles[crumb.sessionId]?.let { MapLabel.Recording(it) } ?: MapLabel.Text(GONE)
        }

    private fun <T> Flow<List<T>>.orEmptyOnFailure(): Flow<List<T>> = orOnFailure(emptyList())

    /** A read that fails shows [fallback] and says the map couldn't be loaded, instead of crashing. */
    private fun <T> Flow<T>.orOnFailure(fallback: T): Flow<T> = catch { e ->
        Log.e(TAG, "Failed to read the mind map", e)
        error.value = MindMapError.Load
        emit(fallback)
    }

    // ─── What the user does ──────────────────────────────────────────────────

    /** Chooses the chip of the top level. */
    fun setFilter(chosen: ThemeFilter) {
        savedStateHandle[KEY_FILTER] = chosen.name
    }

    /** The search of the top level; it filters as it is typed. */
    fun setQuery(text: String) {
        savedStateHandle[KEY_QUERY] = text
    }

    /** Switches all views of the map to the list ([list] true) or to the map. */
    fun chooseList(list: Boolean) = viewChoice.choose(list)

    fun clearError() {
        error.value = null
    }

    /**
     * Checks whether the AI can run: AI notes on and a model installed. The screen calls it again
     * when it comes back, as Settings or the model download may have changed either.
     */
    fun refreshAi() {
        viewModelScope.launch {
            aiAvailable.value = try {
                val settings = settingsRepository.getSettings().first()
                settings.llmEnabled && withContext(Dispatchers.IO) { installedModelPath(settings) != null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't check the AI model", e)
                false
            }
        }
    }

    /** Pins the theme in the centre to Core, or unpins it. */
    fun setPinned(pinned: Boolean) {
        val id = (key as? MapKey.Node)?.id ?: return
        viewModelScope.launch {
            try {
                mindMapRepository.setPinned(id, pinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The pin wasn't stored", e)
                error.value = MindMapError.Pin
            }
        }
    }

    /**
     * Maps the recordings that wait, newest first, then rewrites the summaries of the themes they
     * joined ([MapOlderRecordingsUseCase], which keeps the LLM processing service running for it).
     * It gives way between two recordings or two summaries once a recorder leaves IDLE, or a
     * recording waits to be finished, which then gets the AI ([MapYield.olderRun]); the rest can
     * be mapped afterwards. Progress is in [MindMapUiState.job]; how it ended comes as a notice or
     * an error.
     */
    fun mapOlder() {
        // The recordings whose readings can't be made now, which the run leaves for a later one.
        val unread = mutableSetOf<String>()
        runAi(MapJob.Older(), beforeLoad = { giveWay -> unread += unreadOf(giveWay) }) { giveWay -> mapOlder(giveWay, unread) }
    }

    /** The run of [mapOlder], past the readings, leaving [unread] out. */
    private suspend fun mapOlder(giveWay: MapYield, unread: Set<String>) {
        val started = SystemClock.elapsedRealtime()
        val outcome = mapOlderRecordings(shouldYield = giveWay::olderRun, leaveOut = unread) { progress ->
            job.update { current -> (current as? MapJob.Older)?.copy(progress = progress) ?: current }
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000
        when (outcome) {
            is MapOlderRecordingsUseCase.Outcome.Finished -> Log.i(
                TAG,
                "Older recordings: ${outcome.mapped} mapped, ${outcome.withoutTopics} without topics, " +
                    if (outcome.yielded) {
                        val to = if (giveWay.forRecording) "a recording" else "finishing"
                        "${outcome.summaries} summaries, gave way to $to after $seconds s"
                    } else {
                        "${outcome.summaries} summaries in $seconds s"
                    }
            )
            is MapOlderRecordingsUseCase.Outcome.ModelFailed ->
                Log.w(TAG, "Older recordings: the model failed after ${outcome.mapped} in $seconds s")
            MapOlderRecordingsUseCase.Outcome.AlreadyRunning -> Log.i(TAG, "Older recordings: another run is going on")
        }
        // Stopped for a recording, the view says that instead ([runAi]) and shows what was mapped.
        if (!giveWay.forRecording) report(MindMapOutcomes.older(outcome))
    }

    /** Stops the run of the older recordings; what was mapped stays. */
    fun cancelOlder() {
        val current = running?.takeIf { it.isActive } ?: return
        job.update { (it as? MapJob.Older)?.copy(stopping = true) ?: it }
        current.cancel()
    }

    /**
     * Rewrites the summary of the theme in the centre (Update). It is one model call, so a
     * recorder that leaves IDLE stops it only before it begins ([runAi]).
     */
    fun updateSummary() {
        val id = (key as? MapKey.Node)?.id ?: return
        runAi(MapJob.Summary) {
            whileProcessing { report(MindMapOutcomes.summary(refreshThemeSummaries(themeIds = listOf(id), limit = 1))) }
        }
    }

    /**
     * Maps the recording in the centre, as its finishing step would have, and rewrites the
     * summaries of the themes it joined, up to [RefreshThemeSummariesUseCase.PER_FINISH]. A
     * recorder that leaves IDLE meanwhile ends it before the next summary ([MapYield.toRecorder]):
     * the recording stays mapped, and its themes' summaries stale.
     */
    fun mapThisRecording() {
        val sessionId = (key as? MapKey.Recording)?.sessionId ?: return
        var unread = false
        runAi(MapJob.Recording, beforeLoad = { unread = !readsMap(sessionId) }) { giveWay ->
            // Its readings can't be made now: the recording is left for later, and the view says the AI couldn't run.
            if (unread) {
                report(MindMapOutcomes.Report(error = MindMapError.ModelFailed))
                return@runAi
            }
            whileProcessing {
                val outcome = mapRecording(sessionId)
                if (outcome is MapRecordingUseCase.Outcome.Mapped && outcome.themeIds.isNotEmpty() && !giveWay.toRecorder()) {
                    // The links are stored either way; a summary that fails stays stale for later.
                    refreshThemeSummaries(outcome.themeIds, RefreshThemeSummariesUseCase.PER_FINISH, shouldStop = giveWay::toRecorder)
                }
                // Stopped for a recording, the view says that instead ([runAi]).
                if (!giveWay.forRecording) report(MindMapOutcomes.recording(outcome))
            }
        }
    }

    /**
     * Runs [work] as this view's AI work: only when the AI is available, no recording is being
     * made or finished and nothing else uses the AI ([AiWorkGate]). The model's path is set first
     * (it loads on demand), and however the work ends the model is freed and then the gate opens
     * again. Also when a recorder opened meanwhile (a quick-key press): no recorder can use the
     * engine while this work holds the gate, so the model in memory is this work's, Gemma 4 E2B as
     * a rule (about 3.3 GB), of no use to the capture that is starting, and the recorder registers
     * its own once the gate is free ([com.satori.presentation.main.LiveModel]).
     *
     * A recorder that leaves IDLE while the model loads stops the work before it begins; [work]
     * gives way to one at its own safe points ([MapYield]); and leaving the view ends it as a
     * quick-key start leaves the view. Each time the same view, opened again, says so once.
     *
     * [beforeLoad] runs once the AI is found available and set up, before its model loads: the
     * readings the work's recordings lack are made there (0.8.0), as the reader never loads beside
     * an AI model and Gemma 4 E2B's every load keeps its room. They may take minutes (Map N older
     * recordings reads each waiting Malayalam recording), so a recorder that leaves IDLE stops them
     * at once, the reader with them, and then no model loads ([prepareModel]).
     */
    private fun runAi(
        kind: MapJob,
        beforeLoad: suspend (giveWay: MapYield) -> Unit = {},
        work: suspend (giveWay: MapYield) -> Unit
    ) {
        if (running?.isActive == true || aiAvailable.value != true || isRecorderBusy()) return
        if (!aiGate.tryEnter(AiWorkGate.MAP)) return
        job.value = kind
        error.value = null
        val giveWay = MapYield(activeRecorder.current, finishingQueue.busy)
        running = viewModelScope.launch {
            try {
                if (prepareModel(giveWay) { beforeLoad(giveWay) } && !giveWay.toRecorder()) work(giveWay)
            } catch (e: CancellationException) {
                // The view was left: a quick-key start leaves it for its recorder.
                giveWay.toRecorder()
                throw e
            } catch (e: Exception) {
                // The use cases throw only on cancellation; this is the settings or the disk.
                Log.e(TAG, "The mind map's AI work failed", e)
                error.value = MindMapError.ModelFailed
            } finally {
                // NonCancellable: leaving the view cancels the work, and the model must still go.
                withContext(NonCancellable) { releaseModel() }
                if (giveWay.forRecording) stoppedForRecording()
                job.value = null
                running = null
                aiGate.exit(AiWorkGate.MAP)
            }
        }
    }

    /**
     * A recorder that left IDLE stopped this view's AI work: what is mapped stays, and the view,
     * opened again, says so once ([StoppedWork]). A quick-key start closes this one meanwhile.
     */
    private fun stoppedForRecording() {
        Log.i(TAG, "The map's work stopped: a recording started; what is mapped stays")
        stoppedWork.post(StoppedWork.Kind.MAP, key, poster = this)
    }

    /** Shows how a piece of AI work ended. */
    private fun report(report: MindMapOutcomes.Report) {
        report.error?.let { error.value = it }
        report.notice?.let { _notices.trySend(it) }
    }

    private fun isRecorderBusy(): Boolean =
        activeRecorder.current.value != null ||
            recordingService.sessionState.value != RecordingSessionState.Idle ||
            finishingQueue.busy.value

    /** Keeps the process alive while [work] runs with the app in the background or the screen off. */
    private suspend fun whileProcessing(work: suspend () -> Unit) {
        llmProcessingService.startProcessing(ProcessingHolders.MAP)
        try {
            work()
        } finally {
            llmProcessingService.stopProcessing(ProcessingHolders.MAP)
        }
    }

    /**
     * The readings the map's recording [sessionId] lacks, made before the model loads
     * ([EnsureReadingsUseCase]): whether the map may read it now. Without the reader on the phone
     * it does, as before the reader: from its notes, or not at all when it has nothing but lines the
     * reader reads (MapRecordingUseCase), and never the model on them. Readings that can't be made
     * for now (the reader held off, stopped, short of memory or failing) leave it for later.
     */
    private suspend fun readsMap(sessionId: String): Boolean =
        when (val readings = withContext(Dispatchers.Default) { ensureReadings(sessionId, EnsureReadingsUseCase.Work.MAP) }) {
            EnsureReadingsUseCase.Outcome.Ready -> true
            is EnsureReadingsUseCase.Outcome.Missing -> readings.why == EnsureReadingsUseCase.Why.NO_READER
        }

    /**
     * The waiting recordings ([MindMapRepository.unmappedSessionIds]) the map can't read now, their
     * readings made first ([readsMap]), one at a time, newest first: those the run leaves out. A
     * recorder that leaves IDLE, or a recording handed over, stops the readings ([MapYield.olderRun]),
     * and the recordings not reached are left out too, their readings not made.
     */
    private suspend fun unreadOf(giveWay: MapYield): Set<String> {
        val waiting = mindMapRepository.unmappedSessionIds().first()
        val unread = mutableSetOf<String>()
        for ((index, sessionId) in waiting.withIndex()) {
            if (giveWay.olderRun()) return unread + waiting.drop(index)
            if (!readsMap(sessionId)) unread += sessionId
        }
        return unread
    }

    /**
     * Tells the model where it is, as the rewrite of notes does, then puts the model for the work
     * after Stop in memory (Gemma 4 E2B when it is switched on and on the phone): the map's work is
     * that work, done later. When E2B can't be loaded the steps load the live model on demand. False,
     * with the error set, when no model can run. [beforeLoad] runs once the model is set up and
     * before it loads, until a recorder leaves IDLE ([runUntilCapture]): one that stops it, or that
     * has left IDLE by its end, leaves the model unloaded, so Gemma 4 E2B never loads beside the
     * capture that starts, and false is returned without an error ([giveWay] remembers why).
     */
    private suspend fun prepareModel(giveWay: MapYield, beforeLoad: suspend () -> Unit): Boolean {
        val settings = settingsRepository.getSettings().first()
        val path = withContext(Dispatchers.IO) { installedModelPath(settings) }
        if (!settings.llmEnabled || path == null) {
            aiAvailable.value = false
            error.value = MindMapError.NoModel
            return false
        }
        // A phone found short of memory before loads the model with fewer threads.
        if (settings.memoryConstrainedDetected) llmRepository.useConservativeThreads()
        val ready = initializeLlmUseCase(path, loadImmediately = false).fold(
            onSuccess = { true },
            onFailure = { e ->
                Log.e(TAG, "The AI model couldn't be set up", e)
                error.value = MindMapError.ModelFailed
                false
            }
        )
        if (ready) {
            val read = activeRecorder.current.runUntilCapture(onStopped = giveWay::stoppedByRecorder) { beforeLoad() }
            if (!read || giveWay.toRecorder()) return false
            val model = loadFinishingModel()
            if (model is FinishingModel.FellBack) Log.w(TAG, "Gemma 4 E2B couldn't be loaded; the live model does the map's work", model.cause)
            else Log.i(TAG, "The map's work runs on ${if (model == FinishingModel.Switched) "Gemma 4 E2B" else "the live model"}")
        }
        return ready
    }

    /** Frees the memory the model took. */
    private suspend fun releaseModel() {
        try {
            llmRepository.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't release the AI model", e)
        }
    }

    /**
     * The path of an AI model on disk: the one chosen in Settings, else any other variant that is
     * installed, as the recorder and the recording's page do. Null when none is installed.
     */
    private fun installedModelPath(settings: AppSettings): String? {
        val chosen = modelConfigForVariant(settings.llmModelVariant).llmFilename
        return modelDownloadManager.getLlmModelPath(chosen)
            ?: LlmModelVariant.entries
                .filter { it != settings.llmModelVariant }
                .firstNotNullOfOrNull { variant -> modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename) }
    }
}

/** A crumb whose node or recording is gone meanwhile. */
private const val GONE = "…"
