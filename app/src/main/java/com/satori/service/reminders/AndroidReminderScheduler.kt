package com.satori.service.reminders

import com.satori.domain.repository.TodoRepository
import com.satori.domain.service.ReminderScheduler
import com.satori.domain.usecase.reminder.ReminderEngine
import com.satori.domain.usecase.todo.TodoChangeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ReminderScheduler] on Android: one exact alarm for the next reminder, a notification for each
 * reminder that comes due, and Done and Snooze from that notification. The logic lives in the
 * domain ([ReminderEngine], [com.satori.domain.usecase.reminder.ReminderRules]); this
 * class wires it to AlarmManager and NotificationManager. Whether a reminder was shown is kept on
 * its todo, in the database.
 *
 * [reschedule] does more than arm the alarm. It also posts reminders that are due and haven't
 * been shown: ones that came due while the phone was off, the app was stopped or notifications
 * weren't allowed, and ones written with a time already past, up to
 * [com.satori.domain.usecase.reminder.ReminderRules.MAX_LATENESS_HOURS] late; a later one
 * is marked shown without a notification and its todo stays open. It removes reminders whose todo is
 * done, dismissed, deleted or reminds later now. Call it after every change to a todo's reminder
 * or status, and when the app starts. [ReminderRescheduleReceiver] calls it after a reboot, an
 * app update, a clock or time zone change and when the exact-alarm permission is granted.
 *
 * Every call switches to Dispatchers.IO, so callers may be on the main thread. Calling it from
 * inside the todo store's own writes is safe too (see [ReminderEngine.sync]).
 */
@Singleton
class AndroidReminderScheduler @Inject constructor(
    todoRepository: TodoRepository,
    alarm: AndroidReminderAlarm,
    private val notifications: AndroidReminderNotifications,
    changeLog: TodoChangeLog
) : ReminderScheduler {

    private val engine = ReminderEngine(todoRepository, alarm, notifications, changeLog, System::currentTimeMillis)

    override suspend fun reschedule() = withContext(Dispatchers.IO) {
        // Early, so the channel can be set up in the system settings before the first reminder
        notifications.createChannel()
        engine.sync()
    }

    /** Done on the reminder of [todoId]: marks the todo done if it is still open. */
    suspend fun markDone(todoId: String) = withContext(Dispatchers.IO) { engine.complete(todoId) }

    /** Snooze on the reminder of [todoId]: reminds again in ten minutes if it is still open. */
    suspend fun snooze(todoId: String) = withContext(Dispatchers.IO) { engine.snooze(todoId) }
}
