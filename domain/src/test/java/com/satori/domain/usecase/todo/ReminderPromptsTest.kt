package com.satori.domain.usecase.todo

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPromptsTest {

    private fun decide(
        hasReminders: Boolean = true,
        notificationsOn: Boolean = false,
        canAsk: Boolean = true,
        alreadyAsked: Boolean = false
    ) = ReminderPrompts.decide(hasReminders, notificationsOn, canAsk, alreadyAsked)

    @Test
    fun `nothing is said while no reminder is waiting`() {
        assertEquals(ReminderPrompt.None, decide(hasReminders = false))
        assertEquals(ReminderPrompt.None, decide(hasReminders = false, canAsk = false, alreadyAsked = true))
    }

    @Test
    fun `nothing is said while notifications are on`() {
        assertEquals(ReminderPrompt.None, decide(notificationsOn = true))
    }

    @Test
    fun `the first time reminders are waiting and notifications are off, it asks`() {
        assertEquals(ReminderPrompt.Ask, decide())
    }

    @Test
    fun `it asks only once`() {
        assertEquals(ReminderPrompt.TurnedOff, decide(alreadyAsked = true))
    }

    @Test
    fun `where Android can't show its dialog, it only says reminders are off`() {
        assertEquals(ReminderPrompt.TurnedOff, decide(canAsk = false))
    }
}
