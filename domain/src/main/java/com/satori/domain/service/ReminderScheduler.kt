package com.satori.domain.service

/**
 * Keeps the reminders in step with the todos: the reminder notifications, and the one platform
 * alarm that wakes the app for the next reminder.
 *
 * Call [reschedule] when the app starts and after every change to a todo's reminder time or
 * status: extraction, keep, dismiss, done and undone, an edit, a delete. Calling it from inside
 * the todo store's own writes is safe.
 */
interface ReminderScheduler {

    /**
     * Posts each reminder whose time has come and that hasn't been shown for that time, also one
     * that came due while the phone was off or notifications weren't allowed, or that was written
     * with a time already past. One more than
     * [com.satori.domain.usecase.reminder.ReminderRules.MAX_LATENESS_HOURS] hours late is
     * marked shown without being posted; its todo stays open. Removes the reminders whose todo is
     * done, dismissed or deleted, or reminds later now. Then arms the alarm for the next reminder,
     * or cancels it when none is left.
     */
    suspend fun reschedule()
}
