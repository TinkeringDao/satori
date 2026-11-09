package com.satori.domain.usecase.todo

import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCertainty
import com.satori.domain.model.TodoKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TodoEditTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    // Wednesday 14 October 2026, 7:00 in Berlin.
    private val now = millis(14, 7, 0)

    private fun millis(day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(berlin).toInstant().toEpochMilli()

    private fun todo(
        dueAt: Long? = null,
        hasTime: Boolean = false,
        kind: TodoKind = TodoKind.TASK,
        remindAt: Long? = null,
        certainty: TodoCertainty = TodoCertainty.CONFIRMED
    ) = Todo(
        id = "t1",
        sessionId = "s1",
        text = "Call the bank",
        kind = kind,
        certainty = certainty,
        dueAt = dueAt,
        dueHasTime = hasTime,
        timePhrase = dueAt?.let { "Friday at 9" },
        remindAt = remindAt,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun edit(original: Todo, text: String = original.text, date: LocalDate?, time: LocalTime?) =
        TodoEdit.edited(original, text, date, time, now, berlin)

    private val friday = LocalDate.of(2026, 10, 16)

    @Test
    fun `the sheet starts from the due day and time of the to-do`() {
        val timed = TodoEdit.dueOf(todo(millis(16, 9, 30), hasTime = true), berlin)
        val dayOnly = TodoEdit.dueOf(todo(millis(16, 9, 0), hasTime = false), berlin)
        val none = TodoEdit.dueOf(todo(), berlin)

        assertEquals(TodoDueDraft(friday, LocalTime.of(9, 30)), timed)
        assertEquals(TodoDueDraft(friday, null), dayOnly)
        assertEquals(TodoDueDraft(null, null), none)
    }

    @Test
    fun `changing only the text keeps the due time and the reminder, a snooze included`() {
        val snoozed = todo(millis(16, 9, 0), hasTime = true, remindAt = millis(16, 9, 10))

        val saved = edit(snoozed, text = "  Call the bank today  ", date = friday, time = LocalTime.of(9, 0))

        assertEquals("Call the bank today", saved.text)
        assertEquals(snoozed.dueAt, saved.dueAt)
        assertEquals(millis(16, 9, 10), saved.remindAt)
        assertEquals("Friday at 9", saved.timePhrase)
        assertEquals(now, saved.updatedAt)
    }

    @Test
    fun `a blank text keeps the old one`() {
        assertEquals("Call the bank", edit(todo(), text = "  ", date = null, time = null).text)
    }

    @Test
    fun `a day only for an unchanged day keeps its reminder`() {
        val dayOnly = todo(millis(16, 9, 0), hasTime = false, remindAt = millis(16, 9, 0))

        val saved = edit(dayOnly, date = friday, time = null)

        assertEquals(millis(16, 9, 0), saved.remindAt)
        assertFalse(saved.dueHasTime)
    }

    @Test
    fun `a new day gives a new reminder at nine`() {
        val saved = edit(todo(), date = friday, time = null)

        assertEquals(millis(16, 9, 0), saved.dueAt)
        assertFalse(saved.dueHasTime)
        assertEquals(millis(16, 9, 0), saved.remindAt)
    }

    @Test
    fun `adding a clock time moves the reminder to it`() {
        val dayOnly = todo(millis(16, 9, 0), hasTime = false, remindAt = millis(16, 9, 0))

        val saved = edit(dayOnly, date = friday, time = LocalTime.of(14, 30))

        assertEquals(millis(16, 14, 30), saved.dueAt)
        assertTrue(saved.dueHasTime)
        assertEquals(millis(16, 14, 30), saved.remindAt)
    }

    @Test
    fun `an appointment with a clock time reminds an hour before`() {
        val appointment = todo(kind = TodoKind.APPOINTMENT)

        val saved = edit(appointment, date = friday, time = LocalTime.of(10, 0))

        assertEquals(TodoKind.APPOINTMENT, saved.kind)
        assertEquals(millis(16, 9, 0), saved.remindAt)
    }

    @Test
    fun `a changed due time is firm and no longer the words it was taken from`() {
        val tentative = todo(millis(16, 9, 0), hasTime = true, certainty = TodoCertainty.TENTATIVE)

        val saved = edit(tentative, date = friday, time = LocalTime.of(11, 0))

        assertEquals(TodoCertainty.CONFIRMED, saved.certainty)
        assertNull(saved.timePhrase)
    }

    @Test
    fun `an unchanged due time keeps the certainty`() {
        val tentative = todo(millis(16, 9, 0), hasTime = true, certainty = TodoCertainty.TENTATIVE)

        assertEquals(TodoCertainty.TENTATIVE, edit(tentative, date = friday, time = LocalTime.of(9, 0)).certainty)
    }

    @Test
    fun `removing the day removes the due time and the reminder, and an appointment becomes a task`() {
        val appointment = todo(millis(16, 10, 0), hasTime = true, kind = TodoKind.APPOINTMENT, remindAt = millis(16, 9, 0))

        val saved = edit(appointment, date = null, time = LocalTime.of(10, 0))

        assertNull(saved.dueAt)
        assertFalse(saved.dueHasTime)
        assertNull(saved.remindAt)
        assertEquals(TodoKind.TASK, saved.kind)
    }

    @Test
    fun `a due time that has passed sets no reminder`() {
        val saved = edit(todo(), date = LocalDate.of(2026, 10, 14), time = LocalTime.of(6, 0))

        assertEquals(millis(14, 6, 0), saved.dueAt)
        assertNull(saved.remindAt)
    }

    @Test
    fun `everything else about the to-do stays as it was`() {
        val original = todo(millis(16, 9, 0), hasTime = true, remindAt = millis(16, 9, 0))

        val saved = edit(original, date = friday, time = LocalTime.of(9, 0))

        assertEquals(original.copy(updatedAt = now), saved)
    }
}
