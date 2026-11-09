package com.satori.domain.usecase.reminder

import com.satori.domain.model.Todo

/** The one platform alarm that wakes the app for the next reminder; [ReminderEngine] keeps it armed. */
interface ReminderAlarm {
    /** Arms the alarm for [atMs] (epoch ms), replacing the one armed before. */
    fun arm(atMs: Long)

    /** Disarms the alarm: no reminder is left to wait for. */
    fun cancel()
}

/** The reminder notifications, at most one per todo. */
interface ReminderNotifications {
    /**
     * Shows the reminder for [todo], replacing one already showing for it.
     *
     * @return false if the app may not post notifications at all (not allowed yet, or turned
     *   off), so nothing was shown and the reminder stays to be shown, until it is more than
     *   [ReminderRules.MAX_LATENESS_HOURS] hours late
     */
    fun show(todo: Todo): Boolean

    /** Removes the reminder of the todo [todoId], if it is showing. */
    fun dismiss(todoId: String)

    /** The ids of the todos whose reminder is showing. */
    fun showing(): Set<String>
}
