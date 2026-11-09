package com.satori.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.satori.domain.finish.FinishingStatus
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.SessionListItem
import com.satori.domain.model.Todo
import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import com.satori.domain.usecase.todo.ReminderPrompt
import com.satori.domain.usecase.todo.TodoSource
import com.satori.presentation.firstrun.StripTapShield
import com.satori.presentation.sessions.IdeasRow
import com.satori.presentation.sessions.SessionsError
import com.satori.presentation.sessions.SessionsUiState
import com.satori.ui.R
import com.satori.ui.components.TodoRow
import com.satori.ui.components.recordingTitleText
import com.satori.ui.design.DotoNumber
import com.satori.ui.design.ErrorNote
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.NoticeHost
import com.satori.ui.design.NoticeState
import com.satori.ui.design.formatDuration
import com.satori.ui.design.relativeDateTime

/**
 * The scrolling list of the home screen: the To do section when there is something to do or
 * decide, or there are ideas, then the recordings. With neither, one sentence that says what to do.
 * Above it, when there is one, the status strip ([StatusStrip]), which stays in view as the list
 * scrolls; its pills ignore taps for a moment after it changed by itself ([StripTapShield]).
 *
 * @param compact The cover screen: rows are narrower, so titles may take two lines
 * @param listState The list's scroll, kept by the screen, so a fold or a turn of the screen,
 *   which lays home out anew, keeps it; the screen reads from it whether the ideas row is on
 *   screen ([IDEAS_ROW_KEY])
 * @param nowMs The current time, from rememberMinuteClock, for the due lines
 * @param notices Where the notice for a completed to-do shows, over the foot of the list
 * @param onOpenIdeas Opens the Ideas page, from the ideas row
 * @param onStripTap What one of the strip's pills asks for
 */
@Composable
internal fun HomeList(
    state: SessionsUiState,
    compact: Boolean,
    listState: LazyListState,
    nowMs: Long,
    showAllTodos: Boolean,
    reminderPrompt: ReminderPrompt,
    notices: NoticeState,
    onShowAllTodos: () -> Unit,
    onShowSuggestions: () -> Unit,
    onOpenIdeas: () -> Unit,
    onTurnOnReminders: () -> Unit,
    onCompleteTodo: (Todo, TodoSource) -> Unit,
    onOpenTodo: (Todo) -> Unit,
    onOpenRecording: (String) -> Unit,
    onDismissError: () -> Unit,
    onStripTap: (StripTap) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalConfiguration.current.locales[0]
    val strip = state.strip?.takeUnless { state.isLoading }
    val stripCopy = strip?.let { shown -> stripCopy(shown) { modelSizeLabel(it, locale) } }
    val shield = remember { StripTapShield() }
    SideEffect { shield.saw(strip?.face, SystemClock.uptimeMillis()) }
    Box(modifier) {
        Column(Modifier.fillMaxSize()) {
            state.error?.let { error ->
                ErrorNote(
                    text = stringResource(
                        when (error) {
                            SessionsError.Load -> R.string.g_home_error_load
                            SessionsError.Create -> R.string.g_home_error_create
                            SessionsError.Todo -> R.string.g_todo_error
                        }
                    ),
                    onDismiss = onDismissError
                )
            }
            stripCopy?.let { copy ->
                StatusStrip(copy = copy, acceptsTap = { shield.accepts(SystemClock.uptimeMillis()) }, onTap = onStripTap)
                Hairline()
            }
            when {
                state.isLoading -> Unit
                state.isEmpty -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.l),
                    // Close to the grille it speaks of: above the dock, or in the middle of the cover's list.
                    contentAlignment = if (compact) Alignment.Center else Alignment.BottomCenter
                ) {
                    Text(
                        text = stringResource(R.string.g_home_empty),
                        style = Grille.type.body,
                        color = Grille.colors.ash,
                        textAlign = TextAlign.Center
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(top = GrilleSpace.s, bottom = GrilleSpace.m)
                ) {
                    if (state.hasTodoSection) {
                        todoSection(
                            state = state,
                            nowMs = nowMs,
                            showAll = showAllTodos,
                            reminderPrompt = reminderPrompt,
                            onShowAll = onShowAllTodos,
                            onShowSuggestions = onShowSuggestions,
                            onOpenIdeas = onOpenIdeas,
                            onTurnOnReminders = onTurnOnReminders,
                            onComplete = onCompleteTodo,
                            onOpen = onOpenTodo
                        )
                        // The recordings read as the second part of the list.
                        if (state.recordings.isNotEmpty()) {
                            item(key = "recordings-heading") {
                                SectionHeading(title = stringResource(R.string.g_home_recordings), count = null)
                            }
                        }
                    }
                    // Lines go between recordings: the last one is closed by the dock's line, or by
                    // nothing on the cover screen.
                    itemsIndexed(state.recordings, key = { _, item -> "recording:${item.session.id}" }) { index, item ->
                        SessionRow(
                            item = item,
                            finishing = state.finishing[item.session.id],
                            compact = compact,
                            onClick = { onOpenRecording(item.session.id) }
                        )
                        if (index < state.recordings.lastIndex) {
                            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
                        }
                    }
                }
            }
        }
        NoticeHost(notices, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The To do section: a heading with the number of open to-dos, up to [SHOWN_TODOS] of them, "Show
 * all" for the rest, a quiet row for the suggestions, one for the ideas, and, when reminders can't
 * show, a quiet row that says so.
 */
private fun LazyListScope.todoSection(
    state: SessionsUiState,
    nowMs: Long,
    showAll: Boolean,
    reminderPrompt: ReminderPrompt,
    onShowAll: () -> Unit,
    onShowSuggestions: () -> Unit,
    onOpenIdeas: () -> Unit,
    onTurnOnReminders: () -> Unit,
    onComplete: (Todo, TodoSource) -> Unit,
    onOpen: (Todo) -> Unit
) {
    val open = state.openTodos
    item(key = "todo-heading") {
        SectionHeading(title = stringResource(R.string.g_todo_heading), count = open.size.takeIf { it > 0 })
    }
    itemsIndexed(if (showAll) open else open.take(SHOWN_TODOS), key = { _, todo -> "todo:${todo.id}" }) { index, todo ->
        Column(Modifier.animateItem()) {
            TodoRow(todo = todo, nowMs = nowMs, onToggle = { onComplete(todo, todoSourceAt(index)) }, onOpen = { onOpen(todo) })
            Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
        }
    }
    if (!showAll && open.size > SHOWN_TODOS) {
        item(key = "todo-show-all") {
            QuietRow(text = stringResource(R.string.g_todo_show_all, open.size), onClick = onShowAll)
        }
    }
    if (state.suggestions.isNotEmpty()) {
        item(key = "todo-suggested") {
            QuietRow(
                text = pluralStringResource(R.plurals.g_todo_suggested, state.suggestions.size, state.suggestions.size),
                onClick = onShowSuggestions
            )
        }
    }
    // Whether the owner has seen it is the screen's to tell, from the list's layout: an item can be
    // composed off screen, ahead of a scroll, and is laid out anew by a fold.
    state.ideasRow?.let { row ->
        item(key = IDEAS_ROW_KEY) {
            QuietRow(text = ideasRowText(row), onClick = onOpenIdeas)
        }
    }
    if (reminderPrompt == ReminderPrompt.TurnedOff) {
        item(key = "todo-reminders-off") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = GrilleSpace.touch)
                    .clickable(role = Role.Button, onClick = onTurnOnReminders)
                    .padding(horizontal = GrilleSpace.margin),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.m)
            ) {
                Text(
                    text = stringResource(R.string.g_todo_reminders_off),
                    style = Grille.type.body,
                    color = Grille.colors.ash,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.g_todo_reminders_turn_on),
                    style = Grille.type.label,
                    color = Grille.colors.ink
                )
            }
        }
    }
}

/** A heading of the list; [count] is shown at the right, in ash. */
@Composable
private fun SectionHeading(title: String, count: Int?) {
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

/** A row of ash text that opens more: "Show all 7", "3 suggested", "4 ideas, 2 new". */
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

/**
 * Whether the item [key] lies on screen in full, as far as this layout of a list goes: neither
 * cut by an edge nor only composed ahead of a scroll, which leaves it out of the visible items.
 */
internal fun LazyListLayoutInfo.showsInFull(key: Any): Boolean {
    val item = visibleItemsInfo.firstOrNull { it.key == key } ?: return false
    return item.offset >= viewportStartOffset && item.offset + item.size <= viewportEndOffset
}

/** "4 ideas", or "4 ideas, 2 new" when some were found since the owner last saw the row. */
@Composable
private fun ideasRowText(row: IdeasRow): String {
    val count = pluralStringResource(R.plurals.g_ideas_row, row.count, row.count)
    return row.newCount?.let { new ->
        stringResource(R.string.g_ideas_row_with_new, count, pluralStringResource(R.plurals.g_ideas_new, new, new))
    } ?: count
}

/** What the line under a recording's title says after when the recording was made. */
internal sealed interface MetaDetail {
    /** How far its finishing in the background has come: "Today, 09:07 · Writing notes". */
    data class Finishing(val status: FinishingStatus) : MetaDetail

    /**
     * Its finishing found too little text for notes ([FinishingStatus.noNotes]): "Today, 09:07 ·
     * Too short for notes: about 20 words are needed".
     */
    data object NoNotes : MetaDetail

    /** The category of a recording that has a name of its own: "Today, 09:07 · Meeting". */
    data class Category(val name: String) : MetaDetail
}

/**
 * What the meta line of a recording with [title] says after its date, or null for the date alone.
 * While the recording is being finished in the background ([finishing]) that is its step, in
 * place of the category, so home shows each step and then nothing; once the finishing has found
 * too little text in it for notes, right after Stop, it says that instead, until the name, the
 * to-dos and the map are done. Otherwise a recording with a name of its own says its category;
 * one called by its category doesn't need it said again.
 */
internal fun metaDetail(title: RecordingTitle, finishing: FinishingStatus?): MetaDetail? {
    val category = title.categoryName
    return when {
        finishing?.noNotes == true -> MetaDetail.NoNotes
        finishing != null -> MetaDetail.Finishing(finishing)
        title.name != null && category != null -> MetaDetail.Category(category)
        else -> null
    }
}

/**
 * A recording: its title, when it was made and [metaDetail], and its length. The title takes one
 * line, or two on the cover screen where the row is narrow; the line under it takes two there and
 * while the recording is being finished. The finishing step is ash like the rest of the line: red
 * is for a live microphone only.
 *
 * @param finishing How far the recording's finishing in the background has come, or null when it
 *   isn't being finished
 */
@Composable
private fun SessionRow(item: SessionListItem, finishing: FinishingStatus?, compact: Boolean, onClick: () -> Unit) {
    val title = item.title
    val date = relativeDateTime(item.session.createdAt)
    val detail = when (val shown = metaDetail(title, finishing)) {
        is MetaDetail.Finishing -> finishingStatusText(shown.status)
        MetaDetail.NoNotes -> pluralStringResource(R.plurals.g_home_notes_too_short, NOTES_MIN_WORDS, NOTES_MIN_WORDS)
        is MetaDetail.Category -> shown.name
        null -> null
    }
    val meta = if (detail != null) stringResource(R.string.g_home_meta, date, detail) else date
    val length = formatDuration(item.session.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = recordingTitleText(title),
                style = Grille.type.body,
                color = Grille.colors.ink,
                maxLines = if (compact) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            // A step such as "Making a better transcript, 42%" may need the second line, where
            // ellipsizing would cut the part that changes.
            Text(
                text = meta,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                maxLines = if (compact || finishing != null) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(GrilleSpace.m))
        DotoNumber(text = length, contentDescription = stringResource(R.string.g_home_length_cd, length))
    }
}

/** How many open to-dos the list shows before "Show all". */
private const val SHOWN_TODOS = 5

/** The words a recording needs for notes, which its line says when it has fewer. */
private const val NOTES_MIN_WORDS = GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT

/**
 * Where a tick on the to-do at [index] of home's list comes from, for the field log: one of the
 * first [SHOWN_TODOS] is home's, one further down the full list's, after Show all.
 */
internal fun todoSourceAt(index: Int): TodoSource = if (index < SHOWN_TODOS) TodoSource.HOME else TodoSource.LIST

/** The key of the ideas row in home's list. */
internal const val IDEAS_ROW_KEY = "todo-ideas"
