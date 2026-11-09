package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The due day and time of a to-do as the edit sheet shows them.
 *
 * @property date The due day, or null for none
 * @property time The due clock time, or null for a to-do due on a day only (always null without a day)
 */
data class TodoDueDraft(val date: LocalDate?, val time: LocalTime?)

/** What saving the edit sheet does to a to-do. */
object TodoEdit {

    /** The due day and time of [todo] in [zone], to start the edit sheet from. */
    fun dueOf(todo: Todo, zone: ZoneId): TodoDueDraft {
        val due = todo.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: return TodoDueDraft(null, null)
        val time = due.toLocalTime().withSecond(0).withNano(0).takeIf { todo.dueHasTime }
        return TodoDueDraft(due.toLocalDate(), time)
    }

    /**
     * [original] with the text, due day and time of the sheet.
     *
     * The reminder follows the due time ([TodoReminderTime]) whenever the user changed that. When
     * only the text changed the reminder stays as it was, a snooze included. A changed due time
     * is a firm one, so the words it was taken from and its tentativeness are dropped; an
     * appointment without a day is a plain task.
     *
     * @param text The new text; a blank one keeps the old
     * @param date The due day, or null for none
     * @param time The due clock time; ignored without [date]
     * @param nowMs The current time, for the reminder and updatedAt
     */
    fun edited(
        original: Todo,
        text: String,
        date: LocalDate?,
        time: LocalTime?,
        nowMs: Long,
        zone: ZoneId
    ): Todo {
        val dueAt = date?.let { TodoReminderTime.dueAt(it, time, zone) }
        val dueHasTime = date != null && time != null
        val dueChanged = dueAt != original.dueAt || dueHasTime != original.dueHasTime
        val kind = if (original.kind == TodoKind.APPOINTMENT && dueAt != null) TodoKind.APPOINTMENT else TodoKind.TASK
        return original.copy(
            text = text.trim().ifEmpty { original.text },
            kind = kind,
            certainty = if (dueChanged) TodoCertainty.CONFIRMED else original.certainty,
            dueAt = dueAt,
            dueHasTime = dueHasTime,
            timePhrase = original.timePhrase.takeUnless { dueChanged },
            remindAt = if (dueChanged) {
                TodoReminderTime.of(dueAt, dueHasTime, kind, nowMs, zone)
            } else {
                original.remindAt
            },
            updatedAt = nowMs
        )
    }
}
