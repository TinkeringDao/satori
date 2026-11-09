package com.satori.ui.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.satori.domain.usecase.todo.DueDay
import com.satori.domain.usecase.todo.TodoDueLabel
import com.satori.ui.R
import com.satori.ui.design.clockTime
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The due line under a to-do.
 *
 * @property visible What is shown, "Today 9:00", or "Overdue · Oct 8" when it is past
 * @property spoken What TalkBack adds after the to-do's text, "due Today 9:00"
 * @property overdue The due time has passed: the line is drawn in ink, not ash
 */
class TodoDueText(val visible: String, val spoken: String, val overdue: Boolean)

/**
 * The due line for a to-do due at [dueAt], worded by [TodoDueLabel] and put in the app's language
 * and the phone's 12/24-hour style; null when it has no due time.
 *
 * @param nowMs The current time, from [rememberMinuteClock] so "Today" and "Overdue" stay right
 */
@Composable
fun todoDueText(dueAt: Long?, dueHasTime: Boolean, nowMs: Long): TodoDueText? {
    if (dueAt == null) return null
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val due = TodoDueLabel.of(dueAt, dueHasTime, nowMs, ZoneId.systemDefault())
    val day = when (val dueDay = due.day) {
        DueDay.Today -> stringResource(R.string.g_todo_today)
        DueDay.Tomorrow -> stringResource(R.string.g_todo_tomorrow)
        is DueDay.Weekday -> dueDay.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        is DueDay.Date -> dateText(dueDay.date, dueDay.withYear, locale)
    }
    val text = if (due.hasTime) stringResource(R.string.g_todo_day_time, day, clockTime(context, dueAt)) else day
    return if (due.overdue) {
        TodoDueText(
            visible = stringResource(R.string.g_todo_overdue, text),
            spoken = stringResource(R.string.g_todo_overdue_cd, text),
            overdue = true
        )
    } else {
        TodoDueText(visible = text, spoken = stringResource(R.string.g_todo_due_cd, text), overdue = false)
    }
}

/** "Oct 16", with the year ("Oct 16, 2027") when [withYear]; day and month in the order of [locale]. */
internal fun dateText(date: LocalDate, withYear: Boolean, locale: Locale): String =
    format(date, if (withYear) "MMMdy" else "MMMd", locale)

/** "Fri, Oct 16", with the year when [withYear]. */
internal fun weekdayDateText(date: LocalDate, withYear: Boolean, locale: Locale): String =
    format(date, if (withYear) "EEEMMMdy" else "EEEMMMd", locale)

private fun format(date: LocalDate, skeleton: String, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)

/**
 * The current time, set again at the start of every minute while the screen is on show, so a due
 * line turns "Overdue" at its minute and "Tomorrow" becomes "Today" at midnight without the user
 * leaving the screen. Read it where it is used, to recompose only that.
 */
@Composable
fun rememberMinuteClock(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now.longValue = System.currentTimeMillis()
                delay(MINUTE_MS - now.longValue % MINUTE_MS)
            }
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L
