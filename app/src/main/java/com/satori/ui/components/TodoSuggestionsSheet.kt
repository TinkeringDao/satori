package com.satori.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.satori.domain.model.RecordingTitle
import com.satori.domain.model.Todo
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSheet
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.Hairline
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind

/**
 * The to-dos the AI found in recordings and left for the user to decide on: each with what to do,
 * where it came from and when, and Keep or Dismiss, or Move to ideas for one that is an idea for
 * how something could be rather than something to do. "Keep all" takes the rest in one tap.
 *
 * A dismissed one stays in the sheet as "Dismissed" with Undo, and is only dropped when the sheet
 * closes: nothing lists dismissed to-dos, so a mis-tap could not be taken back later. The sheet
 * closes by itself when none is left, a dismissed one included.
 *
 * @param titles The title of every recording by id, for "From <recording>"
 * @param nowMs The current time, from [rememberMinuteClock], for the due lines
 * @param onDismissSuggestion Drops a suggestion; called for each dismissed one as the sheet closes
 * @param onKeepAll Keeps the suggestions given, those not dismissed
 * @param onClose Closes the sheet
 * @param onMoveToIdeas Moves a suggestion to the ideas at once, as Keep keeps one; null leaves
 *   Move to ideas out
 */
@Composable
fun TodoSuggestionsSheet(
    suggestions: List<Todo>,
    titles: Map<String, RecordingTitle>,
    nowMs: Long,
    onKeep: (Todo) -> Unit,
    onDismissSuggestion: (Todo) -> Unit,
    onKeepAll: (List<Todo>) -> Unit,
    onClose: () -> Unit,
    onMoveToIdeas: ((Todo) -> Unit)? = null
) {
    var dismissedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val undecided = suggestions.filter { it.id !in dismissedIds }
    val close = {
        suggestions.filter { it.id in dismissedIds }.forEach(onDismissSuggestion)
        onClose()
    }
    LaunchedEffect(suggestions.isEmpty()) {
        if (suggestions.isEmpty()) onClose()
    }
    GrilleSheet(onDismiss = close) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.g_todo_suggestions_title),
                style = Grille.type.title,
                color = Grille.colors.ink,
                modifier = Modifier
                    .padding(horizontal = GrilleSpace.margin)
                    .semantics { heading() }
            )
            Spacer(Modifier.height(GrilleSpace.s))
            suggestions.forEach { suggestion ->
                Suggestion(
                    todo = suggestion,
                    source = suggestion.sessionId?.let(titles::get),
                    nowMs = nowMs,
                    dismissed = suggestion.id in dismissedIds,
                    onKeep = { onKeep(suggestion) },
                    onDismiss = { dismissedIds = dismissedIds + suggestion.id },
                    onUndo = { dismissedIds = dismissedIds - suggestion.id },
                    onMoveToIdeas = onMoveToIdeas?.let { move -> { move(suggestion) } }
                )
                Hairline(Modifier.padding(horizontal = GrilleSpace.margin))
            }
        }
        if (undecided.size > 1) {
            Spacer(Modifier.height(GrilleSpace.m))
            PillButton(
                text = stringResource(R.string.g_todo_keep_all),
                onClick = { onKeepAll(undecided) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GrilleSpace.margin)
            )
        }
    }
}

/**
 * One suggestion with Keep and Dismiss, and Move to ideas when [onMoveToIdeas] is given, or, once
 * dismissed, "Dismissed" and Undo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestion(
    todo: Todo,
    source: RecordingTitle?,
    nowMs: Long,
    dismissed: Boolean,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
    onUndo: () -> Unit,
    onMoveToIdeas: (() -> Unit)?
) {
    val due = todoDueText(todo.dueAt, todo.dueHasTime, nowMs)
    val from = source?.let { stringResource(R.string.g_todo_from, recordingTitleText(it)) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GrilleSpace.margin, vertical = GrilleSpace.m)
    ) {
        Text(
            text = todo.text,
            style = Grille.type.body,
            color = if (dismissed) Grille.colors.ash else Grille.colors.ink
        )
        val caption = when {
            from != null && due != null -> stringResource(R.string.g_home_meta, from, due.visible)
            else -> from ?: due?.visible
        }
        if (caption != null) {
            Text(
                text = caption,
                style = Grille.type.caption,
                color = Grille.colors.ash,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(GrilleSpace.s))
        if (dismissed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.g_todo_dismissed),
                    style = Grille.type.label,
                    color = Grille.colors.ash,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
                PillButton(
                    text = stringResource(R.string.g_todo_undo),
                    onClick = onUndo,
                    kind = PillKind.Outline,
                    contentDescription = stringResource(R.string.g_todo_undo_cd, todo.text)
                )
            }
        } else {
            // Three choices can take more than a line on a narrow screen, in German above all.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GrilleSpace.s),
                verticalArrangement = Arrangement.spacedBy(GrilleSpace.s)
            ) {
                PillButton(
                    text = stringResource(R.string.g_todo_keep),
                    onClick = onKeep,
                    kind = PillKind.Outline,
                    contentDescription = stringResource(R.string.g_todo_keep_cd, todo.text)
                )
                PillButton(
                    text = stringResource(R.string.g_todo_dismiss),
                    onClick = onDismiss,
                    kind = PillKind.Quiet,
                    contentDescription = stringResource(R.string.g_todo_dismiss_cd, todo.text)
                )
                if (onMoveToIdeas != null) {
                    PillButton(
                        text = stringResource(R.string.g_todo_move_to_ideas),
                        onClick = onMoveToIdeas,
                        kind = PillKind.Quiet,
                        contentDescription = stringResource(R.string.g_todo_move_to_ideas_cd, todo.text)
                    )
                }
            }
        }
    }
}
