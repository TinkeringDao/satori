package com.satori.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.BatchInsightProgress
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingAudio
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.llm.InsightGroups
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.groupInsights
import com.satori.presentation.sessiondetails.AiOffer
import com.satori.presentation.sessiondetails.DetailsTab
import com.satori.presentation.sessiondetails.NotesAi
import com.satori.presentation.sessiondetails.RecordingPlayer
import com.satori.presentation.sessiondetails.RecordingTodos
import com.satori.presentation.sessiondetails.RewriteProgress
import com.satori.presentation.sessiondetails.SessionDetailsUiState
import com.satori.presentation.sessiondetails.TodoSearch
import com.satori.presentation.sessiondetails.TranscriptionProgress
import com.satori.ui.R
import com.satori.ui.components.TodoRow
import com.satori.ui.components.rememberMinuteClock
import com.satori.ui.design.DotProgress
import com.satori.ui.design.DotoNumber
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.PillAction
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.TabularDigits
import com.satori.ui.design.TextTabs
import com.satori.ui.design.clockTime
import com.satori.ui.design.formatDuration
import com.satori.ui.design.modeLabel
import com.satori.ui.design.relativeDateTime
import kotlinx.coroutines.flow.StateFlow

// The recording's page: a header that scrolls away, two tabs that stay, and the transcript or the
// notes below them. The header and the tabs are the first two items of each tab's own list, so
// each tab keeps its scroll position and the tabs stick to the top once the header is gone.

/** The header and the tabs come before the first row of either list. */
private const val FIRST_ROW = 2

/** Lines spoken within the same clock minute show their time once. */
private const val MINUTE_MS = 60_000L

/** What the page's rows and buttons do. The screen decides how. */
@Immutable
internal class DetailsActions(
    val onRename: () -> Unit,
    val onEditSegment: (TranscriptionSegment) -> Unit,
    val onEditInsight: (LlmInsight) -> Unit,
    val onWriteNotes: () -> Unit,
    val onCancelRewrite: () -> Unit,
    val onGetModel: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onDelete: () -> Unit,
    val onCancelTranscription: () -> Unit,
    val onOpenPlace: () -> Unit,
    val onSkipTranscript: () -> Unit,
    val onOpenTodo: (Todo) -> Unit,
    val onCompleteTodo: (Todo) -> Unit,
    val onShowSuggestions: () -> Unit,
    val onOpenIdeas: () -> Unit,
    val onCancelTodoSearch: () -> Unit
)

/**
 * What the transcript needs of the recording's kept audio: a tapped line plays from where it was
 * said ([RecordingAudio.startOf]), and the line that plays is marked.
 *
 * @property playback Where the audio plays; read only to find the line that plays, so the
 *   position ticking doesn't redraw the list
 * @property onPlayFrom Plays from a place in the audio
 */
@Immutable
internal class TranscriptAudio(
    val audio: RecordingAudio,
    val playback: StateFlow<RecordingPlayer.State>,
    val onPlayFrom: (Long) -> Unit
)

/**
 * The recording's content below the top bar: its error note, if any, then the tabs and their
 * lists, or for a recording with nothing in it one sentence and Delete.
 *
 * @param audio The recording's kept audio, or null when it has none
 * @param transcription The Transcribe again that runs, shown above the transcript; null when none
 * @param place Where the recording was made, as it reads under the date; null when it has no place
 */
@Composable
internal fun DetailsBody(
    uiState: SessionDetailsUiState,
    details: SessionWithDetails,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    errorText: String?,
    onDismissError: () -> Unit,
    actions: DetailsActions,
    audio: TranscriptAudio?,
    transcription: TranscriptionProgress?,
    place: String?,
    modifier: Modifier = Modifier
) {
    val session = details.session
    val title = recordingTitle(session)
    val segments = remember(details.segments) {
        details.segments.filter { it.text.isNotBlank() }.sortedBy { it.timestamp }
    }
    val segmentMap = remember(details.segments) { details.segments.associateBy { it.id } }
    // Said only for another model than the live one, which made every transcript until now.
    val transcribedWith = SpeechModelId.ofTranscript(session.transcriptModel)?.takeIf { it != SpeechModelId.LIVE }
    val groups = remember(details.insights, session.insightStrategy, session.mode) {
        groupInsights(details.insights, session)
    }
    val enoughText = remember(details.segments) { RewriteNotesUseCase.hasEnoughText(details.segments) }

    // Search opens the page on a line or a note. Each list starts there once and then keeps its
    // own position, across tab switches and edits.
    val highlightId = uiState.highlightId
    val transcriptState = rememberListStartingAt(remember { segments.indexOfFirst { it.id == highlightId } })
    // On the notes tab the recording's to-dos and ideas come first.
    val notesState = rememberListStartingAt(
        remember {
            val note = groups.all.indexOfFirst { it.id == highlightId }
            if (note < 0) note else TodoTabRow.of(uiState.todos).size + note
        }
    )

    val date = relativeDateTime(session.createdAt)
    val length = session.durationMs.takeIf { it > 0L }?.let { formatDuration(it) }
    val finishing = uiState.finishing?.let { status ->
        FinishingText(shown = finishingStatusText(status), spoken = finishingStatusText(status.spoken()))
    }
    val onSkip = actions.onSkipTranscript.takeIf { uiState.canSkipTranscript }
    val header: @Composable () -> Unit = {
        DetailsHeader(title, date, length, finishing, onSkip, place, actions.onRename, actions.onOpenPlace)
    }

    val tabLabels = listOf(stringResource(R.string.g_tab_transcript), stringResource(notesTabRes(session.mode)))
    val tabs: @Composable () -> Unit = { DetailsTabs(tabLabels, selectedTab, onSelectTab) }

    Column(modifier.fillMaxWidth()) {
        if (errorText != null) {
            ErrorNote(text = errorText, onDismiss = onDismissError)
        }
        val listModifier = Modifier.weight(1f)
        when {
            details.hasNothing -> EmptyRecording(header, actions.onDelete, listModifier)

            selectedTab == DetailsTab.TRANSCRIPT -> TranscriptList(
                segments = segments,
                recordingStart = session.createdAt,
                state = transcriptState,
                header = header,
                tabs = tabs,
                // Lines are not edited while a new transcript is being made, nor while the
                // finishing may still replace or join them: either gives the lines new ids.
                onEdit = actions.onEditSegment.takeIf { transcription == null && uiState.canEditTranscript },
                audio = audio,
                transcription = transcription,
                onCancelTranscription = actions.onCancelTranscription,
                transcribedWith = transcribedWith,
                modifier = listModifier
            )

            else -> NotesList(
                groups = groups,
                segmentMap = segmentMap,
                recordingTitle = title,
                rewrite = uiState.rewrite,
                writingNotes = uiState.showsWritingNotes,
                // No notes are offered while a new transcript is made: they'd be written from the old one.
                ai = uiState.notesAi.takeIf { transcription == null },
                // As the menu offers it: not while other work of the page runs, switched off while
                // the AI is in use elsewhere.
                writeNotes = uiState.writeNotesOffer(transcribing = transcription != null),
                waitsForFinishing = uiState.aiFinishing,
                modelComing = uiState.waitsForModel,
                // Not while the finishing may still write the notes anew: an edit would be lost.
                canEditNotes = uiState.canEditNotes,
                enoughText = enoughText,
                mode = session.mode,
                todos = uiState.todos,
                todoSearch = uiState.todoSearch,
                state = notesState,
                header = header,
                tabs = tabs,
                actions = actions,
                modifier = listModifier
            )
        }
    }
}

/**
 * A list that starts at the top, or with row [rowIndex] just below the tabs when it is not
 * negative. The tabs stick over the top of the list, so a row scrolled to the top would sit
 * under them; the list starts one tab bar's height before the row.
 */
@Composable
private fun rememberListStartingAt(rowIndex: Int): LazyListState {
    val tabBarPx = with(LocalDensity.current) { GrilleSpace.touch.roundToPx() }
    return rememberLazyListState(
        initialFirstVisibleItemIndex = if (rowIndex < 0) 0 else FIRST_ROW + rowIndex,
        initialFirstVisibleItemScrollOffset = if (rowIndex < 0) 0 else -tabBarPx
    )
}

/** A recording with nothing in it: no words in the transcript and no notes. */
internal val SessionWithDetails.hasNothing: Boolean
    get() = insights.isEmpty() && segments.none { it.text.isNotBlank() }

/** A recording without a name is called by its mode, as on the home list. */
@Composable
private fun recordingTitle(session: TranscriptionSession): String =
    session.name?.takeIf { it.isNotBlank() } ?: modeLabel(session.mode)

/** How the finishing of the recording stands, as the page shows it and as TalkBack says it. */
@Immutable
private class FinishingText(val shown: String, val spoken: String)

/**
 * This status as TalkBack says it when it changes: its step, without the percent a better
 * transcript counts up while it reads, which would have it speak at every percent.
 */
internal fun FinishingStatus.spoken(): FinishingStatus = copy(transcript = transcript?.copy(fraction = 0f))

/**
 * Title (tap to rename), then the date and, in Doto, the length, then how the finishing of the
 * recording stands while it is finished in the background ([finishing]), then where it was made,
 * if it has a [place] (tap to see it).
 *
 * @param onSkip Ends the better transcript being made; null while none is, or once Skip was pressed
 */
@Composable
private fun DetailsHeader(
    title: String,
    date: String,
    length: String?,
    finishing: FinishingText?,
    onSkip: (() -> Unit)?,
    place: String?,
    onRename: () -> Unit,
    onPlace: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin)
    ) {
        Text(
            text = title,
            style = Grille.type.title,
            color = Grille.colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { heading() }
                .clickable(
                    onClickLabel = stringResource(R.string.g_details_rename_action),
                    role = Role.Button,
                    onClick = onRename
                )
                // The whole padded box is the tap target, at least 48 dp for a one-line title.
                .heightIn(min = GrilleSpace.touch)
                .padding(vertical = GrilleSpace.s)
        )
        // Date and length are read as one.
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = date,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                modifier = Modifier.weight(1f)
            )
            if (length != null) {
                // Doto digits alone read as a time of day; the description says it is a length.
                DotoNumber(
                    text = length,
                    style = Grille.type.listNumber,
                    contentDescription = stringResource(R.string.g_details_length_cd, length)
                )
            }
        }
        if (finishing != null) FinishingLine(finishing, onSkip)
        if (place != null) {
            Text(
                text = place,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClickLabel = stringResource(R.string.g_place_show),
                        role = Role.Button,
                        onClick = onPlace
                    )
                    // The whole line is the tap target, at least 48 dp tall.
                    .heightIn(min = GrilleSpace.touch)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }
        Spacer(Modifier.height(GrilleSpace.s))
    }
}

/**
 * How the finishing of the recording stands, in the words home uses: that it waits, or the step
 * under way. In ash: red is the microphone's alone. TalkBack says each new step as it comes, once:
 * the percent of the better transcript is only shown. While the better transcript is made, a quiet
 * Skip ends it, and the notes come from the transcript there is.
 */
@Composable
private fun FinishingLine(status: FinishingText, onSkip: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = status.shown,
            style = Grille.type.caption,
            color = Grille.colors.ash,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = GrilleSpace.xs)
                .clearAndSetSemantics {
                    contentDescription = status.spoken
                    liveRegion = LiveRegionMode.Polite
                }
        )
        if (onSkip != null) {
            PillButton(
                text = stringResource(R.string.g_speech_finishing_skip),
                onClick = onSkip,
                kind = PillKind.Quiet
            )
        }
    }
}

/** The tabs on an opaque bar, so the rows slide under them once they stick to the top. */
@Composable
private fun DetailsTabs(labels: List<String>, selectedTab: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(Grille.colors.void)) {
        TextTabs(tabs = labels, selectedIndex = selectedTab, onSelect = onSelect)
        Hairline()
    }
}

/** The header, the tabs (stuck to the top once the header has gone) and then the [rows]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsList(
    state: LazyListState,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    rows: LazyListScope.() -> Unit
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = GrilleSpace.l)
    ) {
        item(key = "header") { header() }
        stickyHeader(key = "tabs") { tabs() }
        rows()
    }
}

// ─── Transcript ──────────────────────────────────────────────────────────────

/**
 * The transcript. With audio, a tapped line plays from where it was said, and a dot in the margin
 * marks the line that plays; while a new transcript is made, what is happening stands above it.
 * A transcript made again by another speech model than the live one says so in one line on top.
 *
 * @param recordingStart When the recording was created, the clock of audio whose index was lost
 * @param onEdit Opens a line's editor; null while lines can't be edited
 * @param transcribedWith The speech model that made the transcript, when it isn't the live one
 */
@Composable
private fun TranscriptList(
    segments: List<TranscriptionSegment>,
    recordingStart: Long,
    state: LazyListState,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    onEdit: ((TranscriptionSegment) -> Unit)?,
    audio: TranscriptAudio?,
    transcription: TranscriptionProgress?,
    onCancelTranscription: () -> Unit,
    transcribedWith: SpeechModelId?,
    modifier: Modifier = Modifier
) {
    // Read only inside the derived state, so the ticking position recomposes nothing until the
    // line that plays changes.
    val playback = audio?.playback?.collectAsStateWithLifecycle()
    val playingLine = remember(audio, segments, recordingStart, playback) {
        derivedStateOf {
            val now = playback?.value
            if (audio == null || now?.sessionId == null) null
            else audio.audio.lineAt(now.positionMs, segments, recordingStart)
        }
    }
    val playLabel = stringResource(R.string.g_audio_play_line)
    DetailsList(state, header, tabs, modifier) {
        if (transcription != null) {
            item(key = "transcribing") { TranscribeProgressBlock(transcription, onCancelTranscription) }
        }
        if (transcribedWith != null && segments.isNotEmpty()) {
            item(key = "transcribed_with") { TranscribedWith(transcribedWith) }
        }
        if (segments.isEmpty()) item(key = "empty") { EmptyNote(stringResource(R.string.g_details_empty)) }
        itemsIndexed(segments, key = { _, segment -> segment.id }) { index, segment ->
            val sameMinuteAsBefore = index > 0 &&
                segments[index - 1].timestamp / MINUTE_MS == segment.timestamp / MINUTE_MS
            TranscriptLine(
                segment = segment,
                showTime = !sameMinuteAsBefore,
                playing = playingLine.value == segment.id,
                onEdit = onEdit?.let { edit -> { edit(segment) } },
                onPlay = audio?.let { a -> { a.onPlayFrom(a.audio.startOf(segment, recordingStart)) } },
                playLabel = playLabel
            )
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
        }
    }
}

/**
 * One line of the transcript. [playing] puts a dot in the left margin, beside its first line of
 * text, so the text itself never moves.
 */
@Composable
private fun TranscriptLine(
    segment: TranscriptionSegment,
    showTime: Boolean,
    playing: Boolean,
    onEdit: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    playLabel: String
) {
    val ink = Grille.colors.ink
    val transcript = Grille.type.transcript
    val caption = Grille.type.caption
    EditableColumn(
        onEdit = onEdit,
        onTap = onPlay,
        tapLabel = playLabel,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (playing) {
                    // Centred on the first line of text, under the time when it shows.
                    val above = if (showTime) caption.lineHeight.toPx() + GrilleSpace.xs.toPx() else 0f
                    val y = GrilleSpace.m.toPx() + above + transcript.lineHeight.toPx() / 2
                    drawCircle(ink, radius = PLAYING_DOT.toPx() / 2, center = Offset(GrilleSpace.margin.toPx() / 2, y))
                }
            },
        contentPadding = PaddingValues(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        if (showTime) {
            Text(text = clockTime(segment.timestamp), style = Grille.type.caption, color = Grille.colors.ash)
            Spacer(Modifier.height(GrilleSpace.xs))
        }
        Text(text = segment.text, style = Grille.type.transcript, color = Grille.colors.ink)
    }
}

/** The dot that marks the line that plays: the size of the grille's dots. */
private val PLAYING_DOT = 5.dp

/** The one line above a transcript another speech model made: "Transcribed with Cohere Transcribe". */
@Composable
private fun TranscribedWith(model: SpeechModelId) {
    Text(
        text = stringResource(R.string.g_speech_transcribed_with, speechModelName(model)),
        style = Grille.type.caption,
        color = Grille.colors.ash,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.m)
    )
}

/**
 * Where the new transcript will be: what is going on and with which speech model, how far it is,
 * and a way out.
 */
@Composable
private fun TranscribeProgressBlock(progress: TranscriptionProgress, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    ) {
        Text(
            text = stringResource(R.string.g_audio_transcribe_running),
            style = Grille.type.heading,
            color = Grille.colors.ink
        )
        Text(text = speechModelName(progress.model), style = Grille.type.caption, color = Grille.colors.ash)
        Spacer(Modifier.height(GrilleSpace.m))
        DotProgress(progress = if (progress.stopping) null else progress.fraction)
        Spacer(Modifier.height(GrilleSpace.s))
        Text(
            text = if (progress.stopping) {
                stringResource(R.string.g_audio_transcribe_stopping)
            } else {
                stringResource(R.string.g_audio_transcribe_percent, (progress.fraction * 100).toInt())
            },
            style = Grille.type.caption.copy(fontFeatureSettings = TabularDigits),
            color = Grille.colors.ash
        )
        Spacer(Modifier.height(GrilleSpace.m))
        // Outlined, as the rewrite's Cancel: a quiet pill alone under the status would sit
        // indented from the page's margin.
        PillButton(
            text = stringResource(R.string.g_cancel),
            onClick = onCancel,
            kind = PillKind.Outline,
            enabled = !progress.stopping
        )
    }
}

// ─── Notes ───────────────────────────────────────────────────────────────────

/**
 * The notes tab. Its first rows are the recording's own to-dos and ideas ([todoRows]), above the
 * notes; [noteRows] are the notes and what stands in for them, under a heading of their own when
 * the to-dos are above them.
 *
 * @param writingNotes The finishing of the recording writes its notes, and there are none yet
 * @param writeNotes How the empty tab offers to write the notes
 * @param waitsForFinishing Whether that waits for the finishing of recordings, or for the AI to be
 *   free of other work
 * @param modelComing The finishing waits for the AI model the first run downloads: the notes follow
 *   by themselves once it is in
 * @param canEditNotes Whether a long press on a note opens its editor
 * @param mode The recording's mode: a Translate recording's notes are its translation
 */
@Composable
private fun NotesList(
    groups: InsightGroups,
    segmentMap: Map<String, TranscriptionSegment>,
    recordingTitle: String,
    rewrite: RewriteProgress?,
    writingNotes: Boolean,
    ai: NotesAi?,
    writeNotes: AiOffer,
    waitsForFinishing: Boolean,
    modelComing: Boolean,
    canEditNotes: Boolean,
    enoughText: Boolean,
    mode: RecordingMode,
    todos: RecordingTodos,
    todoSearch: TodoSearch?,
    state: LazyListState,
    header: @Composable () -> Unit,
    tabs: @Composable () -> Unit,
    actions: DetailsActions,
    modifier: Modifier = Modifier
) {
    // Read where the due lines are drawn, so the minute's tick redraws only them.
    val now = rememberMinuteClock()
    val notesHeading = stringResource(notesTabRes(mode))
    val todoRows = remember(todos) { TodoTabRow.of(todos) }
    DetailsList(state, header, tabs, modifier) {
        if (todoSearch != null) item(key = "todo-search") { TodoSearchBlock(todoSearch, actions.onCancelTodoSearch) }
        todoRows(todoRows, notesHeading, now, actions)
        val translation = mode == RecordingMode.REAL_TIME_TRANSLATION
        val empty = EmptyNotes(ai, writeNotes, waitsForFinishing, enoughText, translation, modelComing)
        noteRows(groups, segmentMap, recordingTitle, rewrite, writingNotes, empty, canEditNotes, actions)
    }
}

/**
 * What the notes tab says without notes ([NotesEmpty]).
 *
 * @property writeNotes How it offers to write them
 * @property waitsForFinishing Whether that waits for the finishing of recordings rather than for
 *   other work that holds the AI
 * @property modelComing The recording's finishing waits for the AI model the first run downloads
 */
@Immutable
private class EmptyNotes(
    val ai: NotesAi?,
    val writeNotes: AiOffer,
    val waitsForFinishing: Boolean,
    val enoughText: Boolean,
    val translation: Boolean,
    val modelComing: Boolean
)

/**
 * One row of the top of the notes tab, above the notes: the recording's to-dos and ideas, and the
 * heading the notes get under them. Listed before they are drawn, so the page knows how many rows
 * come before the notes when it opens on one ([of]).
 */
internal sealed interface TodoTabRow {
    /** Unique among the rows of the tab. */
    val key: String

    /** "To do", with the number of open to-dos when there are any. */
    data class TodoHeading(val open: Int) : TodoTabRow {
        override val key: String get() = "todo-heading"
    }

    /** An open to-do: ticked off by its circle, opened in its sheet by its text. */
    data class OpenTodo(val todo: Todo) : TodoTabRow {
        override val key: String get() = "todo:${todo.id}"
    }

    /** "2 suggested", which opens the suggestions to keep or dismiss. */
    data class Suggestions(val count: Int) : TodoTabRow {
        override val key: String get() = "todo-suggested"
    }

    /** "Ideas", with how many. */
    data class IdeasHeading(val count: Int) : TodoTabRow {
        override val key: String get() = "ideas-heading"
    }

    /** An idea, which opens the Ideas page, where it can become a to-do. */
    data class Idea(val idea: Todo) : TodoTabRow {
        override val key: String get() = "idea:${idea.id}"
    }

    /** The heading of the notes, which follow the to-dos and ideas. */
    data object NotesHeading : TodoTabRow {
        override val key: String get() = "notes-heading"
    }

    companion object {
        /**
         * The rows of [todos], in the order they are drawn: the to-dos (open ones, then the row for
         * the suggestions), then the ideas, each part under its heading, and then the notes' own
         * heading. None for a recording without any.
         */
        fun of(todos: RecordingTodos): List<TodoTabRow> = buildList {
            if (todos.open.isNotEmpty() || todos.suggested.isNotEmpty()) {
                add(TodoHeading(todos.open.size))
                todos.open.forEach { add(OpenTodo(it)) }
                if (todos.suggested.isNotEmpty()) add(Suggestions(todos.suggested.size))
            }
            if (todos.ideas.isNotEmpty()) {
                add(IdeasHeading(todos.ideas.size))
                todos.ideas.forEach { add(Idea(it)) }
            }
            if (isNotEmpty()) add(NotesHeading)
        }
    }
}

/** Draws [rows] ([TodoTabRow.of]); [notesHeading] is what the notes are called in this recording. */
private fun LazyListScope.todoRows(rows: List<TodoTabRow>, notesHeading: String, now: State<Long>, actions: DetailsActions) {
    items(rows, key = { it.key }) { row ->
        when (row) {
            is TodoTabRow.TodoHeading -> ListHeading(stringResource(R.string.g_todo_heading), count = row.open.takeIf { it > 0 })
            is TodoTabRow.OpenTodo -> Column(Modifier.animateItem()) {
                TodoRow(
                    todo = row.todo,
                    nowMs = now.value,
                    onToggle = { actions.onCompleteTodo(row.todo) },
                    onOpen = { actions.onOpenTodo(row.todo) }
                )
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
            is TodoTabRow.Suggestions -> QuietRow(
                text = pluralStringResource(R.plurals.g_todo_suggested, row.count, row.count),
                onClick = actions.onShowSuggestions
            )
            is TodoTabRow.IdeasHeading -> ListHeading(stringResource(R.string.g_details_ideas), count = row.count)
            is TodoTabRow.Idea -> Column(Modifier.animateItem()) {
                IdeaRow(row.idea.text, onOpen = actions.onOpenIdeas)
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
            TodoTabRow.NotesHeading -> ListHeading(notesHeading)
        }
    }
}

/**
 * The summary, then the other notes. While a rewrite runs, the page says what is happening
 * instead: the old notes stay stored until the new ones are complete. While the finishing of the
 * recording is to write them ([writingNotes]), it says that. Without notes: one sentence and the
 * action that gets them ([empty]).
 *
 * @param canEditNotes Whether a long press on a note opens its editor
 */
private fun LazyListScope.noteRows(
    groups: InsightGroups,
    segmentMap: Map<String, TranscriptionSegment>,
    recordingTitle: String,
    rewrite: RewriteProgress?,
    writingNotes: Boolean,
    empty: EmptyNotes,
    canEditNotes: Boolean,
    actions: DetailsActions
) {
    val onEdit = actions.onEditInsight.takeIf { canEditNotes }
    when {
        rewrite != null -> item(key = "rewrite") { RewriteProgressBlock(rewrite, actions.onCancelRewrite) }

        writingNotes -> item(key = "writing") { WritingNotes() }

        groups.all.isEmpty() -> item(key = "empty") { NotesEmpty(empty, actions) }

        else -> {
            items(groups.pinned, key = { it.id }) { insight ->
                NoteRow(insight, pinned = true, groups, segmentMap, recordingTitle, onEdit)
            }
            items(groups.rest, key = { it.id }) { insight ->
                NoteRow(insight, pinned = false, groups, segmentMap, recordingTitle, onEdit)
            }
        }
    }
}

/** One note; [onEdit] opens its editor on a long press, null while the notes can't be edited. */
@Composable
private fun NoteRow(
    insight: LlmInsight,
    pinned: Boolean,
    groups: InsightGroups,
    segmentMap: Map<String, TranscriptionSegment>,
    recordingTitle: String,
    onEdit: ((LlmInsight) -> Unit)?
) {
    val actionItems = remember(insight.tasks, groups) { groups.actionItems(insight) }
    InsightBlock(
        insight = insight,
        segmentMap = segmentMap,
        pinned = pinned,
        recordingTitle = recordingTitle,
        actionItems = actionItems,
        onEdit = onEdit?.let { edit -> { edit(insight) } }
    )
    Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
}

/**
 * No notes yet: one sentence and, where the AI can help, the one action that gets them. Nothing
 * shows until it is known whether the AI is available. A translation is only written while
 * recording, never afterwards (the menu has no rewrite for it either), so an empty one only says
 * there is none yet. Write notes is offered as the menu offers it: not while other work of the
 * page runs, and switched off, saying when it is available, while the AI is in use elsewhere.
 */
@Composable
private fun NotesEmpty(empty: EmptyNotes, actions: DetailsActions) {
    if (empty.translation) {
        EmptyNote(stringResource(R.string.g_details_translation_empty))
        return
    }
    if (!empty.enoughText) {
        EmptyNote(stringResource(R.string.g_details_notes_too_short))
        return
    }
    // The first run downloads the AI model, and the finishing waits for it: no model to get here,
    // nothing to tap. The recorder says the same while it records.
    if (empty.modelComing) {
        EmptyNote(stringResource(R.string.g_rec_notes_downloading))
        return
    }
    when (empty.ai) {
        null -> Unit
        NotesAi.Ready -> EmptyNote(
            text = stringResource(R.string.g_details_notes_empty),
            action = PillAction(
                label = stringResource(R.string.g_details_write_notes),
                enabled = empty.writeNotes == AiOffer.READY,
                onClick = actions.onWriteNotes
            ).takeIf { empty.writeNotes != AiOffer.NONE },
            caption = aiWaitText(empty.waitsForFinishing).takeIf { empty.writeNotes == AiOffer.WAITS }
        )
        NotesAi.TurnedOff -> EmptyNote(
            text = stringResource(R.string.g_details_notes_off),
            action = PillAction(stringResource(R.string.g_details_open_settings), onClick = actions.onOpenSettings)
        )
        NotesAi.NoModel -> EmptyNote(
            text = stringResource(R.string.g_details_notes_no_model),
            action = PillAction(stringResource(R.string.g_details_get_model), onClick = actions.onGetModel)
        )
    }
}

/** Where the notes will be: what is going on, and a way out. */
@Composable
private fun RewriteProgressBlock(rewrite: RewriteProgress, onCancel: () -> Unit) {
    val progress = rewrite.progress
    // One part is the whole recording: "part 1 of 1" would say nothing, and a full bar too early.
    val parts = (progress as? BatchInsightProgress.Mapping)?.takeIf { it.totalChunks > 1 }
    val status = when {
        // A cancelled pipeline reports itself as an error on its way out.
        rewrite.stopping || progress is BatchInsightProgress.Error -> stringResource(R.string.g_details_rewrite_stopping)
        parts != null -> stringResource(R.string.g_batch_part, parts.currentChunk, parts.totalChunks)
        progress is BatchInsightProgress.Idle -> stringResource(R.string.g_details_rewrite_starting)
        else -> stringResource(R.string.g_batch_merge)
    }
    val fraction: Float? = when {
        rewrite.stopping -> null
        parts != null -> parts.currentChunk.toFloat() / parts.totalChunks
        progress is BatchInsightProgress.Complete -> 1f
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    ) {
        Text(
            text = stringResource(R.string.g_details_rewrite_running),
            style = Grille.type.heading,
            color = Grille.colors.ink
        )
        Spacer(Modifier.height(GrilleSpace.m))
        DotProgress(progress = fraction)
        Spacer(Modifier.height(GrilleSpace.s))
        Text(
            text = status,
            style = Grille.type.caption,
            color = Grille.colors.ash,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(GrilleSpace.m))
        // Outlined, not quiet: standing alone under the status, a quiet pill's text would sit
        // indented from the margin the rest of the page keeps.
        PillButton(
            text = stringResource(R.string.g_cancel),
            onClick = onCancel,
            kind = PillKind.Outline,
            enabled = !rewrite.stopping
        )
    }
}

/**
 * The notes are on their way: the finishing of the recording writes them, behind any recording
 * finished before it. The status under the date says which step it is at.
 */
@Composable
private fun WritingNotes() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    ) {
        Text(
            text = stringResource(R.string.g_rec_notes_writing),
            style = Grille.type.body,
            color = Grille.colors.ink
        )
        Spacer(Modifier.height(GrilleSpace.m))
        DotProgress(progress = null)
    }
}

/** A new search for the recording's to-dos and ideas: what is going on, and a way out. */
@Composable
private fun TodoSearchBlock(search: TodoSearch, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    ) {
        Text(
            text = stringResource(if (search.stopping) R.string.g_details_rewrite_stopping else R.string.g_details_finding),
            style = Grille.type.heading,
            color = Grille.colors.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Spacer(Modifier.height(GrilleSpace.m))
        DotProgress(progress = null)
        Spacer(Modifier.height(GrilleSpace.m))
        // Outlined, as the rewrite's Cancel: a quiet pill alone would sit in from the margin.
        PillButton(
            text = stringResource(R.string.g_cancel),
            onClick = onCancel,
            kind = PillKind.Outline,
            enabled = !search.stopping
        )
    }
}

/** A heading of the notes tab's parts; [count] is shown at the right, in ash. */
@Composable
private fun ListHeading(title: String, count: Int? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GrilleSpace.margin, end = GrilleSpace.margin, top = GrilleSpace.l, bottom = GrilleSpace.xs)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = Grille.type.heading,
            color = Grille.colors.ink,
            modifier = Modifier.weight(1f)
        )
        if (count != null) {
            Text(text = count.toString(), style = Grille.type.label, color = Grille.colors.ash)
        }
    }
}

/** A row of ash text that opens more: "2 suggested". */
@Composable
private fun QuietRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = GrilleSpace.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GrilleSpace.margin),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, style = Grille.type.body, color = Grille.colors.ash)
    }
}

/** One idea of the recording: what it says, in a row that opens the Ideas page. */
@Composable
private fun IdeaRow(text: String, onOpen: () -> Unit) {
    Text(
        text = text,
        style = Grille.type.body,
        color = Grille.colors.ink,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.g_details_idea_open), role = Role.Button, onClick = onOpen)
            .heightIn(min = GrilleSpace.touch)
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    )
}

// ─── States ──────────────────────────────────────────────────────────────────

/** A recording with nothing in it: the header (to rename it), one sentence and Delete. */
@Composable
private fun EmptyRecording(header: @Composable () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        header()
        EmptyNote(
            text = stringResource(R.string.g_details_empty),
            action = PillAction(stringResource(R.string.g_details_delete), onClick = onDelete)
        )
    }
}

/**
 * One sentence and, if there is one, the action that follows from it, with a [caption] under it
 * that says why it is switched off.
 */
@Composable
private fun EmptyNote(text: String, action: PillAction? = null, caption: String? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l)
    ) {
        Text(text = text, style = Grille.type.body, color = Grille.colors.ink)
        if (action != null) {
            Spacer(Modifier.height(GrilleSpace.m))
            PillButton(text = action.label, onClick = action.onClick, enabled = action.enabled)
        }
        if (caption != null) {
            Spacer(Modifier.height(GrilleSpace.s))
            Text(text = caption, style = Grille.type.caption, color = Grille.colors.ash)
        }
    }
}

/**
 * What the page's AI work says while it waits, in the menu and on the empty notes tab: that it is
 * available once the finishing of recordings is done ([forFinishing]), or once the AI is free of
 * other work, the mind map's or another recording page's.
 */
@Composable
internal fun aiWaitText(forFinishing: Boolean): String =
    stringResource(if (forFinishing) R.string.g_details_ai_busy else R.string.g_details_ai_in_use)
