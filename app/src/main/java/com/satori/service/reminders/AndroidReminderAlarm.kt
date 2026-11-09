package com.satori.service.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.satori.domain.usecase.reminder.ReminderAlarm
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one reminder alarm: an exact RTC_WAKEUP alarm, allowed in Doze, that wakes
 * [ReminderReceiver] when the next reminder is due. Arming it again replaces the previous one,
 * as both use the same PendingIntent.
 *
 * USE_EXACT_ALARM grants exact alarms from Android 13 and can't be revoked; Android 12 uses
 * SCHEDULE_EXACT_ALARM, which the user can revoke. Without it the alarm is inexact and may ring
 * some minutes late in Doze.
 */
@Singleton
class AndroidReminderAlarm @Inject constructor(
    @ApplicationContext private val context: Context
) : ReminderAlarm {

    private companion object {
        const val REQUEST_CODE = 0
        const val TAG = "ReminderAlarm"
    }

    private val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun arm(atMs: Long) {
        val operation = operation(PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
                return
            } catch (e: SecurityException) {
                // Revoked between the check and the call
                Log.w(TAG, "Exact alarm refused, arming an inexact one", e)
            }
        } else {
            Log.w(TAG, "Exact alarms not allowed, arming an inexact one")
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, operation)
    }

    override fun cancel() {
        val operation = operation(PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun operation(flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_ALARM),
        PendingIntent.FLAG_IMMUTABLE or flag
    )
}
