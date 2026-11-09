package com.satori.presentation.sessiondetails

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
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.LlmModelChoice
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.modelfile.ModelFileState
import com.satori.domain.repository.CategoryRepository
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.LlmProcessingServiceController
import com.satori.domain.usecase.finish.EnsureReadingsUseCase
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.InitializeLlmUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.RewriteOutcome
import com.satori.domain.usecase.stt.MakeTextAgainUseCase
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.todo.TodoSource
import com.satori.domain.usecase.transcription.ChangeRecordingLanguageUseCase
import com.satori.domain.usecase.transcription.ChangeRecordingTypeUseCase
import com.satori.domain.usecase.transcription.DeleteSessionUseCase
import com.satori.domain.usecase.transcription.GetSessionDetailsUseCase
import com.satori.domain.usecase.transcription.RenameSessionUseCase
import com.satori.domain.usecase.transcription.UpdateInsightContentUseCase
import com.satori.domain.usecase.transcription.UpdateSegmentTextUseCase
import com.satori.presentation.ai.AiWorkGate
import com.satori.presentation.main.ActiveRecorder
import com.satori.presentation.main.StoppedWork
import com.satori.presentation.main.runUntilCapture
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel for the recording's details page: its transcript and notes, the edits to them,
 * rewriting the notes in place, and the recording's own to-dos and ideas, which a new search can
 * find again.
 *
 * A recording is finished in the background after Stop ([FinishingQueue]): the page follows its
 * steps ([SessionDetailsUiState.finishing]), and the page's own AI work waits while the AI is in
 * use ([AiWorkGate]) or a recording waits to be finished, which goes first. Each piece of that
 * work holds the gate while it runs and frees the model before it lets go, so it never loads or
 * frees a model under the finishing of a recording.
 *
 * The recorder wins ([runUntilCapture]): a recorder that leaves IDLE (the quick key, say) stops
 * writing the notes and the search for to-dos and ideas at once. The notes stay as they were, as
 * do the to-dos and ideas found before, and the recording's page says so once, opened again
 * ([StoppedWork]): the start closes this one.
 *
 * "Change type" records the recording as another category ([changeType]) and writes its notes
 * anew in that category's mode, as Rewrite notes does. Its sheet also changes the language the
 * recording is made in ([changeLanguage]); the transcript stays as it was made.
 *
 * After Transcribe again puts in a transcript in another language, the page makes the recording's
 * name, notes, to-dos and ideas again from it ([makeTextAgain]).
 *
 * Since 0.8.0 that AI work reads the recording as the finishing's AI steps read it: each line only
 * the reader reads (a Malayalam line) as its English reading. The readings it lacks are made first,
 * holding the AI, before the work's model loads ([readingsReady]); without them the work doesn't
 * run and says it failed, and what the recording has stays (design D7).
 *
 * @property savedStateHandle Navigation arguments: `sessionId`, and optionally `highlightId` and
 *   `initialTab` (negative means no preference)
 * @property todoActions Everything the user does to a to-do; each ends in rescheduling reminders
 * @property stoppedWork Where the work a recorder stopped waits to be told
 * @property categoryRepository The recording categories, for "Change type"
 * @property changeRecordingType Records the recording as another category ("Change type")
 * @property changeRecordingLanguage Records the recording in another language ("Change type")
 * @property makeTextAgainUseCase Makes the name, notes, to-dos and ideas again after a transcript
 *   in another language
 * @property ensureReadings Makes the readings the page's AI work reads before it loads its model
 */
@HiltViewModel
class SessionDetailsViewModel @Inject constructor(
    private val getSessionDetailsUseCase: GetSessionDetailsUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val renameSessionUseCase: RenameSessionUseCase,
    private val updateSegmentTextUseCase: UpdateSegmentTextUseCase,
    private val updateInsightContentUseCase: UpdateInsightContentUseCase,
    private val rewriteNotesUseCase: RewriteNotesUseCase,
    private val initializeLlmUseCase: InitializeLlmUseCase,
    private val loadFinishingModel: LoadFinishingModelUseCase,
    private val extractSessionTodos: ExtractSessionTodosUseCase,
    private val llmRepository: LlmRepository,
    private val settingsRepository: SettingsRepository,
    private val todoRepository: TodoRepository,
    private val todoActions: TodoActions,
    private val modelDownloadManager: ModelDownloadManager,
    private val llmProcessingServiceController: LlmProcessingServiceController,
    private val finishingQueue: FinishingQueue,
    private val aiGate: AiWorkGate,
    private val activeRecorder: ActiveRecorder,
    private val stoppedWork: StoppedWork,
    private val categoryRepository: CategoryRepository,
    private val changeRecordingType: ChangeRecordingTypeUseCase,
    private val changeRecordingLanguage: ChangeRecordingLanguageUseCase,
    private val makeTextAgainUseCase: MakeTextAgainUseCase,
    private val ensureReadings: EnsureReadingsUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "SessionDetailsVM"
    }

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"]) {
        "Session ID is required"
    }

    private val requestedTab: Int? = savedStateHandle.get<Int>("initialTab")?.takeIf { it >= 0 }

    private val _uiState = MutableStateFlow(SessionDetailsUiState(highlightId = savedStateHandle["highlightId"]))
    val uiState: StateFlow<SessionDetailsUiState> = _uiState.asStateFlow()

    private val _notices = Channel<DetailsNotice>(Channel.BUFFERED)

    /**
     * For the screen to show once: what each new search for to-dos and ideas found, and the work
     * of this page a recorder stopped.
     */
    val notices: Flow<DetailsNotice> = _notices.receiveAsFlow()

    // The running rewrite, so it can be cancelled.
    private var rewriteJob: Job? = null

    // The running search for to-dos and ideas, so it can be cancelled.
    private var todoSearchJob: Job? = null

    init {
        viewModelScope.launch {
            stoppedWork.stopsOn(sessionId, setOf(StoppedWork.Kind.REWRITE_NOTES, StoppedWork.Kind.FIND_TODOS), taker = this@SessionDetailsViewModel)
                .collect { kind ->
                    _notices.trySend(if (kind == StoppedWork.Kind.REWRITE_NOTES) DetailsNotice.RewriteStopped else DetailsNotice.SearchStopped)
                }
        }
        loadSessionDetails()
        viewModelScope.launch {
            // A recording waiting to be finished goes first, also before the finisher holds the gate.
            combine(aiGate.holder, finishingQueue.busy) { holder, finishing -> holder to finishing }
                .collect { (holder, finishing) -> _uiState.update { it.withAi(holder, finishing) } }
        }
        viewModelScope.launch {
            todoRepository.observeForSession(sessionId)
                .catch { e ->
                    // The to-dos are a courtesy of the page: it works without them.
                    Log.e(TAG, "Failed to load the to-dos of the recording", e)
                    emit(emptyList())
                }
                .collect { todos -> _uiState.update { it.copy(todos = RecordingTodos.of(todos), todosLoaded = true) } }
        }
        viewModelScope.launch {
            categoryRepository.observeAll()
                // Without them "Change type" isn't offered; the page works all the same.
                .catch { e -> Log.e(TAG, "Failed to load the categories", e) }
                .collect { categories -> _uiState.update { it.copy(categories = categories) } }
        }
    }

    private fun loadSessionDetails() {
        viewModelScope.launch {
            combine(
                getSessionDetailsUseCase(sessionId),
                finishingQueue.statuses.map { statuses -> statuses.firstOrNull { it.sessionId == sessionId } }.distinctUntilChanged()
            ) { details, finishing -> details to finishing }
                .catch { e ->
                    Log.e(TAG, "Failed to load session", e)
                    _uiState.update { it.copy(error = DetailsError.Load) }
                }
                .collect { (details, finishing) ->
                    _uiState.update {
                        val next = it.copy(
                            sessionDetails = details,
                            finishing = finishing,
                            // Decided once, from the first load: notes that arrive later (a
                            // rewrite, or the finishing's) don't move the user to another tab.
                            openingTab = it.openingTab ?: details?.let { loaded ->
                                requestedTab
                                    ?: if (loaded.insights.isNotEmpty() || finishing != null) DetailsTab.NOTES else DetailsTab.TRANSCRIPT
                            },
                            error = when {
                                details == null -> DetailsError.Missing
                                // Only the loader's own errors go with a fresh load. The flow
                                // also emits for edits and for a rewrite's swap, which must not
                                // wipe out the report of how those ended.
                                it.error == DetailsError.Missing || it.error == DetailsError.Load -> null
                                else -> it.error
                            }
                        )
                        // The finishing is about to give the lines new ids, or to write the notes
                        // anew: an edit would be lost.
                        next.copy(
                            editingSegment = next.editingSegment.takeIf { next.canEditTranscript },
                            editingInsight = next.editingInsight.takeIf { next.canEditNotes }
                        )
                    }
                }
        }
    }

    /**
     * Checks whether the AI can write notes now: a model on disk and AI notes switched on. The
     * screen calls it again when it comes back from Settings or the model download.
     */
    fun refreshNotesAi() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings().first()
            val installed = withContext(Dispatchers.IO) { installedModelPath(settings) != null }
            val ai = when {
                !installed -> NotesAi.NoModel
                !settings.llmEnabled -> NotesAi.TurnedOff
                else -> NotesAi.Ready
            }
            _uiState.update { it.copy(notesAi = ai) }
        }
    }

    /**
     * Looks whether the Malayalam speech model is on the phone, whole, so "Change type" offers
     * Malayalam ([SessionDetailsUiState.malayalamOnPhone]). The screen calls it again when it comes
     * back, from Settings where the model downloads, say.
     */
    fun lookForMalayalam() {
        viewModelScope.launch {
            val onPhone = withContext(Dispatchers.IO) {
                modelDownloadManager.checkSttModel(SpeechModelId.INDICCONFORMER_ML) == ModelFileState.Intact
            }
            _uiState.update { it.copy(malayalamOnPhone = onPhone) }
        }
    }

    /** The owner doesn't wait for the better transcript of this recording; the notes come from the one there is. */
    fun skipBetterTranscript() {
        finishingQueue.skipBetterTranscript(sessionId)
    }

    // ========== Rename ==========

    fun showRenameDialog() {
        _uiState.update { it.copy(showRename = true) }
    }

    fun hideRenameDialog() {
        _uiState.update { it.copy(showRename = false) }
    }

    /**
     * Rename this session.
     *
     * @param newName New name; blank clears the name
     */
    fun renameSession(newName: String) {
        viewModelScope.launch {
            renameSessionUseCase(sessionId, newName)
                .onFailure { e ->
                    Log.e(TAG, "Failed to rename session: ${e.message}")
                    _uiState.update { it.copy(error = DetailsError.Rename) }
                }
            _uiState.update { it.copy(showRename = false) }
            // sessionDetails refreshes automatically via the existing Flow
        }
    }

    // ========== Delete ==========

    /** Opens the delete sheet, which says how many to-dos stay on the list. */
    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun hideDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    /**
     * Delete this session and navigate back. Its to-dos are kept (they lose their recording).
     * A recording that is being finished is taken out of the finishing queue first, which can take
     * until a model load ends: [SessionDetailsUiState.deleting] holds until the delete returns,
     * and a second call meanwhile does nothing.
     *
     * @param onDeleted Callback invoked after successful deletion
     */
    fun deleteSession(onDeleted: () -> Unit) {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleting = true, showDeleteConfirmation = false) }
        viewModelScope.launch {
            deleteSessionUseCase(sessionId)
                .onSuccess {
                    onDeleted()
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to delete session: ${e.message}")
                    _uiState.update {
                        it.copy(
                            error = DetailsError.Delete,
                            showDeleteConfirmation = false,
                            deleting = false
                        )
                    }
                }
        }
    }

    // ========== Editing ==========

    /** Open the edit sheet for a transcription segment, unless the finishing may still replace the lines. */
    fun showEditSegmentDialog(segment: TranscriptionSegment) {
        _uiState.update { if (it.canEditTranscript) it.copy(editingSegment = segment) else it }
    }

    /** Dismiss the segment edit sheet without saving. */
    fun hideEditSegmentDialog() {
        _uiState.update { it.copy(editingSegment = null) }
    }

    /** Persist updated segment text. */
    fun saveSegmentText(segmentId: String, newText: String) {
        viewModelScope.launch {
            updateSegmentTextUseCase(segmentId, newText)
                .onFailure { e ->
                    Log.e(TAG, "Failed to update segment $segmentId: ${e.message}")
                    _uiState.update { it.copy(error = DetailsError.Segment) }
                }
            _uiState.update { it.copy(editingSegment = null) }
        }
    }

    /**
     * Open the edit sheet for the title, text and action items of a note, unless the finishing may
     * still write the notes anew.
     */
    fun showEditInsightDialog(insight: LlmInsight) {
        _uiState.update { if (it.canEditNotes) it.copy(editingInsight = insight) else it }
    }

    /** Dismiss the note edit sheet without saving. */
    fun hideEditInsightDialog() {
        _uiState.update { it.copy(editingInsight = null) }
    }

    /**
     * Persist updated title, content, and action items for a note, together: a failure leaves
     * the note as it was, and the sheet open with what was typed. A note that notes made again
     * replaced meanwhile (design R9) comes back as one of its own with what was typed
     * ([UpdateInsightContentUseCase]).
     *
     * @param insightId ID of the note to update.
     * @param newTitle Updated title; blank clears it.
     * @param patchedContent Full updated content string (already patched by the caller).
     * @param tasksText Newline-delimited action items; empty string clears them all.
     */
    fun saveInsight(insightId: String, newTitle: String, patchedContent: String, tasksText: String) {
        viewModelScope.launch {
            val taskLines = tasksText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val tasksJson = if (taskLines.isEmpty()) null
                            else org.json.JSONArray().apply { taskLines.forEach { put(it) } }.toString()

            updateInsightContentUseCase(sessionId, insightId, newTitle.ifBlank { null }, patchedContent, tasksJson)
                .onSuccess { _uiState.update { it.copy(editingInsight = null) } }
                .onFailure { e ->
                    Log.e(TAG, "Failed to update insight $insightId: ${e.message}")
                    _uiState.update { it.copy(error = DetailsError.Insight) }
                }
        }
    }

    // ========== Errors ==========

    /** The screen reports what it could not do itself: save, share or copy the text. */
    fun reportError(error: DetailsError) {
        _uiState.update { it.copy(error = error) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    // ========== Rewriting the notes ==========

    /** Open the sheet that asks before the current notes are replaced. */
    fun showRewriteConfirm() {
        _uiState.update { it.copy(showRewriteConfirm = true) }
    }

    fun hideRewriteConfirm() {
        _uiState.update { it.copy(showRewriteConfirm = false) }
    }

    /**
     * Write the notes of this recording again, in place of the old ones.
     *
     * Progress is in [SessionDetailsUiState.rewrite]. The old notes are replaced only once the
     * new ones are complete, so a failure ([SessionDetailsUiState.error]), a too short
     * transcript or [cancelRewrite] before the swap leaves them as they were. Leaving the screen
     * cancels as well, and so does a recorder that leaves IDLE ([runUntilCapture]); a swap begun
     * runs to its end, and a stop that comes then tells nothing. It runs only while the AI is
     * free, and holds it until the model is released again.
     */
    fun rewriteNotes() {
        val state = _uiState.value
        if (state.rewrite != null || state.todoSearch != null) return
        _uiState.update { it.copy(showRewriteConfirm = false, error = null) }

        rewriteJob = viewModelScope.launch {
            // Set once the new notes are swapped in, which runs to its end also after a stop.
            var replaced = false
            val ran = aiGate.runGated(AiWorkGate.REWRITE, refused = { finishingQueue.busy.value }, release = ::releaseModel) {
                activeRecorder.current.runUntilCapture(onStopped = { if (!replaced) stoppedForRecording(StoppedWork.Kind.REWRITE_NOTES) }) {
                    rewrite { replaced = true }
                }
            }
            if (!ran) reportError(DetailsError.AiBusy)
        }
    }

    /**
     * The rewrite itself, holding the AI: its progress, the model, the swap of the notes.
     *
     * @param onReplaced Called once the new notes are swapped in, before a cancel can come back instead of the result
     */
    private suspend fun rewrite(onReplaced: () -> Unit) {
        _uiState.update { it.copy(rewrite = RewriteProgress()) }
        try {
            // Keeps the process alive if the app goes to the background meanwhile. Inside the
            // try, so a refused start still ends in the finally block.
            llmProcessingServiceController.startProcessing(ProcessingHolders.rewrite(sessionId))

            val settings = settingsRepository.getSettings().first()
            val modelPath = withContext(Dispatchers.IO) { installedModelPath(settings) }
            if (!settings.llmEnabled || modelPath == null) {
                reportError(DetailsError.NoModel)
                return
            }
            // Apply conservative threads before loading if the device was previously
            // detected as constrained (cached in DataStore across sessions).
            if (settings.memoryConstrainedDetected) llmRepository.useConservativeThreads()
            // The model is loaded by the first pass of the pipeline (loadImmediately = false);
            // here it only learns the path. Post-load checks are in GenerateBatchInsightUseCase.
            // The live model is the one to fall back on when Gemma 4 E2B is swapped in for
            // the rewrite and fails to load.
            initializeLlmUseCase(modelPath, loadImmediately = false).onFailure { e ->
                Log.e(TAG, "Rewrite: LLM initialization failed", e)
                reportError(DetailsError.ModelFailed)
                return
            }
            if (!readingsReady(EnsureReadingsUseCase.Work.REWRITE)) {
                reportError(DetailsError.RewriteFailed)
                return
            }

            val result = rewriteNotesUseCase(
                sessionId = sessionId,
                // A recorder that is open owns the loaded model: no E2B next to its speech model.
                mayChangeModel = activeRecorder.mayRelease(this@SessionDetailsViewModel),
                onModel = { model -> logModel("Rewrite", model) },
                onProgress = { progress ->
                    _uiState.update { it.copy(rewrite = it.rewrite?.copy(progress = progress)) }
                }
            )
            if (result.getOrNull() == RewriteOutcome.Replaced) onReplaced()
            // A cancel can come back as a failed Result; it must not be reported as a failure.
            currentCoroutineContext().ensureActive()
            result.onSuccess { outcome ->
                if (outcome == RewriteOutcome.TooLittle) reportError(DetailsError.TooShort)
            }.onFailure { e ->
                Log.e(TAG, "Rewriting the notes failed", e)
                reportError(DetailsError.RewriteFailed)
            }
        } finally {
            // The model is released and the gate opened after this, also after Cancel or when
            // the screen closes ([runGated]).
            _uiState.update { it.copy(rewrite = null) }
            llmProcessingServiceController.stopProcessing(ProcessingHolders.rewrite(sessionId))
            rewriteJob = null
        }
    }

    // ========== Changing the type ==========

    /**
     * Records this recording as the category [categoryId] from now on ("Change type"): its
     * category and its mode are stored together, then, when the category records in another mode,
     * its notes are written again from the transcript with the new mode's prompt, as Rewrite notes
     * writes them: the meeting notes with their action items, say. Its name and to-dos stay; "Find
     * to-dos and ideas again" looks for them anew. A category in the same mode, or a recording the
     * AI can't write notes for ([SessionDetailsUiState.rewritesNotesFor]), only changes the type:
     * no model loads and the notes stay as they are, edits and all.
     *
     * As the menu offers it ([SessionDetailsUiState.changeTypeOffer]): not while other work of the
     * page runs, nor while this recording is still being finished, whose finishing reads its mode.
     * With a rewrite it holds the AI from before the type is stored until the new notes are in, and
     * runs only while the AI is free and no recording waits to be finished. The recorder wins: a
     * recorder that leaves IDLE stops the rewrite as it stops Rewrite notes ([runUntilCapture]);
     * the type stays changed and the old notes stay, which the page says once, opened again. A
     * notice says what the recording is now.
     */
    fun changeType(categoryId: String) {
        val state = _uiState.value
        if (state.rewrite != null || state.todoSearch != null || state.finishing != null) return
        _uiState.update { it.copy(error = null) }
        if (!state.rewritesNotesFor(categoryId)) {
            viewModelScope.launch { storeType(categoryId) }
            return
        }
        rewriteJob = viewModelScope.launch {
            // Set once the new notes are swapped in, which runs to its end also after a stop.
            var replaced = false
            val ran = aiGate.runGated(AiWorkGate.REWRITE, refused = { finishingQueue.busy.value }, release = ::releaseModel) {
                activeRecorder.current.runUntilCapture(onStopped = { if (!replaced) stoppedForRecording(StoppedWork.Kind.REWRITE_NOTES) }) {
                    // A store begun goes through, so the type the notice names is the stored one.
                    if (withContext(NonCancellable) { storeType(categoryId) }) rewrite { replaced = true }
                }
            }
            if (!ran) reportError(DetailsError.AiBusy)
        }
    }

    /**
     * Stores [categoryId] as what this recording is recorded as ([ChangeRecordingTypeUseCase]):
     * the notice names it, and what kept it from being stored is the page's error.
     *
     * @return Whether the recording changed
     */
    private suspend fun storeType(categoryId: String): Boolean = when (val outcome = changeRecordingType(sessionId, categoryId)) {
        is ChangeRecordingTypeUseCase.Outcome.Changed -> {
            Log.i(TAG, "Recording $sessionId changed to ${outcome.category.id} (${outcome.category.mode}) on its page")
            _notices.trySend(DetailsNotice.TypeChanged(outcome.category.name))
            true
        }
        ChangeRecordingTypeUseCase.Outcome.Unchanged -> false
        ChangeRecordingTypeUseCase.Outcome.Refused -> {
            Log.w(TAG, "Recording $sessionId not changed to $categoryId: it or the category is gone, or translates")
            reportError(DetailsError.ChangeType)
            false
        }
        is ChangeRecordingTypeUseCase.Outcome.Failed -> {
            Log.e(TAG, "Recording $sessionId not changed to $categoryId", outcome.cause)
            reportError(DetailsError.ChangeType)
            false
        }
    }

    /**
     * Records this recording in [language] from now on (the language row of "Change type"). Only
     * the language is stored: the transcript stays as the model of the language it was recorded in
     * made it, and its notes, name and to-dos stay; Transcribe again makes the transcript anew, and
     * Rewrite notes the notes. As the menu offers it ([SessionDetailsUiState.changeTypeOffer]): not
     * while other work of the page runs, nor while this recording is still being finished, whose
     * finishing reads its language; Malayalam only while its model is on the phone. A notice says
     * what the recording is in now.
     */
    fun changeLanguage(language: RecordingLanguage) {
        val state = _uiState.value
        if (state.rewrite != null || state.todoSearch != null || state.finishing != null) return
        if (!state.changesLanguage) return
        _uiState.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val outcome = changeRecordingLanguage.onPage(sessionId, language)) {
                is ChangeRecordingLanguageUseCase.Outcome.Changed -> {
                    Log.i(TAG, "Recording $sessionId changed to $language on its page")
                    _notices.trySend(DetailsNotice.LanguageChanged(language))
                }
                ChangeRecordingLanguageUseCase.Outcome.Unchanged -> Unit
                ChangeRecordingLanguageUseCase.Outcome.Refused -> {
                    Log.w(TAG, "Recording $sessionId not changed to $language: it is gone or translates, or the Malayalam model isn't on the phone")
                    reportError(DetailsError.ChangeLanguage)
                }
                is ChangeRecordingLanguageUseCase.Outcome.Failed -> {
                    Log.e(TAG, "Recording $sessionId not changed to $language", outcome.cause)
                    reportError(DetailsError.ChangeLanguage)
                }
            }
        }
    }

    /** Stop the running rewrite. The old notes stay as they are. */
    fun cancelRewrite() {
        val job = rewriteJob?.takeIf { it.isActive } ?: return
        _uiState.update { it.copy(rewrite = it.rewrite?.copy(stopping = true)) }
        job.cancel()
    }

    // ========== Finding to-dos and ideas again ==========

    /**
     * Looks for this recording's to-dos and ideas again ([ExtractSessionTodosUseCase]), on the
     * model of the work after Stop as a rewrite does, and keeps the new ones; those found before
     * stay as they are. The count of what it added comes as a notice ([notices]), a failure as
     * [DetailsError.FindFailed]. It runs only while the AI is free and holds it until the model is
     * released again; Cancel ([cancelTodoSearch]), leaving the page or a recorder that leaves IDLE
     * ([runUntilCapture]) ends it, storing nothing. Once the model has answered, what it found is
     * stored to the end ([ExtractSessionTodosUseCase]), and a stop that comes then tells nothing.
     */
    fun findTodosAgain() {
        val state = _uiState.value
        if (state.rewrite != null || state.todoSearch != null) return

        todoSearchJob = viewModelScope.launch {
            // Set once the search has stored what it found, which runs to its end also after a stop.
            var stored = false
            val ran = aiGate.runGated(AiWorkGate.TODOS, refused = { finishingQueue.busy.value }, release = ::releaseModel) {
                activeRecorder.current.runUntilCapture(onStopped = { if (!stored) stoppedForRecording(StoppedWork.Kind.FIND_TODOS) }) {
                    _uiState.update { it.copy(todoSearch = TodoSearch(), error = null) }
                    try {
                        // The search takes the model a minute or so: the service keeps the process going.
                        llmProcessingServiceController.startProcessing(ProcessingHolders.todos(sessionId))
                        val started = SystemClock.elapsedRealtime()
                        val found = searchTodos { stored = true }
                        val seconds = (SystemClock.elapsedRealtime() - started) / 1000
                        if (found != null) {
                            Log.i(TAG, "Find again: ${found.todos} to-dos, ${found.ideas} ideas in $seconds s")
                            _notices.trySend(DetailsNotice.Found(found))
                        } else {
                            Log.w(TAG, "Find again: nothing stored after $seconds s")
                        }
                    } finally {
                        // The model is released and the gate opened after this, also after Cancel.
                        _uiState.update { it.copy(todoSearch = null) }
                        llmProcessingServiceController.stopProcessing(ProcessingHolders.todos(sessionId))
                        todoSearchJob = null
                    }
                }
            }
            if (!ran) reportError(DetailsError.AiBusy)
        }
    }

    /**
     * A recording started and stopped [kind], which kept what was there before it: the
     * recording's page, opened again, says so once ([StoppedWork]). A quick-key start closes this
     * one meanwhile.
     *
     * @param work What stopped, for the field log
     */
    private fun stoppedForRecording(
        kind: StoppedWork.Kind,
        work: String = if (kind == StoppedWork.Kind.REWRITE_NOTES) "Rewrite" else "Find again"
    ) {
        Log.i(TAG, "$work of $sessionId stopped: a recording started; what was there stays")
        stoppedWork.post(kind, sessionId, poster = this)
    }

    // ========== The text again, after Transcribe again ==========

    /**
     * Makes the recording's name, notes, to-dos and ideas again from the transcript Transcribe
     * again has just put in, in another language than the old one ([MakeTextAgainUseCase]), each in
     * place of what the old transcript gave it once it is ready: the notes as Rewrite notes writes
     * them but without the recording's name as their topic, the name, then the to-dos and ideas, a
     * Malayalam recording's all in English. The progress is that of Rewrite notes
     * ([SessionDetailsUiState.rewrite]) while the notes and the name are written, then that of the
     * search ([SessionDetailsUiState.todoSearch]), and the Cancel of either stops it. So do leaving
     * the page and a recorder that leaves IDLE ([runUntilCapture]): what was stored stays, the old
     * name, notes, to-dos and ideas that nothing replaced yet stay too, and the page, opened again,
     * says once which part stopped (none once the search has stored what it found). Refused (the
     * AI busy, a recording waiting to be finished) it changes nothing. The search's notice says what
     * it found.
     *
     * As the page offers it ([SessionDetailsUiState.makesTextAgain]), and not while other work of
     * the page runs. It runs only while the AI is free and no recording waits to be finished,
     * holds it to the end, and frees the model after.
     */
    fun makeTextAgain() {
        val state = _uiState.value
        if (state.rewrite != null || state.todoSearch != null || !state.makesTextAgain) return
        _uiState.update { it.copy(error = null) }

        val job = viewModelScope.launch {
            // The step a stop would cut short, which the page then names; none once all is stored.
            var step: MakeTextAgainUseCase.Step? = MakeTextAgainUseCase.Step.Notes
            val ran = aiGate.runGated(AiWorkGate.REWRITE, refused = { finishingQueue.busy.value }, release = ::releaseModel) {
                activeRecorder.current.runUntilCapture(onStopped = { step?.let { stoppedForRecording(stopOf(it), work = "Text again") } }) {
                    makeAgain(onStep = { step = it }, onStored = { step = null })
                }
            }
            if (!ran) reportError(DetailsError.AiBusy)
        }
        // Either Cancel stops it.
        rewriteJob = job
        todoSearchJob = job
    }

    /** What the page says of [step] stopped: the notes not written, or the to-dos and ideas not found. */
    private fun stopOf(step: MakeTextAgainUseCase.Step): StoppedWork.Kind = when (step) {
        MakeTextAgainUseCase.Step.Notes -> StoppedWork.Kind.REWRITE_NOTES
        MakeTextAgainUseCase.Step.Name, MakeTextAgainUseCase.Step.Todos -> StoppedWork.Kind.FIND_TODOS
    }

    /**
     * The work itself, holding the AI: the model, the three steps with their progress, the notices.
     *
     * @param onStep Called as each step begins
     * @param onStored Called once all is stored, before a cancel can come back instead of the result
     */
    private suspend fun makeAgain(onStep: (MakeTextAgainUseCase.Step) -> Unit, onStored: () -> Unit) {
        _uiState.update { it.copy(rewrite = RewriteProgress()) }
        try {
            // Keeps the process alive if the app goes to the background meanwhile. Inside the
            // try, so a refused start still ends in the finally block.
            llmProcessingServiceController.startProcessing(ProcessingHolders.rewrite(sessionId))
            val settings = settingsRepository.getSettings().first()
            val modelPath = withContext(Dispatchers.IO) { installedModelPath(settings) }
            if (!settings.llmEnabled || modelPath == null) {
                reportError(DetailsError.NoModel)
                return
            }
            if (settings.memoryConstrainedDetected) llmRepository.useConservativeThreads()
            // Registers the live model, the one to fall back on when E2B can't load.
            initializeLlmUseCase(modelPath, loadImmediately = false).onFailure { e ->
                Log.e(TAG, "Text again: LLM initialization failed", e)
                reportError(DetailsError.ModelFailed)
                return
            }
            if (!readingsReady(EnsureReadingsUseCase.Work.TEXT_AGAIN)) {
                reportError(DetailsError.RewriteFailed)
                return
            }
            val started = SystemClock.elapsedRealtime()
            val before = todoRepository.observeForSession(sessionId).first()
            // A recorder that is open owns the loaded model: no E2B next to its speech model.
            val mayChangeModel = activeRecorder.mayRelease(this@SessionDetailsViewModel)
            // Off the main thread, as the finishing runs it: the search reads the whole transcript.
            val result = withContext(Dispatchers.Default) {
                makeTextAgainUseCase(
                    sessionId = sessionId,
                    mayChangeModel = mayChangeModel,
                    onModel = { model -> logModel("Text again", model) },
                    onStep = { step ->
                        onStep(step)
                        if (step == MakeTextAgainUseCase.Step.Todos) _uiState.update { it.copy(rewrite = null, todoSearch = TodoSearch()) }
                    },
                    onNotesProgress = { progress -> _uiState.update { it.copy(rewrite = it.rewrite?.copy(progress = progress)) } }
                ).also { onStored() }
            }
            // A cancel can come back as the result; it must not be reported.
            currentCoroutineContext().ensureActive()
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000
            result.onSuccess { made ->
                Log.i(TAG, "Text again of $sessionId: ${madeText(made)} in $seconds s")
                when {
                    made.notes == MakeTextAgainUseCase.Notes.Failed -> reportError(DetailsError.RewriteFailed)
                    made.todos == ExtractSessionTodosUseCase.Outcome.Failed -> reportError(DetailsError.FindFailed)
                    made.notes == MakeTextAgainUseCase.Notes.TooLittle -> reportError(DetailsError.TooShort)
                }
                if (made.todos is ExtractSessionTodosUseCase.Outcome.Found) {
                    _notices.trySend(DetailsNotice.Found(TodosFound.between(before, todoRepository.observeForSession(sessionId).first())))
                }
            }.onFailure { e ->
                Log.e(TAG, "Text again of $sessionId failed", e)
                reportError(DetailsError.RewriteFailed)
            }
        } finally {
            // The model is released and the gate opened after this, also after Cancel.
            _uiState.update { it.copy(rewrite = null, todoSearch = null) }
            llmProcessingServiceController.stopProcessing(ProcessingHolders.rewrite(sessionId))
            rewriteJob = null
            todoSearchJob = null
        }
    }

    /** What [made] holds, for the field log: counts and states, never a text. */
    private fun madeText(made: MakeTextAgainUseCase.Outcome): String {
        val notes = when (made.notes) {
            MakeTextAgainUseCase.Notes.Written -> "notes written"
            MakeTextAgainUseCase.Notes.TooLittle -> "too little text for notes, none kept"
            MakeTextAgainUseCase.Notes.Failed -> "notes failed, the old ones stay"
        }
        val name = if (made.name) "name written" else "no name written"
        val todos = when (val found = made.todos) {
            is ExtractSessionTodosUseCase.Outcome.Found -> "${found.count - found.ideas} to-dos, ${found.ideas} ideas found"
            ExtractSessionTodosUseCase.Outcome.Skipped -> "no search"
            ExtractSessionTodosUseCase.Outcome.Failed -> "the search failed"
        }
        val old = "found before: ${made.oldFinds.removed} removed, ${made.oldFinds.kept} kept"
        return "$notes, $name, $todos; $old"
    }

    /**
     * Sets the model up and runs the search: what it added, or null when it couldn't run or
     * failed, with the reason set as the error. Throws only [CancellationException].
     *
     * @param onStored Called once the search has stored what it found, before a cancel can come back instead of the result
     */
    private suspend fun searchTodos(onStored: () -> Unit): TodosFound? {
        try {
            val settings = settingsRepository.getSettings().first()
            val modelPath = withContext(Dispatchers.IO) { installedModelPath(settings) }
            if (!settings.llmEnabled || modelPath == null) {
                reportError(DetailsError.NoModel)
                return null
            }
            if (settings.memoryConstrainedDetected) llmRepository.useConservativeThreads()
            // Registers the live model, the one the search falls back on when E2B can't load.
            initializeLlmUseCase(modelPath, loadImmediately = false).onFailure { e ->
                Log.e(TAG, "Find again: LLM initialization failed", e)
                reportError(DetailsError.ModelFailed)
                return null
            }
            if (!readingsReady(EnsureReadingsUseCase.Work.FIND_AGAIN)) {
                reportError(DetailsError.FindFailed)
                return null
            }
            // A recorder that is open owns the loaded model: no E2B next to its speech model.
            if (activeRecorder.mayRelease(this@SessionDetailsViewModel)) logModel("Find again", loadFinishingModel())
            val before = todoRepository.observeForSession(sessionId).first()
            // Off the main thread, as the finishing runs it: it reads the whole transcript.
            val outcome = withContext(Dispatchers.Default) {
                extractSessionTodos(sessionId).also { if (it is ExtractSessionTodosUseCase.Outcome.Found) onStored() }
            }
            // A cancel can come back as a failed search; it must not be reported as a failure.
            currentCoroutineContext().ensureActive()
            if (outcome == ExtractSessionTodosUseCase.Outcome.Failed) {
                reportError(DetailsError.FindFailed)
                return null
            }
            return TodosFound.between(before, todoRepository.observeForSession(sessionId).first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The settings or the to-dos couldn't be read; the search itself never throws.
            Log.e(TAG, "Find again failed", e)
            reportError(DetailsError.FindFailed)
            return null
        }
    }

    /**
     * Stop the running search for to-dos and ideas. Nothing it found is stored, unless the model
     * has answered already: then all of it is.
     */
    fun cancelTodoSearch() {
        val job = todoSearchJob?.takeIf { it.isActive } ?: return
        _uiState.update { it.copy(todoSearch = it.todoSearch?.copy(stopping = true)) }
        job.cancel()
    }

    // ========== The recording's to-dos ==========

    /** Opens the sheet of [todo]. */
    fun openTodo(todo: Todo) {
        _uiState.update { it.copy(openTodo = todo) }
    }

    fun closeTodo() {
        _uiState.update { it.copy(openTodo = null) }
    }

    /** Stores the new text and due day and time of the to-do of the open sheet, and closes it. */
    fun saveOpenTodo(text: String, date: LocalDate?, time: LocalTime?) {
        val todo = takeOpenTodo() ?: return
        changeTodos { todoActions.edit(todo.id, text, date, time, ZoneId.systemDefault()) }
    }

    /** Deletes the to-do of the open sheet and closes it. */
    fun deleteOpenTodo() {
        val todo = takeOpenTodo() ?: return
        changeTodos { todoActions.delete(todo.id, TodoSource.PAGE) }
    }

    /** Moves the to-do of the open sheet to the ideas, where nothing reminds, and closes it. */
    fun moveOpenTodoToIdeas() {
        val todo = takeOpenTodo() ?: return
        changeTodos { todoActions.moveToIdeas(todo.id, TodoSource.PAGE) }
    }

    fun completeTodo(id: String) = changeTodos { todoActions.complete(id, TodoSource.PAGE) }

    /** Takes [completeTodo] back. */
    fun reopenTodo(id: String) = changeTodos { todoActions.reopen(id) }

    fun keepSuggestions(suggestions: List<Todo>) = changeTodos { todoActions.keep(suggestions, TodoSource.PAGE) }

    fun dismissSuggestion(id: String) = changeTodos { todoActions.dismiss(id, TodoSource.PAGE) }

    /** Moves the suggested to-do [id] to the ideas, from the suggestions sheet. */
    fun moveSuggestionToIdeas(id: String) = changeTodos { todoActions.moveToIdeas(id, TodoSource.PAGE) }

    /** The to-do of the open sheet, whose sheet closes. */
    private fun takeOpenTodo(): Todo? {
        val todo = _uiState.value.openTodo ?: return null
        _uiState.update { it.copy(openTodo = null) }
        return todo
    }

    private fun changeTodos(change: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                change()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change a to-do", e)
                reportError(DetailsError.Todo)
            }
        }
    }

    // ========== The model ==========

    /**
     * Makes the readings the recording's lines only the reader reads lack, for [work], holding the
     * AI as the work does and before its model loads, which the reader never loads beside
     * ([EnsureReadingsUseCase]): whether the work may read the recording. Without them it doesn't
     * run, and what the recording has stays: never notes, a name or to-dos of the AI model's of the
     * lines as they were said, nor of the rest of the recording (D7). The field log says why.
     */
    private suspend fun readingsReady(work: EnsureReadingsUseCase.Work): Boolean =
        withContext(Dispatchers.Default) { ensureReadings(sessionId, work) } == EnsureReadingsUseCase.Outcome.Ready

    /** Frees the RAM the model took; it is not needed once the work is over. Never throws. */
    private suspend fun releaseModel() {
        try {
            llmRepository.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing the LLM after the work of the recording's page", e)
        }
    }

    /** The model [work] ("Rewrite", "Find again") runs on, for the field log. */
    private fun logModel(work: String, model: FinishingModel) {
        when (model) {
            FinishingModel.Live -> Log.i(TAG, "$work on the live model")
            FinishingModel.Switched -> Log.i(TAG, "$work on Gemma 4 E2B")
            is FinishingModel.FellBack -> Log.w(
                TAG,
                "Gemma 4 E2B could not be loaded, ${work.replaceFirstChar(Char::lowercaseChar)} on the live model",
                model.cause
            )
        }
    }

    /**
     * The path of an AI model on disk: the one chosen in Settings, else any other variant that
     * is installed, as the recorder does so that picking a variant without downloading it doesn't
     * block the AI. Null when none is installed. Gemma 4 E2B alone is none ([LlmModelChoice]).
     */
    private fun installedModelPath(settings: AppSettings): String? {
        val variant = LlmModelChoice.pick(
            selected = settings.llmModelVariant,
            recommended = null,
            installed = modelDownloadManager.installedLlmVariants()
        ) ?: return null
        return modelDownloadManager.getLlmModelPath(modelConfigForVariant(variant).llmFilename)
    }
}
