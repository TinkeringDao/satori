package com.satori.ui

import android.content.Intent
import com.satori.presentation.main.ActiveRecorder
import com.satori.service.LlmProcessingNotificationManager
import com.satori.service.reminders.AndroidReminderNotifications

/**
 * What a tap on one of Satori's notifications may do with the screen ([AppLaunch]): a reminder
 * opens its to-do on home, "Your notes are ready" its recording's page. Neither does while a
 * recorder starts, records or saves: that recorder stays in front, and the tap only brings Satori
 * up. An open recorder that is idle gives way.
 *
 * What the tapped intent holds is made here too, and read back here ([notesReadyTap],
 * [launchesOf]), so that the recording's id going in and the page it opens are tested without
 * Android's Intent: LlmProcessingNotificationManager builds the intent from [notesReadyTap], and
 * MainActivity reads it with [launchesOf].
 */
internal object NotificationTaps {

    /** Whether a tap may take the screen from the recorder doing [activity], or from no recorder (null). */
    fun mayOpen(activity: ActiveRecorder.Activity?): Boolean = when (activity) {
        ActiveRecorder.Activity.STARTING, ActiveRecorder.Activity.RECORDING, ActiveRecorder.Activity.SAVING -> false
        ActiveRecorder.Activity.IDLE, null -> true
    }

    /** What a notification's tap intent is made of, as [android.content.Intent] takes it. */
    data class TapIntent(val action: String, val categories: Set<String>, val flags: Int, val extras: Map<String, String>)

    /**
     * The intent of a tapped "Your notes are ready" for the recording [sessionId]: shaped like the
     * launcher's (ACTION_MAIN, CATEGORY_LAUNCHER), as a tapped reminder's is, so a task it creates
     * doesn't stack a second MainActivity under a later tap on the icon; NEW_TASK, CLEAR_TOP and
     * SINGLE_TOP, so a running MainActivity gets it in onNewIntent; and the recording's id
     * ([LlmProcessingNotificationManager.EXTRA_NOTES_SESSION_ID]).
     */
    fun notesReadyTap(sessionId: String): TapIntent = TapIntent(
        action = Intent.ACTION_MAIN,
        categories = setOf(Intent.CATEGORY_LAUNCHER),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        extras = mapOf(LlmProcessingNotificationManager.EXTRA_NOTES_SESSION_ID to sessionId)
    )

    /**
     * What a tapped notification's intent asks for, read from its string extras by [extra]: a
     * reminder's to-do ([AndroidReminderNotifications.EXTRA_TODO_ID]), a "Your notes are ready"'s
     * recording ([LlmProcessingNotificationManager.EXTRA_NOTES_SESSION_ID]), or nothing. A blank id
     * asks for nothing.
     */
    fun launchesOf(extra: (String) -> String?): List<AppLaunch> = listOfNotNull(
        extra(AndroidReminderNotifications.EXTRA_TODO_ID)?.takeIf { it.isNotBlank() }?.let { AppLaunch.OpenTodo(it) },
        extra(LlmProcessingNotificationManager.EXTRA_NOTES_SESSION_ID)?.takeIf { it.isNotBlank() }?.let { AppLaunch.OpenRecording(it) }
    )
}
