package com.satori.service.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Arms the reminder alarm again whenever the system may have dropped or moved it: after a
 * reboot (alarms don't survive one), an app update, a change of the clock or time zone, and when
 * the exact-alarm permission is granted (Android 12; USE_EXACT_ALARM is never revoked). It also
 * posts reminders that came due meanwhile (see [AndroidReminderScheduler.reschedule]).
 *
 * After a reboot or an update it also resumes the finishing of recordings that the process before
 * left unfinished (FinishingQueue.resume), as [FinishingResume] says.
 *
 * Not exported: these broadcasts come from the system, which reaches unexported receivers.
 */
class ReminderRescheduleReceiver : BroadcastReceiver() {

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val reminders = context.reminderScheduler()
        doInBackground { reminders.reschedule() }
        val resume = FinishingResume.of(intent.action) ?: return
        if (resume.schedulesWake) context.finishingWake().schedule()
        context.finishingQueue().resume(mayStartForeground = resume.mayStartForeground)
    }
}

/**
 * How a broadcast resumes the finishing of recordings.
 *
 * @property mayStartForeground What FinishingQueue.resume gets: whether the queue may start the
 *   AI's foreground service
 * @property schedulesWake Whether the receiver schedules FinishingWakeJob itself, before the
 *   broadcast ends
 */
internal enum class FinishingResume(val mayStartForeground: Boolean, val schedulesWake: Boolean) {

    /**
     * After a reboot. Android 15 bars dataSync services started from BOOT_COMPLETED, so the queue
     * goes on under its JobScheduler job (FinishingWakeJob). The job is scheduled here, while the
     * broadcast keeps the process up: the queue's resume writes and schedules from its own
     * coroutine, and the process that exists only for this broadcast may be frozen or killed as
     * soon as it ends. The job then resumes the queue in whatever process runs it, and ends at
     * once when nothing is left to finish.
     */
    BOOT(mayStartForeground = false, schedulesWake = true),

    /** After an update, which lets the app start a foreground service: the queue takes the AI's. */
    UPDATE(mayStartForeground = true, schedulesWake = false);

    companion object {

        /** How the broadcast [action] resumes the queue; null for one that doesn't. */
        fun of(action: String?): FinishingResume? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> UPDATE
            else -> null
        }
    }
}
