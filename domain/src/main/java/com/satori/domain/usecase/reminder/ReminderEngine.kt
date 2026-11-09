package com.satori.domain.usecase.reminder

import com.satori.domain.model.TodoStatus
import com.satori.domain.repository.TodoRepository
import com.satori.domain.usecase.todo.TodoChangeLog
import com.satori.domain.usecase.todo.TodoSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs the reminders against the todos: posts each reminder when it comes due, keeps one alarm
 * armed for the next, and carries out Done and Snooze from a reminder. The decisions are
 * [ReminderRules]; this class applies them to the store and the platform.
 *
 * Whether a reminder was shown is kept on its todo (TodoRepository.markReminderShown), so each
 * one is posted once for each time it is set to: also when the todo was written after that time
 * had passed, or the time came while the phone was off. One more than
 * [ReminderRules.MAX_LATENESS_HOURS] hours late by then is marked shown without being posted, and
 * its todo stays open. A mark later than the clock doesn't count ([ReminderRules.isShown]), as it
 * was made before the clock was set back.
 *
 * Every call ends with a [sync], so the alarm and the notifications match the todos afterwards.
 * Syncs run one at a time, and only they touch the platform parts, which therefore need not be
 * thread-safe.
 *
 * @property todos Where the todos, their reminder times and what was shown are kept
 * @property alarm The one alarm that wakes the app for the next reminder
 * @property notifications The reminders on show
 * @property log Hears of Done on a reminder, also of one that leaves its todo as it was
 * @property clock The current time, epoch ms
 */
class ReminderEngine(
    private val todos: TodoRepository,
    private val alarm: ReminderAlarm,
    private val notifications: ReminderNotifications,
    private val log: TodoChangeLog = TodoChangeLog.NONE,
    private val clock: () -> Long
) {
    private val mutex = Mutex()

    /**
     * Brings the reminders in step with the todos now. It removes reminders on show whose todo is
     * done, dismissed or deleted, or reminds later now. It posts each reminder whose time has
     * come and that hasn't been shown for that time, oldest first, and marks it shown; one more
     * than [ReminderRules.MAX_LATENESS_HOURS] hours late is only marked. Then it arms the alarm
     * for the next reminder, or cancels it when none is left.
     *
     * A store may reschedule after each of its writes: a sync started from within the writes
     * this one makes returns at once. This one brings everything in step anyway, and the other
     * would wait for its lock forever.
     */
    suspend fun sync() {
        if (currentCoroutineContext()[SyncInProgress]?.engine === this) return
        mutex.withLock { withContext(SyncInProgress(this)) { syncLocked() } }
    }

    /** Done on the reminder of [todoId]: marks the todo done if it is still open, then syncs. */
    suspend fun complete(todoId: String) {
        val todo = todos.get(todoId)
        if (todo != null && ReminderRules.completes(todo)) {
            todos.setStatus(todoId, TodoStatus.DONE, completedAt = clock())
            log.changed(todoId, todo.status, TodoStatus.DONE, TodoSource.NOTIFICATION)
        } else {
            log.changed(todoId, todo?.status, todo?.status, TodoSource.NOTIFICATION)
        }
        // Outside the lock: a sync the write starts runs before this one, which puts right
        // anything that changed meanwhile.
        sync()
    }

    /**
     * Snooze on the reminder of [todoId]: reminds again [ReminderRules.SNOOZE_MINUTES] minutes
     * from now if the todo is still open, then syncs. Only the reminder time is written, so an
     * edit made to the todo meanwhile is kept.
     */
    suspend fun snooze(todoId: String) {
        val until = todos.get(todoId)?.let { ReminderRules.snoozeUntil(it, clock()) }
        if (until != null) todos.setRemindAt(todoId, until)
        sync()
    }

    private suspend fun syncLocked() {
        val now = clock()

        for (id in notifications.showing()) {
            val todo = todos.get(id)
            if (todo == null || !ReminderRules.stillDue(todo, now)) notifications.dismiss(id)
        }

        val due = todos.dueReminders(now)
        // Too late to be of use: marked without posting, so the shade doesn't fill with old
        // reminders. The todos stay open.
        for (todo in ReminderRules.tooLate(due, now)) todos.markReminderShown(todo.id, now)
        for (todo in ReminderRules.toPost(due, now)) {
            // Marked only once it is up: a run cut short posts it again, replacing the first (both
            // carry the todo's id), rather than not at all. While the app may not post
            // notifications it stays to be shown.
            if (notifications.show(todo)) todos.markReminderShown(todo.id, now)
        }

        val armAt = ReminderRules.alarmAt(todos.nextReminderAfter(now), now)
        if (armAt != null) alarm.arm(armAt) else alarm.cancel()
    }

    /** Set on the coroutine of a running sync of [engine]; see [sync]. */
    private class SyncInProgress(val engine: ReminderEngine) : AbstractCoroutineContextElement(SyncInProgress) {
        companion object : CoroutineContext.Key<SyncInProgress>
    }
}
