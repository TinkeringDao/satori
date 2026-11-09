package com.satori.ui

import android.content.Intent
import com.satori.presentation.main.ActiveRecorder
import com.satori.service.LlmProcessingNotificationManager
import com.satori.service.reminders.AndroidReminderNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tapped reminder or "Your notes are ready" opens what it is about, its to-do or its recording's
 * page, unless a recorder starts, records or saves, which stays in front.
 */
class NotificationTapsTest {

    @Test
    fun `a tap opens what it is about unless a recorder is busy, one activity at a time`() {
        val table = mapOf(
            null to true,
            ActiveRecorder.Activity.IDLE to true,
            ActiveRecorder.Activity.STARTING to false,
            ActiveRecorder.Activity.RECORDING to false,
            ActiveRecorder.Activity.SAVING to false
        )
        assertEquals(ActiveRecorder.Activity.entries.toSet(), table.keys.filterNotNull().toSet())
        for ((activity, opens) in table) {
            assertEquals("$activity", opens, NotificationTaps.mayOpen(activity))
        }
    }

    @Test
    fun `a tapped notes-ready carries its recording's id, and the tap opens that recording's page`() {
        val tap = NotificationTaps.notesReadyTap("rec-7")

        assertEquals(mapOf(LlmProcessingNotificationManager.EXTRA_NOTES_SESSION_ID to "rec-7"), tap.extras)
        assertEquals(listOf(AppLaunch.OpenRecording("rec-7")), NotificationTaps.launchesOf { tap.extras[it] })
        // Shaped like the launcher's, and delivered to a running MainActivity without a second one.
        assertEquals(Intent.ACTION_MAIN, tap.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), tap.categories)
        for (flag in listOf(Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_CLEAR_TOP, Intent.FLAG_ACTIVITY_SINGLE_TOP)) {
            assertTrue("flag $flag", tap.flags and flag == flag)
        }
    }

    @Test
    fun `a tapped reminder opens its to-do, and an intent without an id, or with a blank one, asks for nothing`() {
        val reminder = mapOf(AndroidReminderNotifications.EXTRA_TODO_ID to "todo-3")

        assertEquals(listOf(AppLaunch.OpenTodo("todo-3")), NotificationTaps.launchesOf { reminder[it] })
        assertEquals(emptyList<AppLaunch>(), NotificationTaps.launchesOf { null })
        assertEquals(emptyList<AppLaunch>(), NotificationTaps.launchesOf { " " })
    }
}
