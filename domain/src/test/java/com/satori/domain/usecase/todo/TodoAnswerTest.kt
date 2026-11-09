package com.satori.domain.usecase.todo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoAnswerTest {

    private val bank = TodoAnswer.Item(segments = listOf("S1"), text = "Call the bank", time = "T1", kind = "task", capture = "commitment")
    private val bankJson = """{"segment": "S1", "type": "task", "text": "Call the bank", "when": "T1", "capture": "commitment"}"""
    private val option = TodoAnswer.Item(segments = listOf("S4"), text = "Option to sort by place", time = null, kind = "idea", capture = null)
    private val optionJson = """{"segment": "S4", "type": "idea", "text": "Option to sort by place"}"""

    @Test
    fun `reads the to-dos and ideas of a well-formed answer`() {
        assertEquals(listOf(bank, option), TodoAnswer.parse("""{"items": [$bankJson, $optionJson]}"""))
        assertEquals(emptyList<TodoAnswer.Item>(), TodoAnswer.parse("""{"items": []}"""))
        assertFalse(bank.idea)
        assertTrue(option.idea)
        assertTrue(bank.copy(kind = "Idea").idea)
    }

    @Test
    fun `an answer of an earlier build, with todos, kind and certainty, still reads`() {
        val old = """{"todos": [{"segment": "S1", "text": "Call the bank", "when": "T1", "kind": "task", "capture": "commitment", "certainty": "tentative"}]}"""
        assertEquals(listOf(bank), TodoAnswer.parse(old))
    }

    @Test
    fun `finds the JSON inside code fences, a thinking block or chatter`() {
        assertEquals(listOf(bank), TodoAnswer.parse("```json\n{\"items\": [$bankJson]}\n```"))
        assertEquals(listOf(bank), TodoAnswer.parse("<think>The user wants todos.</think>{\"items\": [$bankJson]}"))
        assertEquals(listOf(bank), TodoAnswer.parse("Here are the items: {\"items\": [$bankJson]} Hope this helps!"))
        assertEquals(listOf(bank), TodoAnswer.parse("Done [as asked]: {\"items\": [$bankJson]}"))
    }

    @Test
    fun `keeps the complete items of an answer that was cut off`() {
        val cut = """{"items": [$bankJson, $optionJson, {"segment": "S2", "type": "task", "text": "Book the ro"""
        assertEquals(listOf(bank, option), TodoAnswer.parse(cut))
    }

    @Test
    fun `accepts a bare list, number ids and a list of segments`() {
        val answer = """[{"segment": 2, "text": "Pay rent", "when": "none"}, {"segment": ["s3", "S4"], "text": "Ring Bo"}]"""
        assertEquals(
            listOf(
                TodoAnswer.Item(listOf("2"), "Pay rent", "none", null, null),
                TodoAnswer.Item(listOf("s3", "S4"), "Ring Bo", null, null, null)
            ),
            TodoAnswer.parse(answer)
        )
    }

    @Test
    fun `an answer without JSON gives nothing`() {
        assertEquals(emptyList<TodoAnswer.Item>(), TodoAnswer.parse("I could not find any todos."))
        assertEquals(emptyList<TodoAnswer.Item>(), TodoAnswer.parse(""))
    }

    @Test
    fun `the words an item copied from its segment are read, said or quote`() {
        val items = TodoAnswer.parse(
            """{"items": [{"segment": "S1", "type": "task", "said": "ഡോക്ടറെ വിളിക്കണം", "text": "Call the doctor", "when": "none", "capture": "commitment"}, """ +
                """{"segment": "S2", "type": "idea", "quote": "die Uhr größer", "text": "Bigger clock"}, {"segment": "S3", "type": "task", "text": "Pay", "when": "none", "capture": "explicit"}]}"""
        )

        assertEquals(listOf("ഡോക്ടറെ വിളിക്കണം", "die Uhr größer", null), items.map { it.said })
    }
}
