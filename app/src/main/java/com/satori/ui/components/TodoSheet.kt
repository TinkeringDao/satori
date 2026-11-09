package com.satori.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerColors
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.usecase.todo.TodoEdit
import com.satori.domain.usecase.todo.TodoReminderTime
import com.satori.ui.R
import com.satori.ui.design.ConfirmSheet
import com.satori.ui.design.FormField
import com.satori.ui.design.FormSheet
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleIcons
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTextField
import com.satori.ui.design.PillButton
import com.satori.ui.design.PillKind
import com.satori.ui.design.SegmentedChoice
import com.satori.ui.design.clockTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The sheet of one to-do: its text, Mark as done, the recording it came from, when it is due, Move
 * to ideas and Delete. A tapped reminder opens it, so it can be ticked off from here too. Mark as
 * done comes right under the text: on the razr's cover the sheet shows little more than the text
 * and the due day above its Cancel and Save, and at the end of the sheet it lay below them, to be
 * found only by scrolling.
 *
 * The reminder is never typed: it follows the due day and time ([TodoEdit]), and the sheet says
 * when it will be as a caption, while it is still to come. The due day is a choice of No date,
 * Today, Tomorrow or a date from the picker; a time can be added to a day.
 *
 * Move to ideas is for an open or suggested to-do that is a wish for how something could be
 * rather than something to do; there nothing reminds of it.
 *
 * @param sourceTitle The title of the recording it came from, or null when that recording was
 *   deleted (the to-do is kept) or it was added by hand
 * @param onOpenSource Opens that recording
 * @param onSave Stores the text and the due day and time given
 * @param onComplete Marks the to-do done, as its circle in the list does
 * @param onMoveToIdeas Moves the to-do to the ideas; null leaves Move to ideas out
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodoSheet(
    todo: Todo,
    sourceTitle: String?,
    onOpenSource: () -> Unit,
    onSave: (text: String, date: LocalDate?, time: LocalTime?) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onMoveToIdeas: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    val now = remember(todo.id) { System.currentTimeMillis() }
    val today = remember(todo.id) { LocalDate.now(zone) }
    val start = remember(todo.id) { TodoEdit.dueOf(todo, zone) }

    var text by rememberSaveable(todo.id) { mutableStateOf(todo.text) }
    // The day as an epoch day and the time as minutes since midnight: both survive a rotation.
    var epochDay by rememberSaveable(todo.id) { mutableStateOf(start.date?.toEpochDay()) }
    var minuteOfDay by rememberSaveable(todo.id) { mutableStateOf(start.time?.let { it.hour * 60 + it.minute }) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    val date = epochDay?.let(LocalDate::ofEpochDay)
    val time = minuteOfDay?.let { LocalTime.of(it / 60, it % 60) }
    val reminderAt = remember(date, time) { TodoEdit.edited(todo, todo.text, date, time, now, zone).remindAt }

    FormSheet(
        title = stringResource(R.string.g_todo_sheet_title),
        confirmLabel = stringResource(R.string.g_todo_save),
        onConfirm = { onSave(text, date, time) },
        onDismiss = onDismiss,
        confirmEnabled = text.isNotBlank()
    ) {
        Spacer(Modifier.height(GrilleSpace.m))
        GrilleTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(R.string.g_todo_text_hint),
            singleLine = false,
            imeAction = ImeAction.Done
        )
        // A pill with an edge, as the way to the recording below it: quiet ash text right under
        // the text field would read as a hint.
        Spacer(Modifier.height(GrilleSpace.m))
        PillButton(
            text = stringResource(R.string.g_todo_mark_done),
            onClick = onComplete,
            kind = PillKind.Outline,
            trailingIcon = GrilleIcons.Check,
            contentPadding = PaddingValues(start = GrilleSpace.m, end = 12.dp)
        )
        if (sourceTitle != null) {
            // The way to the recording, so it looks like one: an edge and an arrow, not a caption.
            Spacer(Modifier.height(GrilleSpace.m))
            PillButton(
                text = stringResource(R.string.g_todo_from, sourceTitle),
                onClick = onOpenSource,
                kind = PillKind.Outline,
                trailingIcon = GrilleIcons.Forward,
                contentPadding = PaddingValues(start = GrilleSpace.m, end = 12.dp)
            )
        }

        FormField(label = stringResource(R.string.g_todo_field_due)) {
            val choice = when (date) {
                null -> 0
                today -> 1
                today.plusDays(1) -> 2
                else -> 3
            }
            SegmentedChoice(
                options = listOf(
                    stringResource(R.string.g_todo_due_none),
                    stringResource(R.string.g_todo_today),
                    stringResource(R.string.g_todo_tomorrow),
                    if (choice == 3 && date != null) {
                        weekdayDateText(date, date.year != today.year, locale)
                    } else {
                        stringResource(R.string.g_todo_due_pick)
                    }
                ),
                selectedIndex = choice,
                onSelect = { index ->
                    when (index) {
                        0 -> {
                            epochDay = null
                            minuteOfDay = null
                        }
                        1 -> epochDay = today.toEpochDay()
                        2 -> epochDay = today.plusDays(1).toEpochDay()
                        else -> pickingDate = true
                    }
                }
            )
        }

        if (date != null) {
            FormField(label = stringResource(R.string.g_todo_field_time)) {
                SegmentedChoice(
                    options = listOf(
                        stringResource(R.string.g_todo_time_none),
                        if (time != null) {
                            clockTime(context, TodoReminderTime.dueAt(date, time, zone))
                        } else {
                            stringResource(R.string.g_todo_time_pick)
                        }
                    ),
                    selectedIndex = if (time == null) 0 else 1,
                    onSelect = { index ->
                        if (index == 0) minuteOfDay = null else pickingTime = true
                    }
                )
            }
        }

        // Only a reminder still to come: one that has gone off would read as if it were.
        if (reminderAt != null && reminderAt > now) {
            Spacer(Modifier.height(GrilleSpace.m))
            Text(
                text = stringResource(R.string.g_todo_reminder_at, clockTime(context, reminderAt)),
                style = Grille.type.caption,
                color = Grille.colors.ash
            )
        }

        Spacer(Modifier.height(GrilleSpace.l))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(GrilleSpace.l)) {
            if (onMoveToIdeas != null && movesToIdeas(todo.status)) {
                PillButton(
                    text = stringResource(R.string.g_todo_move_to_ideas),
                    onClick = onMoveToIdeas,
                    kind = PillKind.Quiet,
                    contentPadding = FlushPill
                )
            }
            PillButton(
                text = stringResource(R.string.g_todo_delete),
                onClick = { confirmingDelete = true },
                kind = PillKind.Quiet,
                contentPadding = FlushPill
            )
        }
    }

    if (pickingDate) {
        DatePickerSheet(
            initial = date ?: today,
            firstDay = today,
            onPick = {
                epochDay = it.toEpochDay()
                pickingDate = false
            },
            onDismiss = { pickingDate = false }
        )
    }
    if (pickingTime) {
        TimePickerSheet(
            initial = time ?: LocalTime.of(DEFAULT_PICKER_HOUR, 0),
            onPick = {
                minuteOfDay = it.hour * 60 + it.minute
                pickingTime = false
            },
            onDismiss = { pickingTime = false }
        )
    }
    if (confirmingDelete) {
        ConfirmSheet(
            title = stringResource(R.string.g_todo_delete_title),
            text = stringResource(R.string.g_todo_delete_body),
            confirmLabel = stringResource(R.string.g_todo_delete),
            onConfirm = onDelete,
            onDismiss = { confirmingDelete = false }
        )
    }
}

/**
 * Whether a to-do in [status] offers Move to ideas: one still to do or to decide on. Any other is
 * done, dismissed or an idea already, and TodoActions.moveToIdeas would leave it as it is.
 */
internal fun movesToIdeas(status: TodoStatus): Boolean = status == TodoStatus.OPEN || status == TodoStatus.SUGGESTED

/** The calendar. Days before [firstDay] can't be picked: a to-do is not due in the past. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    firstDay: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    // Remembered: the picker's state is keyed by it, and a new object each time would reset the choice.
    val selectable = remember(firstDay) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= utcMidnight(firstDay)
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMidnight(initial),
        selectableDates = selectable
    )
    FormSheet(
        title = stringResource(R.string.g_todo_date_title),
        confirmLabel = stringResource(R.string.g_ok),
        onConfirm = {
            state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        },
        onDismiss = onDismiss,
        confirmEnabled = state.selectedDateMillis != null
    ) {
        Spacer(Modifier.height(GrilleSpace.s))
        // The sheet's own graphite shows through; the other colours come from the Grille scheme.
        DatePicker(
            state = state,
            title = null,
            headline = null,
            showModeToggle = false,
            colors = DatePickerDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

/** The clock, as a dial; on a short screen (the cover) as two number fields. Centred under the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    )
    val colors = timePickerColors()
    FormSheet(
        title = stringResource(R.string.g_todo_time_title),
        confirmLabel = stringResource(R.string.g_ok),
        onConfirm = { onPick(LocalTime.of(state.hour, state.minute)) },
        onDismiss = onDismiss
    ) {
        Spacer(Modifier.height(GrilleSpace.m))
        if (LocalConfiguration.current.screenHeightDp < COMPACT_SCREEN_DP) {
            TimeInput(state = state, colors = colors, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            TimePicker(state = state, colors = colors, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

/** The time picker in the Grille palette: ink and ash on the sheet, with the dial a step lighter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun timePickerColors(): TimePickerColors {
    val colors = Grille.colors
    return TimePickerDefaults.colors(
        clockDialColor = colors.unlit,
        clockDialSelectedContentColor = colors.void,
        clockDialUnselectedContentColor = colors.ink,
        selectorColor = colors.ink,
        containerColor = Color.Transparent,
        periodSelectorBorderColor = colors.ash,
        periodSelectorSelectedContainerColor = colors.ink,
        periodSelectorUnselectedContainerColor = Color.Transparent,
        periodSelectorSelectedContentColor = colors.void,
        periodSelectorUnselectedContentColor = colors.ink,
        timeSelectorSelectedContainerColor = colors.ink,
        timeSelectorUnselectedContainerColor = colors.unlit,
        timeSelectorSelectedContentColor = colors.void,
        timeSelectorUnselectedContentColor = colors.ink
    )
}

/** The date picker counts days as UTC midnights. */
private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** A quiet pill with no side padding, so its text lines up with the fields above it. */
private val FlushPill = PaddingValues(horizontal = 0.dp)

/** A screen shorter than this (dp) gets the compact time input instead of the dial. */
private const val COMPACT_SCREEN_DP = 560

/** The hour the time picker opens on when the to-do has no time yet. */
private const val DEFAULT_PICKER_HOUR = 9
