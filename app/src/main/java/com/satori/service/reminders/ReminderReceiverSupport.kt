package com.satori.service.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.satori.domain.finish.FinishingQueue
import com.satori.domain.finish.FinishingWake
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Hands the reminder receivers the app's [AndroidReminderScheduler]. An entry point instead of
 * @AndroidEntryPoint field injection, so the receivers don't depend on Hilt rewriting their
 * abstract onReceive.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun reminderScheduler(): AndroidReminderScheduler
}

internal fun Context.reminderScheduler(): AndroidReminderScheduler =
    EntryPointAccessors.fromApplication(applicationContext, ReminderEntryPoint::class.java).reminderScheduler()

/**
 * Hands [ReminderRescheduleReceiver] the app's [FinishingQueue], which a reboot or an update
 * resumes, and its [FinishingWake], the same way as [ReminderEntryPoint].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FinishingEntryPoint {
    fun finishingQueue(): FinishingQueue
    fun finishingWake(): FinishingWake
}

internal fun Context.finishingQueue(): FinishingQueue =
    EntryPointAccessors.fromApplication(applicationContext, FinishingEntryPoint::class.java).finishingQueue()

internal fun Context.finishingWake(): FinishingWake =
    EntryPointAccessors.fromApplication(applicationContext, FinishingEntryPoint::class.java).finishingWake()

private const val TAG = "Reminders"

/**
 * Under goAsync a receiver must finish within 10 s of a foreground broadcast (60 s of a
 * background one, such as an alarm); reminder work takes milliseconds.
 */
private const val WORK_TIMEOUT_MS = 9_000L

/** Outlives onReceive, so the work can finish after it returns. */
private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [work] after onReceive returns, keeping the broadcast alive (and, for the alarm, the
 * system's wake lock held) until it ends. A failure is logged, not thrown: the next reschedule
 * puts the reminders right again.
 */
internal fun BroadcastReceiver.doInBackground(work: suspend () -> Unit) {
    val pending = goAsync()
    receiverScope.launch {
        try {
            withTimeout(WORK_TIMEOUT_MS) { work() }
        } catch (e: Exception) {
            Log.e(TAG, "Reminder work failed", e)
        } finally {
            pending.finish()
        }
    }
}
