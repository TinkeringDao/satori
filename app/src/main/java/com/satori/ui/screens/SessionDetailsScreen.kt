package com.satori.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Resources
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingCategory
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.presentation.sessiondetails.AiOffer
import com.satori.presentation.sessiondetails.AudioError
import com.satori.presentation.sessiondetails.AudioNotice
import com.satori.presentation.sessiondetails.DetailsError
import com.satori.presentation.sessiondetails.DetailsNotice
import com.satori.presentation.sessiondetails.DetailsTab
import com.satori.presentation.sessiondetails.RecordingAudioViewModel
import com.satori.presentation.sessiondetails.RecordingPlaceViewModel
import com.satori.presentation.sessiondetails.SessionDetailsViewModel
import com.satori.presentation.sessiondetails.TodosFound
import com.satori.ui.R
import com.satori.ui.components.RecordingLanguageRow
import com.satori.ui.components.RecordingTypeSheet
import com.satori.ui.components.TodoSheet
import com.satori.ui.components.TodoSuggestionsSheet
import com.satori.ui.components.recordingLanguageName
import com.satori.ui.components.recordingLanguageNameRes
import com.satori.ui.components.rememberMinuteClock
import com.satori.ui.design.CircleIconButton
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.DotProgress
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.NoticeResult
import com.satori.ui.design.PillAction
import com.satori.ui.design.modeLabel
import com.satori.ui.design.rememberNoticeState
import com.satori.ui.export.AudioShare
import com.satori.ui.export.MarkdownExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One recording: its title (tap to rename), date and length, then the transcript and the AI's
 * notes in two text tabs. Share, save, copy (what the open tab shows), rewrite the notes, rename
 * and delete are in the overflow sheet. Lines and notes are edited with a long press.
 *
 * A recording that kept its audio has a player docked under the page, a tapped transcript line
 * plays from where it was said, and the sheet can share the audio and transcribe it again, with a
 * speech model picked in a sheet of its own. The audio stops when the page closes or another
 * screen opens over it.
 *
 * Search can open the screen on a tab and scroll it to a line or a note; the ViewModel reads
 * both from the `initialTab` and `highlightId` navigation arguments. Without a tab it opens on
 * the notes when there are any.
 *
 * A recording with a place shows it under the date; a tap opens its sheet (map, removal), and the
 * Markdown file and the copies carry it too.
 *
 * A recording is finished in the background after Stop. While it is, its step shows under the
 * date (with Skip for the better transcript), the notes tab says the notes are being written, and
 * the lines can't be edited while they may still be replaced. The page's AI work (writing the
 * notes, Transcribe again, a new search for to-dos and ideas) waits while the AI is in use, and
 * says so in the menu. Playing goes on.
 *
 * The notes tab starts with the recording's own to-dos (ticked off with Undo, opened in their
 * sheet), its suggestions and its ideas, which open the Ideas page. The menu also keeps the audio
 * out of the settings that compress or delete older audio, or deletes it, after asking.
 *
 * "Change type" in the menu records the recording as another category, in the sheet of the
 * recorder's Record as, and writes its notes anew in that category's mode on the notes tab. Its
 * language row stores the language the recording is made in; the transcript stays as it is.
 *
 * @param onNavigateBack Leaves the screen; also called once the recording is deleted.
 * @param onNavigateToLlmDownload Opens the download of the AI model, for a recording whose notes
 *   can't be written without one.
 * @param onNavigateToSettings Opens Settings, where AI notes are switched on.
 * @param onNavigateToMindMap Opens the recording's own mind map, with the id of the recording.
 * @param onNavigateToIdeas Opens the ideas found in recordings, from one of this recording's.
 * @param onNavigateToSpeechModels Opens Advanced settings, where the Malayalam model downloads, from
 *   the link the language row has while it isn't on the phone.
 */
@Composable
fun SessionDetailsScreen(
    onNavigateBack: () -> Unit = {},
    onNavigateToLlmDownload: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToMindMap: (sessionId: String) -> Unit = {},
    onNavigateToIdeas: () -> Unit = {},
    onNavigateToSpeechModels: () -> Unit = {},
    viewModel: SessionDetailsViewModel = hiltViewModel(),
    audioViewModel: RecordingAudioViewModel = hiltViewModel(),
    placeViewModel: RecordingPlaceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioState by audioViewModel.uiState.collectAsStateWithLifecycle()
    val placeState by placeViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notices = rememberNoticeState()
    val details = uiState.sessionDetails
    val audio = audioState.audio
    // Where it was made, as it reads under the date, in the Markdown file and in a copy.
    val placeLabel = PlaceText.shown(details?.session?.place, placeState.label)
    val place = placeLabel?.let { placeText(it) }
    val errorText = uiState.error?.let { errorMessage(it, hasNotes = details?.insights?.isNotEmpty() == true) }
    // What went wrong with the place or the audio is the newer news, and the one shown.
    val shownError = placeState.error?.let { placeErrorMessage(it) } ?: audioState.error?.let { audioErrorMessage(it) } ?: errorText
    val dismissError = {
        when {
            placeState.error != null -> placeViewModel.clearError()
            audioState.error != null -> audioViewModel.clearError()
            else -> viewModel.clearError()
        }
    }

    // The user's own choice of tab; until there is one, the page opens where the ViewModel decided.
    var chosenTab by rememberSaveable { mutableIntStateOf(NO_TAB) }
    val selectedTab = if (chosenTab != NO_TAB) chosenTab else uiState.openingTab ?: DetailsTab.TRANSCRIPT
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var placeSheet by rememberSaveable { mutableStateOf<PlaceSheet?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var showSuggestions by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteAudio by rememberSaveable { mutableStateOf(false) }
    // The sheet of "Change type", open from the menu.
    var choosingType by rememberSaveable { mutableStateOf(false) }

    // Settings and the model downloads change what the AI and the speech model can do; look again
    // on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshNotesAi()
        viewModel.lookForMalayalam()
        audioViewModel.refresh()
    }
    // One player at a time, and none for a page that is not on screen.
    DisposableEffect(audioViewModel) { onDispose { audioViewModel.stopPlayback() } }

    // Leaving ends a running rewrite, search for to-dos and ideas or transcription, which take a
    // minute or more of the model's work: ask before throwing that away.
    val rewriting = uiState.rewrite != null
    val transcribing = audioState.transcription != null
    val searching = uiState.todoSearch != null
    val busy = rewriting || transcribing || searching
    val leave = { if (busy) confirmLeave = true else onNavigateBack() }
    BackHandler(enabled = busy) { confirmLeave = true }
    // Work that ends on its own takes the question with it.
    LaunchedEffect(busy) { if (!busy) confirmLeave = false }
    // Shared copies are kept a day for the app that received them, then removed.
    LaunchedEffect(Unit) { MarkdownExport.pruneShared(context) }

    val shareTitle = stringResource(R.string.g_details_share_title)
    val shareAudioTitle = stringResource(R.string.g_audio_share_title)
    val savedNotice = stringResource(R.string.g_details_saved)
    val copiedNotice = stringResource(R.string.g_details_copied)
    val transcribedNotice = stringResource(R.string.g_audio_transcribed)
    val keptNotice = stringResource(R.string.g_audio_kept)
    val unkeptNotice = stringResource(R.string.g_audio_unkept)
    val deletedNotice = stringResource(R.string.g_audio_deleted)
    val transcribeStoppedNotice = stringResource(R.string.g_audio_transcribe_stopped_recording)
    val doneText = stringResource(R.string.g_todo_done)
    val undoText = stringResource(R.string.g_todo_undo)
    LaunchedEffect(audioViewModel) {
        audioViewModel.notices.collect { notice ->
            val text = when (notice) {
                AudioNotice.Transcribed -> transcribedNotice
                AudioNotice.Kept -> keptNotice
                AudioNotice.Unkept -> unkeptNotice
                AudioNotice.Deleted -> deletedNotice
                AudioNotice.TranscribeStopped -> transcribeStoppedNotice
                AudioNotice.TextToMakeAgain -> {
                    // Made again in another language: the name, notes, to-dos and ideas are made
                    // again from it, a Malayalam recording's in English, where the page does that.
                    // The work takes the AI as Transcribe again has let go of it, and says itself
                    // if it can't.
                    if (uiState.makesTextAgain) {
                        chosenTab = DetailsTab.NOTES
                        viewModel.makeTextAgain()
                    }
                    return@collect
                }
            }
            // Beside the collector, so what comes next is taken up at once; a newer notice replaces it.
            scope.launch { notices.show(text) }
        }
    }
    val resources = context.resources
    LaunchedEffect(viewModel, resources) {
        viewModel.notices.collect { notice ->
            notices.show(
                when (notice) {
                    is DetailsNotice.Found -> foundText(resources, notice.found)
                    DetailsNotice.RewriteStopped -> resources.getString(R.string.g_details_rewrite_stopped_recording)
                    DetailsNotice.SearchStopped -> resources.getString(R.string.g_details_find_stopped_recording)
                    is DetailsNotice.TypeChanged -> resources.getString(R.string.g_details_type_changed, notice.name)
                    is DetailsNotice.LanguageChanged -> resources.getString(
                        R.string.g_details_type_changed,
                        resources.getString(recordingLanguageNameRes(notice.language))
                    )
                }
            )
        }
    }

    fun shareMarkdown(current: SessionWithDetails) {
        scope.launch {
            try {
                val uri = MarkdownExport.prepareShare(context, current, place)
                val subject = MarkdownExport.title(context, current)
                context.startActivity(MarkdownExport.shareIntent(context, uri, subject, shareTitle))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.reportError(DetailsError.Share)
            }
        }
    }

    fun saveToDownloads(current: SessionWithDetails) {
        scope.launch {
            val saved = try {
                MarkdownExport.saveToDownloads(context, current, place)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                viewModel.reportError(DetailsError.Save)
                false
            }
            if (saved) notices.show(savedNotice)
        }
    }

    fun shareAudio(current: SessionWithDetails, kept: RecordingAudio) {
        try {
            val uri = AudioShare.uri(context, current, kept)
            val subject = MarkdownExport.title(context, current)
            context.startActivity(MarkdownExport.shareIntent(context, uri, subject, shareAudioTitle, AudioShare.mimeType(kept)))
        } catch (e: RuntimeException) {
            // No app to share with, or the file is no longer there.
            viewModel.reportError(DetailsError.Share)
        }
    }

    fun copyToClipboard(current: SessionWithDetails, text: String) {
        val clip = ClipData.newPlainText(MarkdownExport.title(context, current), text)
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        } catch (e: RuntimeException) {
            // The system refuses text too large for the clipboard.
            viewModel.reportError(DetailsError.Copy)
            return
        }
        // Android 13 and later confirm a copy themselves, where the notice would show.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { notices.show(copiedNotice) }
    }

    fun writeNotes() {
        // The progress is on the notes tab.
        chosenTab = DetailsTab.NOTES
        viewModel.rewriteNotes()
    }

    fun transcribeAgain(model: SpeechModelId) {
        // The progress is on the transcript tab, above the lines it replaces.
        chosenTab = DetailsTab.TRANSCRIPT
        audioViewModel.transcribeAgain(model, makesTextAgain = uiState.makesTextAgain)
    }

    fun findTodosAgain() {
        // The search, and what it finds, are at the top of the notes tab.
        chosenTab = DetailsTab.NOTES
        viewModel.findTodosAgain()
    }

    fun changeType(category: RecordingCategory) {
        // The notes written anew show their progress on the notes tab.
        if (uiState.rewritesNotesFor(category.id)) chosenTab = DetailsTab.NOTES
        viewModel.changeType(category.id)
    }

    val completeTodo: (Todo) -> Unit = { todo ->
        viewModel.completeTodo(todo.id)
        scope.launch {
            if (notices.show(doneText, undoText) == NoticeResult.ActionPerformed) viewModel.reopenTodo(todo.id)
        }
    }

    val actions = DetailsActions(
        onRename = viewModel::showRenameDialog,
        onEditSegment = viewModel::showEditSegmentDialog,
        onEditInsight = viewModel::showEditInsightDialog,
        onWriteNotes = ::writeNotes,
        onCancelRewrite = viewModel::cancelRewrite,
        onGetModel = onNavigateToLlmDownload,
        onOpenSettings = onNavigateToSettings,
        onDelete = viewModel::showDeleteConfirmation,
        onCancelTranscription = audioViewModel::cancelTranscription,
        onOpenPlace = { placeSheet = PlaceSheet.DETAILS },
        onSkipTranscript = viewModel::skipBetterTranscript,
        onOpenTodo = viewModel::openTodo,
        onCompleteTodo = completeTodo,
        onShowSuggestions = { showSuggestions = true },
        onOpenIdeas = onNavigateToIdeas,
        onCancelTodoSearch = viewModel::cancelTodoSearch
    )
    val transcriptAudio = audio?.let {
        remember(it, audioViewModel) { TranscriptAudio(it, audioViewModel.playback, audioViewModel::playFrom) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            GrilleTopBar(
                title = null,
                onBack = leave,
                backDescription = stringResource(R.string.g_back),
                actions = {
                    if (details != null && !uiState.deleting) {
                        CircleIconButton(
                            icon = GrilleIcons.More,
                            contentDescription = stringResource(R.string.g_details_more),
                            onClick = { showMenu = true }
                        )
                    }
                }
            )
            when {
                // Once delete is confirmed, until the page closes: the delete may first wait for a
                // finishing step to end, and once the recording is gone the page shows no note that
                // it no longer exists.
                uiState.deleting -> LoadingNote()
                // With its to-dos read: they come before the notes the page may open on.
                details != null && uiState.todosLoaded -> DetailsBody(
                    uiState = uiState,
                    details = details,
                    selectedTab = selectedTab,
                    onSelectTab = { chosenTab = it },
                    errorText = shownError,
                    onDismissError = dismissError,
                    actions = actions,
                    audio = transcriptAudio,
                    transcription = audioState.transcription,
                    place = place,
                    modifier = Modifier.weight(1f)
                )
                details != null -> LoadingNote()
                errorText != null -> MissingNote(text = errorText, onBack = onNavigateBack)
                else -> LoadingNote()
            }
            if (details != null && audio != null && !uiState.deleting) {
                RecordingAudioBar(
                    playback = audioViewModel.playback,
                    durationMs = audio.durationMs,
                    sizeBytes = audioState.sizeBytes,
                    compressed = audioState.compressed,
                    onToggle = audioViewModel::togglePlay,
                    onSeek = audioViewModel::seekTo,
                    enabled = !audioState.recorderBusy
                )
            }
        }
        // Over the content and anchored to the bottom: a confirmation never moves what is read.
        NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }

    if (details != null) {
        val session = details.session
        val translation = session.mode == RecordingMode.REAL_TIME_TRANSLATION
        // One long job at a time: the rewrite and the search read the transcript this would replace.
        val canTranscribe = audioState.offersTranscribe && !rewriting && uiState.todoSearch == null
        val changeTypeOffer = uiState.changeTypeOffer(transcribing)
        // The sheet goes once the change can't be made from it (a finishing or other work began).
        LaunchedEffect(changeTypeOffer) {
            if (changeTypeOffer != AiOffer.READY) choosingType = false
        }

        if (showMenu) {
            // Copy takes what the open tab shows: the notes, to paste into a chat or a mail, or
            // the transcript.
            val copiesNotes = selectedTab == DetailsTab.NOTES && details.insights.isNotEmpty()
            DetailsMenu(
                canExport = !details.hasNothing,
                canShareAudio = audio != null,
                audioCompressed = audioState.compressed,
                canTranscribe = canTranscribe,
                canWriteNotes = uiState.offersWriteNotes(transcribing),
                canFindAgain = uiState.offersFindAgain(transcribing),
                aiWaits = uiState.aiWaits(transcribing),
                waitsForFinishing = uiState.aiFinishing,
                hasNotes = details.insights.isNotEmpty(),
                copiesNotes = copiesNotes,
                translation = translation,
                canOpenMap = !details.hasNothing && uiState.rewrite == null,
                keptAudio = audio?.kept,
                canDeleteAudio = audio != null && !transcribing,
                // Its steps read the audio: the better transcript, the joining of lines.
                deleteAudioWaits = uiState.finishing != null,
                changeType = changeTypeOffer,
                changeTypeWaitsForFinishing = uiState.changeTypeWaitsForFinishing,
                currentType = currentTypeLine(uiState.recordingAs?.name ?: modeLabel(session.mode), session.language),
                onShare = {
                    showMenu = false
                    shareMarkdown(details)
                },
                onShareAudio = {
                    showMenu = false
                    audio?.let { shareAudio(details, it) }
                },
                onSave = {
                    showMenu = false
                    saveToDownloads(details)
                },
                onCopy = {
                    showMenu = false
                    val text = if (copiesNotes) {
                        MarkdownExport.plainNotes(context, details, place)
                    } else {
                        MarkdownExport.plainTranscript(context, details, place)
                    }
                    copyToClipboard(details, text)
                },
                onTranscribe = {
                    showMenu = false
                    audioViewModel.showTranscribeChoice()
                },
                onKeepAudio = { kept ->
                    showMenu = false
                    audioViewModel.setKept(kept)
                },
                onDeleteAudio = {
                    showMenu = false
                    confirmDeleteAudio = true
                },
                onWriteNotes = {
                    showMenu = false
                    // Only notes that exist need the question: nothing is replaced otherwise.
                    if (details.insights.isEmpty()) writeNotes() else viewModel.showRewriteConfirm()
                },
                onFindAgain = {
                    showMenu = false
                    findTodosAgain()
                },
                onChangeType = {
                    showMenu = false
                    choosingType = true
                },
                onOpenMap = {
                    showMenu = false
                    onNavigateToMindMap(session.id)
                },
                onRename = {
                    showMenu = false
                    viewModel.showRenameDialog()
                },
                onDelete = {
                    showMenu = false
                    viewModel.showDeleteConfirmation()
                },
                onDismiss = { showMenu = false }
            )
        }

        if (choosingType) {
            RecordingTypeSheet(
                title = stringResource(R.string.g_details_change_type),
                choices = uiState.typeChoices,
                currentId = session.categoryId,
                onPick = { category ->
                    choosingType = false
                    changeType(category)
                },
                onDismiss = { choosingType = false },
                caption = if (uiState.someChangeRewritesNotes) stringResource(R.string.g_details_change_type_body) else null,
                language = if (uiState.changesLanguage) {
                    {
                        RecordingLanguageRow(
                            current = session.language,
                            malayalamOnPhone = uiState.malayalamOnPhone,
                            onPick = viewModel::changeLanguage,
                            onGetMalayalam = {
                                choosingType = false
                                onNavigateToSpeechModels()
                            }
                        )
                        Text(
                            text = stringResource(R.string.g_details_language_body),
                            style = Grille.type.caption,
                            color = Grille.colors.ash,
                            modifier = Modifier.padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.xs)
                        )
                    }
                } else {
                    null
                }
            )
        }

        RecordingPlaceSheets(
            sheet = placeSheet,
            label = placeLabel,
            text = place,
            onSheet = { placeSheet = it },
            onOpenInMaps = {
                placeSheet = null
                if (placeLabel != null && place != null && !openInMaps(context, placeLabel.place, place)) {
                    placeViewModel.reportNoMapsApp()
                }
            },
            onRemove = placeViewModel::removePlace
        )

        if (uiState.showRename) {
            DetailsRenameSheet(
                currentName = session.name.orEmpty(),
                fallbackName = modeLabel(session.mode),
                onRename = viewModel::renameSession,
                onDismiss = viewModel::hideRenameDialog
            )
        }

        uiState.editingSegment?.let { segment ->
            DetailsSegmentSheet(
                segment = segment,
                onSave = { text -> viewModel.saveSegmentText(segment.id, text) },
                onDismiss = viewModel::hideEditSegmentDialog
            )
        }

        uiState.editingInsight?.let { insight ->
            DetailsInsightSheet(
                insight = insight,
                translation = translation,
                onSave = { title, content, items -> viewModel.saveInsight(insight.id, title, content, items) },
                onDismiss = viewModel::hideEditInsightDialog
            )
        }

        if (uiState.showDeleteConfirmation) {
            val body = stringResource(
                if (translation) R.string.g_details_delete_body_translation else R.string.g_details_delete_body
            )
            val kept = uiState.keptTodos
            ConfirmSheet(
                title = stringResource(R.string.g_details_delete_title),
                // To-dos outlive their recording, which is worth saying where it is deleted.
                text = if (kept > 0) {
                    "$body ${pluralStringResource(R.plurals.g_details_delete_todos, kept, kept)}"
                } else {
                    body
                },
                confirmLabel = stringResource(R.string.g_details_delete),
                onConfirm = {
                    viewModel.hideDeleteConfirmation()
                    // A copy handed to the share sheet goes with the recording. Started at once,
                    // so leaving the screen right after can't skip it.
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        MarkdownExport.forgetShared(context, session.id)
                    }
                    viewModel.deleteSession(onDeleted = onNavigateBack)
                },
                onDismiss = viewModel::hideDeleteConfirmation
            )
        }

        if (confirmLeave) {
            // One of the three runs at a time.
            ConfirmSheet(
                title = stringResource(
                    when {
                        transcribing -> R.string.g_audio_stop_title
                        searching -> R.string.g_details_find_stop_title
                        else -> R.string.g_details_stop_title
                    }
                ),
                // Writing the first notes leaves no current notes to keep.
                text = stringResource(
                    when {
                        transcribing -> R.string.g_audio_stop_body
                        searching -> R.string.g_details_find_stop_body
                        details.insights.isEmpty() -> R.string.g_details_stop_body_none
                        else -> R.string.g_details_stop_body
                    }
                ),
                confirmLabel = stringResource(if (transcribing) R.string.g_audio_stop_confirm else R.string.g_details_stop_confirm),
                dismissLabel = stringResource(
                    when {
                        transcribing -> R.string.g_audio_stop_keep
                        searching -> R.string.g_details_find_stop_keep
                        else -> R.string.g_details_stop_keep
                    }
                ),
                onConfirm = {
                    confirmLeave = false
                    onNavigateBack()
                },
                onDismiss = { confirmLeave = false }
            )
        }

        audioState.transcribeChoice?.let { options ->
            TranscribeChoiceSheet(
                options = options,
                onChoose = ::transcribeAgain,
                onDismiss = audioViewModel::hideTranscribeChoice,
                textFollows = uiState.makesTextAgain
            )
        }

        if (uiState.showRewriteConfirm) {
            ConfirmSheet(
                title = stringResource(R.string.g_details_rewrite_title),
                text = stringResource(R.string.g_details_rewrite_body),
                confirmLabel = stringResource(R.string.g_details_rewrite_confirm),
                onConfirm = ::writeNotes,
                onDismiss = viewModel::hideRewriteConfirm
            )
        }

        // Asked only while there is audio to delete and no finishing step reads it.
        if (confirmDeleteAudio && audio != null && uiState.finishing == null) {
            ConfirmSheet(
                title = stringResource(R.string.g_audio_delete_this),
                text = stringResource(R.string.g_audio_delete_this_body),
                confirmLabel = stringResource(R.string.g_details_delete),
                onConfirm = {
                    confirmDeleteAudio = false
                    audioViewModel.deleteAudio()
                },
                onDismiss = { confirmDeleteAudio = false }
            )
        }

        uiState.openTodo?.let { todo ->
            TodoSheet(
                todo = todo,
                // The recording it came from is the page it opened on.
                sourceTitle = null,
                onOpenSource = viewModel::closeTodo,
                onSave = viewModel::saveOpenTodo,
                onComplete = {
                    viewModel.closeTodo()
                    completeTodo(todo)
                },
                onDelete = viewModel::deleteOpenTodo,
                onDismiss = viewModel::closeTodo,
                onMoveToIdeas = viewModel::moveOpenTodoToIdeas
            )
        }

        if (showSuggestions) {
            val now by rememberMinuteClock()
            TodoSuggestionsSheet(
                suggestions = uiState.todos.suggested,
                // All are from this recording: no "From" line.
                titles = emptyMap(),
                nowMs = now,
                onKeep = { viewModel.keepSuggestions(listOf(it)) },
                onDismissSuggestion = { viewModel.dismissSuggestion(it.id) },
                onKeepAll = viewModel::keepSuggestions,
                onClose = { showSuggestions = false },
                onMoveToIdeas = { viewModel.moveSuggestionToIdeas(it.id) }
            )
        }
    }
}

/**
 * What a new search for the recording's to-dos and ideas found, in words: "2 new to-dos and 1 new
 * idea". Read outside composition, as it shows from a coroutine.
 */
private fun foundText(resources: Resources, found: TodosFound): String {
    val todos = found.todos.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.g_details_found_todos, it, it) }
    val ideas = found.ideas.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.g_details_found_ideas, it, it) }
    return when {
        todos != null && ideas != null -> resources.getString(R.string.g_details_found_both, todos, ideas)
        else -> todos ?: ideas ?: resources.getString(R.string.g_details_found_none)
    }
}

/**
 * Says what happened and what to do, in the app's language.
 *
 * @param hasNotes Whether the recording has notes: a failed first write has no current notes to
 *   speak of.
 */
@Composable
private fun errorMessage(error: DetailsError, hasNotes: Boolean): String = stringResource(
    when (error) {
        DetailsError.Missing -> R.string.g_details_error_missing
        DetailsError.Load -> R.string.g_details_error_load
        DetailsError.Rename -> R.string.g_details_error_rename
        DetailsError.Delete -> R.string.g_details_error_delete
        DetailsError.Segment -> R.string.g_details_error_segment
        DetailsError.Insight -> R.string.g_details_error_insight
        DetailsError.NoModel -> R.string.g_details_error_no_model
        DetailsError.ModelFailed -> R.string.g_details_error_model
        DetailsError.TooShort -> R.string.g_details_notes_too_short
        DetailsError.RewriteFailed -> if (hasNotes) R.string.g_details_error_rewrite else R.string.g_details_error_write
        DetailsError.AiBusy -> R.string.g_details_error_ai_busy
        DetailsError.FindFailed -> R.string.g_details_error_find
        DetailsError.ChangeType -> R.string.g_details_error_change_type
        DetailsError.ChangeLanguage -> R.string.g_details_error_change_language
        DetailsError.Todo -> R.string.g_todo_error
        DetailsError.Save -> R.string.g_details_error_save
        DetailsError.Share -> R.string.g_details_error_share
        DetailsError.Copy -> R.string.g_details_error_clipboard
    }
)

/** Says what went wrong with the audio and what to do, in the app's language. */
@Composable
private fun audioErrorMessage(error: AudioError): String = stringResource(
    when (error) {
        AudioError.Playback -> R.string.g_audio_error_play
        AudioError.TranscribeFailed -> R.string.g_audio_error_transcribe
        AudioError.NoSpeech -> R.string.g_audio_error_no_speech
        AudioError.AiBusy -> R.string.g_details_error_ai_busy
        AudioError.Keep -> R.string.g_audio_error_keep
        AudioError.Delete -> R.string.g_audio_error_delete
    }
)

/**
 * The line under "Change type" in the menu: the type the recording has, and its language when it is
 * Malayalam ("Meeting · Malayalam"), as English or German is the categories' own.
 */
@Composable
private fun currentTypeLine(type: String, language: RecordingLanguage): String =
    if (language == RecordingLanguage.ENGLISH_OR_GERMAN) type else stringResource(R.string.g_language_pair, type, recordingLanguageName(language))

/** No tab chosen by the user yet. */
private const val NO_TAB = -1

/** The database usually answers within a frame or two; the dots only appear if it takes longer. */
@Composable
private fun LoadingNote() {
    val visible by produceState(initialValue = false) {
        delay(LOADING_DOTS_DELAY_MS)
        value = true
    }
    if (visible) {
        val description = stringResource(R.string.g_details_loading)
        DotProgress(
            progress = null,
            modifier = Modifier
                .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
                .semantics { contentDescription = description }
        )
    }
}

/** The recording can't be shown; the only way on is back to the list. */
@Composable
private fun MissingNote(text: String, onBack: () -> Unit) {
    ErrorNote(
        text = text,
        action = PillAction(stringResource(R.string.g_details_back_to_list), onClick = onBack),
        modifier = Modifier.padding(vertical = GrilleSpace.m)
    )
}

/** Loading from the database rarely takes this long; only then are the dots worth showing. */
private const val LOADING_DOTS_DELAY_MS = 300L
