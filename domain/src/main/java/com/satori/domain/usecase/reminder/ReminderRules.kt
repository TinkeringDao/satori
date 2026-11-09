package com.satori.domain.usecase.reminder

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus

/**
 * The decisions behind reminders, free of Android so they can be tested: which reminders to
 * post, when the one alarm should ring, and what Done and Snooze change.
 *
 * Only open todos remind (see [TodoStatus]). Each todo carries whether its reminder was shown
 * ([Todo.reminderShownAt]), so a reminder is posted once for each time it is set to, whenever
 * the todo was written. One that came due while the phone was off, the app was stopped or
 * notifications weren't allowed, or that was written with a time already past, is posted late
 * rather than never, up to [MAX_LATENESS_HOURS] hours late. One later than that is not posted
 * ([tooLate]), so old reminders can't fill the shade all at once; its todo stays open.
 */
object ReminderRules {

    /** How long Snooze puts a reminder off. */
    const val SNOOZE_MINUTES = 10

    /** How late a reminder may still be posted; see [tooLate]. */
    const val MAX_LATENESS_HOURS = 12

    private const val SNOOZE_MS = SNOOZE_MINUTES * 60_000L

    private const val MAX_LATENESS_MS = MAX_LATENESS_HOURS * 3_600_000L

    /**
     * The reminders to post at [nowMs]: those of open todos whose time has come (now included),
     * that haven't been shown for that time yet and are at most [MAX_LATENESS_HOURS] hours late,
     * oldest first.
     */
    fun toPost(candidates: List<Todo>, nowMs: Long): List<Todo> =
        due(candidates, nowMs).filterNot { isTooLate(it, nowMs) }

    /**
     * The reminders that are due at [nowMs] and not shown yet, like those of [toPost], but more
     * than [MAX_LATENESS_HOURS] hours late, oldest first. They are marked shown without being
     * posted. That happens after notifications were off for a long time, or to a todo written
     * long after its time, such as one found by reading an old recording again.
     */
    fun tooLate(candidates: List<Todo>, nowMs: Long): List<Todo> =
        due(candidates, nowMs).filter { isTooLate(it, nowMs) }

    /**
     * Whether the reminder of [todo] has been shown for its current time, as seen at [nowMs]: its
     * mark lies between that time and now. A mark earlier than the time belongs to a time the
     * reminder had before, as no reminder is shown before its time. A mark later than now was
     * made before the clock was set back, so it doesn't count either.
     */
    fun isShown(todo: Todo, nowMs: Long): Boolean {
        val remindAt = todo.remindAt ?: return false
        val shownAt = todo.reminderShownAt ?: return false
        return shownAt in remindAt..nowMs
    }

    /**
     * Whether a reminder showing for [todo] still applies at [nowMs]: the todo is open and its
     * reminder time has come. It no longer does once the todo is done or dismissed, or its
     * reminder was removed or moved later, or the clock was set back to before it.
     */
    fun stillDue(todo: Todo, nowMs: Long): Boolean = todo.remindsAt { it <= nowMs }

    /**
     * When the alarm should ring for [next], the earliest reminder after [nowMs]: its reminder
     * time, or null for no alarm. A time that is not ahead is refused, as the alarm would ring at
     * once, find nothing new and be armed for the same time again.
     */
    fun alarmAt(next: Todo?, nowMs: Long): Long? =
        next?.remindAt?.takeIf { next.status == TodoStatus.OPEN && it > nowMs }

    /**
     * The new reminder time of [todo] after Snooze at [nowMs]: [SNOOZE_MINUTES] minutes after the
     * tap. Null when the todo is no longer open, as it reminds of nothing then.
     */
    fun snoozeUntil(todo: Todo, nowMs: Long): Long? =
        if (todo.status != TodoStatus.OPEN) null else nowMs + SNOOZE_MS

    /**
     * Whether Done on a reminder marks [todo] done: only while it is open. One dismissed or done
     * in the app since the reminder was posted keeps that status.
     */
    fun completes(todo: Todo): Boolean = todo.status == TodoStatus.OPEN

    /** Open, due at [nowMs] and not shown for that time, oldest first. */
    private fun due(candidates: List<Todo>, nowMs: Long): List<Todo> =
        candidates.filter { todo -> todo.remindsAt { it <= nowMs } && !isShown(todo, nowMs) }.sortedBy { it.remindAt }

    private fun isTooLate(todo: Todo, nowMs: Long): Boolean =
        todo.remindAt?.let { nowMs - it > MAX_LATENESS_MS } == true

    private inline fun Todo.remindsAt(test: (Long) -> Boolean): Boolean =
        status == TodoStatus.OPEN && remindAt?.let(test) == true
}
