package com.satori.presentation.sessions

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.firstrun.FirstRunCoordinator
import com.satori.domain.firstrun.FirstRunPart
import com.satori.domain.firstrun.FirstRunPlan
import com.satori.domain.firstrun.FirstRunSnapshot
import com.satori.domain.firstrun.NudgeId
import com.satori.domain.firstrun.NudgeLedger
import com.satori.domain.firstrun.NudgeState
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordRequest
import com.satori.domain.model.RecordTarget
import com.satori.domain.model.RecordTargets
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.SpeechModelRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.usecase.reminder.ReminderRules
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.transcription.CreateSessionUseCase
import com.satori.domain.usecase.transcription.GetAllSessionsUseCase
import com.satori.domain.usecase.transcription.RecordingVisibility
import com.satori.presentation.firstrun.StripState
import com.satori.presentation.main.ActiveRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for the home screen: the to-dos and the row that leads to the ideas, the recordings
 * with how far each one being finished in the background has come, and what Record starts.
 *
 * The lists are read only while the screen is on show (plus five seconds), so a recording that
 * writes a line every few seconds does not keep re-running them behind the recorder.
 *
 * How far the owner has seen the ideas row ([IdeasSighting]) is kept in [savedStateHandle], so the
 * row's count of new ideas outlasts a fold, a turn of the screen and a restored process. The
 * screen says when the row was seen and when home starts and stops; IdeasSighting decides what
 * that leaves new. A home opened afresh counts from the moment it opened: what was found before
 * is no news to it.
 *
 * Record starts a recording as the target and in the language last chosen in "Record as"
 * ([AppSettings.homeLanguage]); Malayalam only while its speech model is on the phone, which is
 * looked for as home shows ([homeStarted]): a download in Settings is picked up on the way back.
 *
 * The status strip at the top ([SessionsUiState.strip], [HomeStrip]) shows the first run's
 * download after the setup, Malayalam dropped from it, the first notes' confirmation and the
 * one-time offers, one at a time. Its offers and its confirmation are kept in the nudge ledger
 * ([NudgeLedgerStore]); none comes while a recorder is live or within a minute of a Stop, and the
 * strip looks again by itself once that minute is over or a nudge on show goes after its day.
 *
 * @property todoActions Everything the user does to a to-do; each ends in rescheduling reminders
 * @property speechModels Whether the Malayalam speech model is on the phone
 * @property firstRun The first run's coordinator: the strip's download, and what its pills ask for
 * @property nudgeLedger Where each offer and the first notes' confirmation stands
 * @property phone What the offers read from the phone, and the clock they go by
 * @param activeRecorder The open recorder, whose recording the list keeps while it records or saves
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    getAllSessionsUseCase: GetAllSessionsUseCase,
    private val createSessionUseCase: CreateSessionUseCase,
    private val settingsRepository: SettingsRepository,
    categoryRepository: CategoryRepository,
    private val todoRepository: TodoRepository,
    private val todoActions: TodoActions,
    activeRecorder: ActiveRecorder,
    finishingQueue: FinishingQueue,
    private val speechModels: SpeechModelRepository,
    private val savedStateHandle: SavedStateHandle,
    private val firstRun: FirstRunCoordinator,
    private val nudgeLedger: NudgeLedgerStore,
    private val phone: HomePhone
) : ViewModel() {

    internal companion object {
        private const val TAG = "SessionsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Ideas found after this (epoch ms) are new: [IdeasSighting.seenAt]. */
        const val KEY_IDEAS_SEEN_AT = "ideas_seen_at"

        // The rest of IdeasSighting, while a sighting has yet to count.
        private const val KEY_IDEAS_SHOWN_SINCE = "ideas_shown_since"
        private const val KEY_IDEAS_ROW_TAPPED = "ideas_row_tapped"
        private const val KEY_HOME_LEFT_AT = "home_left_at"
    }

    private val error = MutableStateFlow<SessionsError?>(null)

    // Whether the Malayalam speech model is on the phone, so Record as offers Malayalam.
    private val malayalamOnPhone = MutableStateFlow(false)

    private val _sheetTodo = MutableStateFlow<Todo?>(null)

    /** The to-do whose sheet is open, or null. */
    val sheetTodo: StateFlow<Todo?> = _sheetTodo.asStateFlow()

    private val ideasSeenAt: StateFlow<Long> =
        savedStateHandle.getStateFlow(KEY_IDEAS_SEEN_AT, System.currentTimeMillis())

    // What the strip's offers read from the phone: as home starts, and after Android's window.
    private val phoneFacts = MutableStateFlow(PhoneFacts())

    // When home last saw a recorder stop (epoch ms); 0 while it saw none.
    private val lastStopAt = MutableStateFlow(0L)

    // The time the strip was decided at, set again when it may change with nothing else changing.
    private val stripClock = MutableStateFlow(phone.nowMs())
    private var stripWake: Job? = null
    private var stripWakeAtMs: Long? = null

    // The recordings as the strip last counted them: the ping's field log line says how many, and
    // an opened recording ends a first notes' confirmation still to come once the first notes exist.
    @Volatile private var stripRecordings = StripRecordings()

    // Whether AI notes are on, as the strip last read it: the reader's Try again is offered only then.
    @Volatile private var stripAiNotesOn = true

    private val recordings = combine(
        getAllSessionsUseCase().catch { e ->
            Log.e(TAG, "Failed to load sessions", e)
            error.value = SessionsError.Load
            emit(emptyList())
        },
        activeRecorder.current
    ) { items, recorder ->
        val capturing = capturingSessionId(recorder)
        val recordingNow = RecordingVisibility.recordingNowId(capturing)
        Recordings(
            visible = RecordingVisibility.visible(items, recordingNow),
            titles = items.associate { it.session.id to it.title },
            all = items,
            capturingId = capturing,
            live = isLive(recorder)
        )
    }

    private val todos = combine(
        todoRepository.observeOpen().catch { e ->
            Log.e(TAG, "Failed to load to-dos", e)
            emit(emptyList())
        },
        todoRepository.observeSuggested().catch { e ->
            Log.e(TAG, "Failed to load suggested to-dos", e)
            emit(emptyList())
        },
        todoRepository.observeIdeas().catch { e ->
            Log.e(TAG, "Failed to load ideas", e)
            emit(emptyList())
        },
        ideasSeenAt
    ) { open, suggested, ideas, seenAt ->
        TodoLists(open, suggested, ideas, SessionsUiState.newIdeas(ideas, seenAt))
    }

    private val finishing = finishingQueue.statuses.map { statuses -> statuses.associateBy(FinishingStatus::sessionId) }

    private val recordTargets = combine(
        categoryRepository.observeAll().catch { e ->
            Log.e(TAG, "Failed to load categories", e)
            emit(emptyList())
        },
        settingsRepository.getSettings(),
        malayalamOnPhone
    ) { categories, settings, malayalam ->
        RecordTargetState(
            categories = categories,
            target = RecordTargets.resolve(settings.homeCategoryId, categories),
            translateTarget = RecordRequest.translationTarget(settings.translationTargetLanguage),
            language = settings.homeLanguage.usableWith(malayalam),
            malayalamOnPhone = malayalam
        )
    }

    private val stripInputs = combine(
        firstRun.state,
        nudgeLedger.ledger,
        phoneFacts,
        settingsRepository.getSettings().map { it.llmEnabled }.distinctUntilChanged(),
        combine(lastStopAt, stripClock, ::Pair)
    ) { snapshot, ledger, phoneNow, aiNotesOn, (stopAt, now) ->
        StripInputs(snapshot, ledger, phoneNow, aiNotesOn, stopAt, now)
    }

    val uiState: StateFlow<SessionsUiState> = combine(
        recordings,
        todos,
        recordTargets,
        combine(finishing, error, ::Pair),
        stripInputs
    ) { listed, todoLists, targets, (finishingNow, failure), strip ->
        val counted = StripRecordings.of(listed.all, listed.capturingId, finishingNow.keys).also { stripRecordings = it }
        stripAiNotesOn = strip.aiNotesOn
        val decision = HomeStrip.decide(
            snapshot = strip.snapshot,
            ledger = strip.ledger,
            recordings = counted,
            phone = strip.phone,
            aiNotesOn = strip.aiNotesOn,
            malayalamOnPhone = targets.malayalamOnPhone,
            recordingLive = listed.live,
            lastStopAtMs = strip.lastStopAtMs,
            nowMs = strip.nowMs,
            // As the to-do reminders' sheet counts them (SessionsUiState.hasPendingReminders).
            remindersPending = todoLists.open.any { it.remindAt != null && !ReminderRules.isShown(it, strip.nowMs) }
        )
        wakeStripAt(decision.nextChangeAtMs)
        SessionsUiState(
            recordings = listed.visible,
            titles = listed.titles,
            finishing = finishingNow,
            openTodos = todoLists.open,
            suggestions = todoLists.suggested,
            ideas = todoLists.ideas,
            newIdeas = todoLists.newIdeas,
            categories = targets.categories,
            target = targets.target,
            translateTarget = targets.translateTarget,
            language = targets.language,
            malayalamOnPhone = targets.malayalamOnPhone,
            isLoading = false,
            error = failure,
            strip = decision.strip,
            asksQuiet = decision.quiet
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionsUiState())

    init {
        lookForMalayalam()
        readPhone()
        watchRecorder(activeRecorder)
        // The to-do reminders' sheet answered, or Android's window was asked for: the ping goes at
        // once, not at the next start of home.
        viewModelScope.launch {
            phone.notificationAskChanges()
                .catch { e -> Log.w(TAG, "Couldn't follow the notification ask", e) }
                .collect { readPhone() }
        }
    }

    /**
     * Creates the session for [target] and calls [onSessionCreated] with its id, so the screen can
     * open the recorder, which starts at once, or [onFailed] when it couldn't be created, so the
     * screen can take the next tap. [target] becomes what the one-tap Record starts from now on, and
     * so does [language], the one picked in "Record as"; the one-tap Record passes none and records
     * in the one stored ([AppSettings.homeLanguage]), which it leaves as it was. A translation records
     * in English or German, whatever the language says, and leaves the language stored as it was;
     * Malayalam records in English or German while its model isn't on the phone, and the one-tap
     * Record in Malayalam again once it is back. Whether it is there is looked at as the recording
     * starts, not taken from the last look as home showed: a model deleted in Settings just before
     * would otherwise start a recording its recorder can't record.
     *
     * The note about an earlier failed start goes when the user tries again: if this one works it
     * must not be waiting on the home screen afterwards.
     */
    fun startRecording(
        target: RecordTarget,
        language: RecordingLanguage?,
        onSessionCreated: (String) -> Unit,
        onFailed: () -> Unit
    ) {
        error.compareAndSet(SessionsError.Create, null)
        viewModelScope.launch {
            var recordsIn = RecordingLanguage.ENGLISH_OR_GERMAN
            val created = try {
                val settings = settingsRepository.getSettings().first()
                val wanted = language ?: settings.homeLanguage
                val malayalam = target != RecordTarget.Translate && wanted == RecordingLanguage.MALAYALAM && malayalamIsOnPhone()
                recordsIn = recordingLanguageOf(target, wanted, malayalam)
                val request = RecordRequest.of(target, settings)
                createSessionUseCase(
                    name = null,
                    mode = request.mode,
                    outputLanguage = request.outputLanguage,
                    insightStrategy = request.insightStrategy,
                    topic = null,
                    categoryId = request.categoryId,
                    zoneId = null,
                    language = recordsIn
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            created
                .onSuccess { session ->
                    RecordTargets.idOf(target)?.let { storeHomeCategory(it) }
                    if (language != null && target != RecordTarget.Translate) storeHomeLanguage(language)
                    Log.i(TAG, "Recording ${session.id} started from home in $recordsIn")
                    onSessionCreated(session.id)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to create session", e)
                    error.value = SessionsError.Create
                    onFailed()
                }
        }
    }

    /** A failure to store the choice must not stop the recording that is starting. */
    private suspend fun storeHomeCategory(homeCategoryId: String) {
        try {
            settingsRepository.setHomeCategoryId(homeCategoryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store the home category", e)
        }
    }

    /** Nor must a failure to store the language. */
    private suspend fun storeHomeLanguage(language: RecordingLanguage) {
        try {
            settingsRepository.setHomeLanguage(language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to store the home language", e)
        }
    }

    /** Looks whether the Malayalam speech model is on the phone, for Record as. */
    private fun lookForMalayalam() {
        viewModelScope.launch { malayalamIsOnPhone() }
    }

    /**
     * Whether the Malayalam speech model is on the phone, whole ([SpeechModelRepository.isInstalled]),
     * looked at now; what Record as offers follows. A failed look counts as not there.
     */
    private suspend fun malayalamIsOnPhone(): Boolean {
        val onPhone = try {
            speechModels.isInstalled(SpeechModelId.INDICCONFORMER_ML)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't look for the Malayalam model", e)
            false
        }
        malayalamOnPhone.value = onPhone
        return onPhone
    }

    fun clearError() {
        error.value = null
    }

    /** Opens the sheet of [todo]. */
    fun openTodo(todo: Todo) {
        _sheetTodo.value = todo
    }

    /**
     * Opens the sheet of the to-do [id], as a tapped reminder asks. Only an open to-do has a
     * reminder, so one that is gone, done, dismissed or only suggested opens nothing.
     */
    fun openTodoById(id: String) {
        viewModelScope.launch {
            val todo = try {
                todoRepository.get(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read a to-do", e)
                null
            }
            if (todo?.status == TodoStatus.OPEN) _sheetTodo.value = todo
        }
    }

    fun closeTodo() {
        _sheetTodo.value = null
    }

    /** Stores the new text and due day and time of the to-do of the open sheet, and closes it. */
    fun saveOpenTodo(text: String, date: LocalDate?, time: LocalTime?) {
        val todo = _sheetTodo.value ?: return
        _sheetTodo.value = null
        changeTodos { todoActions.edit(todo.id, text, date, time, ZoneId.systemDefault()) }
    }

    /** Deletes the to-do of the open sheet and closes it. */
    fun deleteOpenTodo() {
        val todo = _sheetTodo.value ?: return
        _sheetTodo.value = null
        changeTodos { todoActions.delete(todo.id, TodoSource.HOME) }
    }

    /** Moves the to-do of the open sheet to the ideas, where nothing reminds, and closes it. */
    fun moveOpenTodoToIdeas() {
        val todo = _sheetTodo.value ?: return
        _sheetTodo.value = null
        changeTodos { todoActions.moveToIdeas(todo.id, TodoSource.HOME) }
    }

    /**
     * The owner has seen the ideas row: it was on screen for a moment with the phone unlocked,
     * saying what it said at [sinceMs] (epoch ms).
     */
    fun ideasRowSeen(sinceMs: Long) = changeSighting { it.shown(sinceMs) }

    /** The ideas row was tapped, which opens the ideas: leaving home for them leaves none new. */
    fun ideasRowTapped() = changeSighting { it.tapped() }

    /**
     * Home shows again, at [nowMs] (epoch ms): it started. The Malayalam model is looked for again,
     * as it may have been downloaded or deleted in Settings meanwhile, and the phone is read again
     * for the strip's offers. A nudge on show for a day goes now, also one from before a restart.
     */
    fun homeStarted(nowMs: Long = System.currentTimeMillis()) {
        lookForMalayalam()
        changeSighting { it.back(nowMs) }
        readPhone()
        nudgeLedger.update { it.faded(phone.nowMs()) }
        stripClock.value = phone.nowMs()
    }

    /** Home no longer shows, from [nowMs] (epoch ms): it stopped, or left the screen. */
    fun homeStopped(nowMs: Long = System.currentTimeMillis()) = changeSighting { it.left(nowMs) }

    private fun changeSighting(change: (IdeasSighting) -> IdeasSighting) {
        val sighting = IdeasSighting(
            seenAt = ideasSeenAt.value,
            shownSince = savedStateHandle[KEY_IDEAS_SHOWN_SINCE],
            rowTapped = savedStateHandle[KEY_IDEAS_ROW_TAPPED] ?: false,
            leftAt = savedStateHandle[KEY_HOME_LEFT_AT]
        )
        val changed = change(sighting)
        if (changed == sighting) return
        savedStateHandle[KEY_IDEAS_SHOWN_SINCE] = changed.shownSince
        savedStateHandle[KEY_IDEAS_ROW_TAPPED] = changed.rowTapped
        savedStateHandle[KEY_HOME_LEFT_AT] = changed.leftAt
        // Last, as it recounts the new ideas.
        savedStateHandle[KEY_IDEAS_SEEN_AT] = changed.seenAt
    }

    /**
     * Ticks off [id] from home: its circle in the list as it first shows, or past the first five
     * after Show all ([TodoSource.LIST]), or its sheet ([TodoSource.HOME]).
     */
    fun completeTodo(id: String, source: TodoSource) = changeTodos { todoActions.complete(id, source) }

    /** Notifications were just allowed; see [TodoActions.notificationsAllowed]. */
    fun notificationsAllowed() = changeTodos { todoActions.notificationsAllowed() }

    /** Takes [completeTodo] back. */
    fun reopenTodo(id: String) = changeTodos { todoActions.reopen(id) }

    fun keepSuggestions(suggestions: List<Todo>) = changeTodos { todoActions.keep(suggestions, TodoSource.HOME) }

    fun dismissSuggestion(id: String) = changeTodos { todoActions.dismiss(id, TodoSource.HOME) }

    /** Moves the suggested to-do [id] to the ideas, from the suggestions sheet. */
    fun moveSuggestionToIdeas(id: String) = changeTodos { todoActions.moveToIdeas(id, TodoSource.HOME) }

    private fun changeTodos(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change a to-do", e)
                error.value = SessionsError.Todo
            }
        }
    }

    /**
     * The strip has been on screen, with the phone unlocked, since [sinceMs] (epoch ms), showing
     * [strip]: an offer or the first notes' confirmation counts as shown from then, once. The
     * ping's first showing says first what made it due ([pingTriggerLine]).
     */
    fun stripSeen(strip: StripState, sinceMs: Long) {
        val id = when (strip) {
            is StripState.Nudge -> strip.id
            is StripState.FirstNotes -> NudgeId.FIRST_NOTES
            is StripState.Download, StripState.MalayalamFailed -> return
        }
        if (strip is StripState.Nudge && id == NudgeId.NOTIFY_PING && nudgeLedger.ledger.value.state(id) == NudgeState.NEVER_SHOWN) {
            nudgeLedger.log(pingTriggerLine(strip.missedNotes, phoneFacts.value.notesMissed, stripRecordings.withWords))
        }
        nudgeLedger.update { it.shown(id, sinceMs) }
    }

    /**
     * The offer [id] was answered, its offer taken ([accepted]) or Not now: it is never made again.
     * Taking the ping marks Android's notification window as asked for before the screen opens it,
     * so the to-do reminders' sheet doesn't ask as well; taking the bigger AI model plans its
     * download, which the strip then shows. A second tap on a strip that is going answers nothing.
     *
     * @return Whether this answered the offer, so the screen opens Android's window for the ping
     *   only then
     */
    fun offerAnswered(id: NudgeId, accepted: Boolean): Boolean {
        val before = nudgeLedger.ledger.value
        nudgeLedger.update { it.answered(id, accepted, phone.nowMs()) }
        if (nudgeLedger.ledger.value == before) return false
        if (accepted) {
            when (id) {
                NudgeId.NOTIFY_PING -> {
                    phone.markNotificationsAsked()
                    phoneFacts.update { it.copy(notificationsAsked = true) }
                }
                NudgeId.E2B_OFFER -> firstRun.getBiggerModel()
                NudgeId.RETENTION, NudgeId.FIRST_NOTES -> Unit
            }
        }
        return true
    }

    /**
     * Android's notification window, which the ping opened, has answered, [allowed] or not: the
     * field log says which, and what it changed is read again.
     */
    fun notificationsAnswered(allowed: Boolean) {
        nudgeLedger.log("id=${NudgeId.NOTIFY_PING} window allowed=$allowed")
        readPhone()
    }

    /** Open on the first notes' confirmation: the screen opens that recording's notes, and the confirmation is done. */
    fun firstNotesOpened() = nudgeLedger.update { it.answered(NudgeId.FIRST_NOTES, accepted = true, atMs = phone.nowMs()) }

    /**
     * A recording was opened from home: the first notes' confirmation on show goes, as its notes can
     * be seen now; once the first notes are written, so does one still to come, which waited behind
     * the ping for notes no notification could tell of.
     */
    fun recordingOpened() = nudgeLedger.update { it.gone(NudgeId.FIRST_NOTES, unseen = stripRecordings.withNotes.isNotEmpty()) }

    /** Try now or Try again on the strip's download of [part]. */
    fun retryDownload(part: FirstRunPart) = firstRun.retry(part)

    /** Use mobile data on the strip's download of [part], which waits for Wi-Fi. */
    fun useMobileData(part: FirstRunPart) = firstRun.useMobileData(part)

    /** Continue on the strip's download, held by Getting ready's Pause. */
    fun resumeDownload() = firstRun.resume()

    /**
     * Try again for the Malayalam the first run dropped, which the strip offers once
     * ([NudgeLedger.malayalamRetried]), and for the reader it dropped, which the same strip offers
     * once ([NudgeLedger.readerRetried], 0.8.0): each of them the strip is on show for is planned
     * again ([HomeStrip.malayalamDropped], [HomeStrip.readerDropped]), and neither once its own Try
     * again was used.
     */
    fun retryMalayalam() {
        val snapshot = firstRun.state.value
        val ledger = nudgeLedger.ledger.value
        val malayalam = HomeStrip.malayalamDropped(snapshot, ledger, malayalamOnPhone.value)
        val reader = HomeStrip.readerDropped(snapshot, ledger, stripAiNotesOn)
        // A strip on show for neither (a tap as it goes) retries Malayalam, as before 0.8.0.
        if (malayalam || !reader) {
            nudgeLedger.update { it.malayalamRetryUsed() }
            firstRun.retry(FirstRunPart.MALAYALAM)
        }
        if (reader) {
            nudgeLedger.update { it.readerRetryUsed() }
            firstRun.retry(FirstRunPart.READER)
        }
    }

    /** Reads the phone for the strip's offers. A failed read keeps what was read before. */
    private fun readPhone() {
        viewModelScope.launch {
            try {
                phoneFacts.value = phone.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read the phone for the status strip", e)
            }
        }
    }

    /**
     * Notes when [activeRecorder]'s recorder stops being live ([lastStopAt]). Home's own view model
     * outlives the recorder over it; one made after the Stop has the recording's end instead
     * ([StripRecordings.lastEndedAtMs]).
     */
    private fun watchRecorder(activeRecorder: ActiveRecorder) {
        viewModelScope.launch {
            var wasLive = false
            activeRecorder.current.collect { recorder ->
                val live = isLive(recorder)
                if (wasLive && !live) lastStopAt.value = phone.nowMs()
                wasLive = live
            }
        }
    }

    /**
     * Decides the strip again at [atMs] (epoch ms), when it may change with nothing else changing;
     * null when nothing is due. A wake that came keeps nothing, so the strip asks for the next one.
     */
    private fun wakeStripAt(atMs: Long?) {
        if (atMs == stripWakeAtMs) return
        stripWakeAtMs = atMs
        stripWake?.cancel()
        stripWake = atMs?.let { at ->
            viewModelScope.launch {
                delay((at - phone.nowMs()).coerceAtLeast(0L))
                stripWakeAtMs = null
                stripClock.value = phone.nowMs()
            }
        }
    }

    /**
     * @property all Every recording, the empty attempts included, as the strip counts them
     * @property capturingId The recording the open recorder captures ([capturingSessionId])
     * @property live A recorder is starting, recording or saving ([isLive])
     */
    private class Recordings(
        val visible: List<SessionListItem>,
        val titles: Map<String, RecordingTitle>,
        val all: List<SessionListItem>,
        val capturingId: String?,
        val live: Boolean
    )

    /** What the strip reads besides the recordings, with the time it is decided at. */
    private class StripInputs(
        val snapshot: FirstRunSnapshot,
        val ledger: NudgeLedger,
        val phone: PhoneFacts,
        val aiNotesOn: Boolean,
        val lastStopAtMs: Long,
        val nowMs: Long
    )

    private class TodoLists(val open: List<Todo>, val suggested: List<Todo>, val ideas: List<Todo>, val newIdeas: Int)

    private class RecordTargetState(
        val categories: List<RecordingCategory>,
        val target: RecordTarget,
        val translateTarget: String,
        val language: RecordingLanguage,
        val malayalamOnPhone: Boolean
    )
}

/**
 * The language a recording started from home as [target] in [language] is made in: a translation
 * in English or German, which the live translation reads, and Malayalam only while its model is on
 * the phone ([malayalamOnPhone]).
 */
internal fun recordingLanguageOf(target: RecordTarget, language: RecordingLanguage, malayalamOnPhone: Boolean): RecordingLanguage =
    if (target == RecordTarget.Translate) RecordingLanguage.ENGLISH_OR_GERMAN else language.usableWith(malayalamOnPhone)

/**
 * The recording [recorder] captures: the one whose microphone is live, or whose last words are
 * being saved after Stop. Null while it only starts, as nothing has been captured yet, while it
 * is idle, and while no recorder is open ([recorder] null). Every activity but those two captures.
 */
internal fun capturingSessionId(recorder: ActiveRecorder.Snapshot?): String? =
    recorder?.takeIf { it.activity != ActiveRecorder.Activity.IDLE && it.activity != ActiveRecorder.Activity.STARTING }
        ?.sessionId

/**
 * Whether [recorder] is live for the strip: starting, recording or saving. No offer comes then,
 * and its end starts the minute after a Stop in which none comes either.
 */
internal fun isLive(recorder: ActiveRecorder.Snapshot?): Boolean =
    recorder != null && recorder.activity != ActiveRecorder.Activity.IDLE

/**
 * The field log's line as the ping first shows, before its `state=SHOWN`: what made it due, notes
 * that no notification could tell of ([missedNotes], [missed] of them so far) or the recordings
 * with words, with their count. Counts only.
 */
internal fun pingTriggerLine(missedNotes: Boolean, missed: Int, recordings: Int): String =
    "id=${NudgeId.NOTIFY_PING} trigger=${if (missedNotes) "notes_missed" else "recordings"} missed=$missed recordings=$recordings"
