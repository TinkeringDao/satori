package com.satori.service.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import androidx.core.app.NotificationCompat
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoKind
import com.satori.domain.usecase.reminder.ReminderNotifications
import com.satori.domain.usecase.reminder.ReminderRules
import com.satori.ui.MainActivity
import com.satori.ui.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reminder notifications on the "Reminders" channel (IMPORTANCE_HIGH, CATEGORY_REMINDER), one
 * per todo, with the todo id as the notification tag.
 *
 * A reminder shows the todo's text under a title that says when it is due. It is public
 * (VISIBILITY_PUBLIC, the user's decision of 2026-10-10): the whole reminder, text included,
 * shows on the lock screen and on the razr's cover screen, so it can be read without unlocking.
 * The flag is set on each notification, as an app can't set a channel's lock screen visibility.
 * Done and Snooze go to [ReminderReceiver]. Tapping a reminder opens [MainActivity] with the
 * todo's id in [EXTRA_TODO_ID]. There is no full-screen intent: Android 14 keeps those for calls
 * and alarm clocks.
 */
@Singleton
class AndroidReminderNotifications @Inject constructor(
    @ApplicationContext private val context: Context
) : ReminderNotifications {

    companion object {
        /**
         * String extra on the [MainActivity] intent of a tapped reminder: the [Todo.id] of the
         * todo to open. The intent has FLAG_ACTIVITY_NEW_TASK, CLEAR_TOP and SINGLE_TOP, so a
         * running MainActivity receives it in onNewIntent and a new one in onCreate.
         */
        const val EXTRA_TODO_ID = "com.satori.extra.TODO_ID"

        const val CHANNEL_ID = "reminders"

        /** Shared by every reminder; the tag tells them apart. */
        private const val NOTIFICATION_ID = 2001

        private const val DATE_FLAGS =
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL

        private const val TAG = "ReminderNotifications"
    }

    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    /** Creates the channel, or renames it after a change of the app language. */
    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.g_reminders_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.g_reminders_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts the reminder, or returns false without posting while the app may not post
     * notifications (POST_NOTIFICATIONS not granted, or the app's notifications turned off), so
     * the reminder stays to be shown once they are allowed, if that is at most
     * ReminderRules.MAX_LATENESS_HOURS after its time. A Reminders channel the user turned off on
     * its own counts as shown: that is a choice about reminders.
     */
    override fun show(todo: Todo): Boolean {
        createChannel()
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "Notifications are off, so a reminder waits to be shown")
            return false
        }
        manager.notify(todo.id, NOTIFICATION_ID, build(todo))
        return true
    }

    override fun dismiss(todoId: String) {
        manager.cancel(todoId, NOTIFICATION_ID)
    }

    override fun showing(): Set<String> = manager.activeNotifications
        .filter { it.id == NOTIFICATION_ID }
        .mapNotNullTo(HashSet()) { it.tag }

    private fun build(todo: Todo): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // The whole reminder on the lock screen and the cover screen (see the class comment)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // The reminder time, also for one posted late after the phone was off
            .setWhen(todo.remindAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openIntent(todo.id))
            .setAutoCancel(true)
            // Posting it again (a run cut short, an edited todo) does not sound again
            .setOnlyAlertOnce(true)
            .setContentTitle(title(todo))
            .setContentText(todo.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(todo.text))
            .addAction(
                R.drawable.ic_reminder_done,
                context.getString(R.string.g_reminder_action_done),
                actionIntent(ReminderReceiver.ACTION_DONE, todo.id)
            )
            .addAction(
                R.drawable.ic_reminder_snooze,
                context.getString(R.string.g_reminder_action_snooze, ReminderRules.SNOOZE_MINUTES),
                actionIntent(ReminderReceiver.ACTION_SNOOZE, todo.id)
            )
            .build()

    /** "Due 3:00 PM", "Starts Fri, Oct 10, 3:00 PM", or "Reminder" when nothing is due. */
    private fun title(todo: Todo): String {
        val dueAt = todo.dueAt ?: return context.getString(R.string.g_reminder_title)
        val flags = when {
            !todo.dueHasTime -> DATE_FLAGS
            DateUtils.isToday(dueAt) -> DateUtils.FORMAT_SHOW_TIME
            else -> DATE_FLAGS or DateUtils.FORMAT_SHOW_TIME
        }
        val due = DateUtils.formatDateTime(context, dueAt, flags)
        val format = if (todo.kind == TodoKind.APPOINTMENT) R.string.g_reminder_title_starts else R.string.g_reminder_title_due
        return context.getString(format, due)
    }

    /**
     * Shaped like the launcher's intent (ACTION_MAIN, CATEGORY_LAUNCHER, no identifier): when a tap
     * creates Satori's task this intent becomes the task's root, and a later tap on the launcher
     * icon would stack a second MainActivity unless the two match (Task.isSameIntentFilter, as for
     * QuickKeyRequest). The request code keeps one PendingIntent per todo instead.
     */
    private fun openIntent(todoId: String): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(context, MainActivity::class.java)
            .putExtra(EXTRA_TODO_ID, todoId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            todoId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun actionIntent(action: String, todoId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .setIdentifier(todoId)
            .putExtra(EXTRA_TODO_ID, todoId)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
