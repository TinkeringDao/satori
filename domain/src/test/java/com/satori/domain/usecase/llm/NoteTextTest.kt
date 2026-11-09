package com.satori.domain.usecase.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTextTest {

    @Test
    fun `a title that differs from the recording's name is shown`() {
        assertEquals("Q4 budget status", NoteText.visibleTitle("Q4 budget status", "Budget review with Anna"))
    }

    @Test
    fun `a title is trimmed`() {
        assertEquals("Next steps", NoteText.visibleTitle("  Next steps\n", null))
    }

    @Test
    fun `no title or a blank one shows nothing`() {
        assertNull(NoteText.visibleTitle(null, "Heating repair"))
        assertNull(NoteText.visibleTitle("", "Heating repair"))
        assertNull(NoteText.visibleTitle("   ", null))
    }

    @Test
    fun `a title that repeats the recording's name shows nothing`() {
        assertNull(NoteText.visibleTitle("Heating repair and rent", "Heating repair and rent"))
        assertNull(NoteText.visibleTitle(" heating REPAIR and rent ", "Heating repair and rent"))
    }

    @Test
    fun `a title is not compared with a name the recording does not have`() {
        assertEquals("Heating repair and rent", NoteText.visibleTitle("Heating repair and rent", null))
    }

    @Test
    fun `items the summary already lists are left out`() {
        val summary = listOf("Plan the user interviews", "Assign owners to open roadmap items")
        val part = listOf("plan the user interviews ", "Book the venue")
        assertEquals(listOf("Book the venue"), NoteText.withoutRepeats(part, summary))
    }

    @Test
    fun `a part whose items are all in the summary keeps none`() {
        assertEquals(emptyList<String>(), NoteText.withoutRepeats(listOf("Send the list"), listOf("Send the list")))
    }

    @Test
    fun `nothing shown yet keeps every item once`() {
        assertEquals(
            listOf("Call Anna", "Book the venue"),
            NoteText.withoutRepeats(listOf("Call Anna", "Book the venue", "call anna"), emptyList())
        )
    }

    @Test
    fun `items keep their order and their spelling`() {
        assertEquals(
            listOf("Zeta first", "Alpha second"),
            NoteText.withoutRepeats(listOf("Zeta first", "Alpha second"), listOf("something else"))
        )
    }

    @Test
    fun `action items are read from a list of strings`() {
        assertEquals(
            listOf("Call Anna", "Book the venue, then pay"),
            NoteText.actionItems("[\"Call Anna\", \"Book the venue, then pay\"]")
        )
    }

    @Test
    fun `older action items are objects with a description or a task`() {
        val stored = "[{\"description\": \"Send the list\", \"priority\": \"high\"}, {\"task\": \"Call Anna\"}, " +
            "{\"description\": \" \", \"task\": \"Book the venue\"}]"
        assertEquals(listOf("Send the list", "Call Anna", "Book the venue"), NoteText.actionItems(stored))
    }

    @Test
    fun `action items are trimmed and empty entries dropped`() {
        assertEquals(
            listOf("Call Anna", "Book the venue"),
            NoteText.actionItems("[\"  Call Anna \", \"\", null, {\"priority\": \"low\"}, \"Book the venue\"]")
        )
    }

    @Test
    fun `no action items or ones that can't be read give an empty list`() {
        assertEquals(emptyList<String>(), NoteText.actionItems(null))
        assertEquals(emptyList<String>(), NoteText.actionItems(" "))
        assertEquals(emptyList<String>(), NoteText.actionItems("[]"))
        assertEquals(emptyList<String>(), NoteText.actionItems("not json"))
        assertEquals(emptyList<String>(), NoteText.actionItems("[\"cut off"))
        assertEquals(emptyList<String>(), NoteText.actionItems("{\"description\": \"Not a list\"}"))
    }
}
