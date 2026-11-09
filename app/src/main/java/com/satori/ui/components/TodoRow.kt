package com.satori.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.ui.R
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace

/**
 * One to-do in a list: a circle to tick it off, its text, and under it when it is due.
 *
 * The circle and the rest are separate targets, each 48 dp or more, and separate stops for
 * TalkBack: the circle is a checkbox ("Mark as done"), the rest reads the text and when it is
 * due and opens the to-do. A done to-do has a filled circle and no due line; the home screen
 * never lists one, the list of a recording's to-dos will.
 *
 * @param nowMs The current time, from [rememberMinuteClock], for the due line
 * @param onToggle Ticks the circle: completes an open to-do, opens a done one again
 * @param onOpen Opens the to-do to edit it
 */
@Composable
fun TodoRow(
    todo: Todo,
    nowMs: Long,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val done = todo.status == TodoStatus.DONE
    val due = if (done) null else todoDueText(todo.dueAt, todo.dueHasTime, nowMs)
    val spoken = if (due != null) "${todo.text}, ${due.spoken}" else todo.text
    val toggleDescription = stringResource(if (done) R.string.g_todo_mark_undone else R.string.g_todo_mark_done)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = CIRCLE_INSET, end = GrilleSpace.margin),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TodoCircle(done = done, description = toggleDescription, onToggle = onToggle)
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ROW_HEIGHT)
                .clickable(onClickLabel = stringResource(R.string.g_todo_edit), onClick = onOpen)
                // Not plain semantics: TalkBack skips a description on a row that merges its text.
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(start = GrilleSpace.s, top = GrilleSpace.s, bottom = GrilleSpace.s),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = todo.text,
                style = Grille.type.body,
                color = if (done) Grille.colors.ash else Grille.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (due != null) {
                Text(
                    text = due.visible,
                    style = Grille.type.caption,
                    color = if (due.overdue) Grille.colors.ink else Grille.colors.ash,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The ring that ticks a to-do off: an outline in ash, filled ink with a check when done. */
@Composable
private fun TodoCircle(done: Boolean, description: String, onToggle: () -> Unit) {
    val colors = Grille.colors
    Box(
        modifier = Modifier
            .size(GrilleSpace.touch)
            .clip(CircleShape)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(RING_SIZE)
                .background(if (done) colors.ink else Color.Transparent, CircleShape)
                .border(1.dp, if (done) colors.ink else colors.ash, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    imageVector = GrilleIcons.Check,
                    contentDescription = null,
                    tint = colors.void,
                    modifier = Modifier.size(CHECK_SIZE)
                )
            }
        }
    }
}

/** At least this tall, so the text is a 48 dp target with room around it. */
private val ROW_HEIGHT = 56.dp

private val RING_SIZE = 24.dp

private val CHECK_SIZE = 16.dp

/** The ring sits in the middle of its 48 dp target; this inset lines the ring up with the screen margin. */
private val CIRCLE_INSET = GrilleSpace.margin - (GrilleSpace.touch - RING_SIZE) / 2
