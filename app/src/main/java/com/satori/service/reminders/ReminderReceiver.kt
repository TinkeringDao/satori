package com.satori.service.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The reminder alarm, and Done and Snooze on a reminder. When the alarm rings it posts every
 * reminder that came due and arms the alarm for the next one.
 *
 * Not exported: only the app's own PendingIntents reach it ([AndroidReminderAlarm],
 * [AndroidReminderNotifications]).
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.satori.action.REMINDER_ALARM"
        const val ACTION_DONE = "com.satori.action.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.satori.action.REMINDER_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminders = context.reminderScheduler()
        val todoId = intent.getStringExtra(AndroidReminderNotifications.EXTRA_TODO_ID)
        when (intent.action) {
            ACTION_ALARM -> doInBackground { reminders.reschedule() }
            ACTION_DONE -> if (todoId != null) doInBackground { reminders.markDone(todoId) }
            ACTION_SNOOZE -> if (todoId != null) doInBackground { reminders.snooze(todoId) }
        }
    }
}
