package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.usecase.llm.ShippedPrompts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The to-dos and ideas leave out what gives back the rules of the to-do prompt (0.7.3), with the
 * app's own prompt and header (res/values): a Malayalam part's item, which passes the check of its
 * words by the Malayalam words copied for it, and an English one that the speaker didn't say. An
 * English item the speaker said in those words stays. The sentences are made up, none is the
 * owner's.
 */
class TodoRuleEchoesTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private var ids = 0

    private class Drops : FoundTodosLog {
        val drops = mutableListOf<Triple<String, Int, FoundTodosLog.Why>>()
        override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) {
            drops += Triple(sessionId, count, why)
        }
    }

    private val drops = Drops()

    private fun search(llm: FakeLlmRepository) =
        ExtractTodosUseCase(llm, ShippedPrompts(), FakeTimeParser(), now = { 0L }, newId = { "todo-${++ids}" }, foundLog = drops)

    private fun at(hour: Int): Long = ZonedDateTime.of(2026, 10, 18, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = time, isComplete = true)

    private fun item(text: String, said: String? = null): String {
        val quote = said?.let { """, "said": "$it"""" }.orEmpty()
        return """{"segment": "S1", "type": "task"$quote, "text": "$text", "when": "none", "capture": "commitment"}"""
    }

    // Made up: "Tomorrow I must buy medicine for the cat."
    private val cat = "നാളെ പൂച്ചയ്ക്ക് മരുന്ന് വാങ്ങണം."

    @Test
    fun `a Malayalam part's item of the prompt's rules is dropped and counted, the real one beside it kept`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = """{"items": [${item("Find the to-dos and the ideas", said = "പൂച്ചയ്ക്ക് മരുന്ന് വാങ്ങണം")}, """ +
                """${item("Buy medicine for the cat", said = "പൂച്ചയ്ക്ക് മരുന്ന് വാങ്ങണം")}]}"""
        )

        val found = search(llm)("s1", listOf(seg("m1", cat, at(9))), zone, "en").todos

        assertEquals(listOf("Buy medicine for the cat"), found.map { it.text })
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.ECHOES_RULES)), drops.drops)
        assertEquals("Dropped 1 found items that repeat the rules of their prompt in s1", FoundTodosLog.line("s1", 1, FoundTodosLog.Why.ECHOES_RULES))
    }

    @Test
    fun `an English item of the rules the speaker didn't say goes, one in the speaker's own words stays`() = runBlocking {
        val said = "I need to find the to-dos and the ideas in the old app before Friday."
        val llm = FakeLlmRepository(answer = """{"items": [${item("Never make up a time")}, ${item("Find the to-dos and the ideas in the old app")}]}""")

        val found = search(llm)("s1", listOf(seg("e1", said, at(9))), zone, "en").todos

        assertEquals(listOf("Find the to-dos and the ideas in the old app"), found.map { it.text })
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.ECHOES_RULES)), drops.drops)
    }

    // The review of 0.7.3.

    // Made up: "Tomorrow the form must be filled in, a list must be made and the names must be changed."
    private val errands = "നാളെ ഫോം പൂരിപ്പിക്കണം, ഒരു ലിസ്റ്റ് ഉണ്ടാക്കണം, പേരുകൾ മാറ്റണം."

    @Test
    fun `a Malayalam part's to-dos of everyday words stay, though the prompts have such words`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = """{"items": [${item("Fill in the form", said = "ഫോം പൂരിപ്പിക്കണം")}, """ +
                """${item("Make a list", said = "ഒരു ലിസ്റ്റ് ഉണ്ടാക്കണം")}, ${item("Change the names on the list", said = "പേരുകൾ മാറ്റണം")}]}"""
        )

        val found = search(llm)("s1", listOf(seg("m1", errands, at(9))), zone, "en").todos

        assertEquals(listOf("Fill in the form", "Make a list", "Change the names on the list"), found.map { it.text })
        assertEquals(emptyList<Triple<String, Int, FoundTodosLog.Why>>(), drops.drops)
    }

    @Test
    fun `an item of the words of the header's fixed text goes, as one of the prompt's does`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = """{"items": [${item("Repeat unless a new time is said", said = "പൂച്ചയ്ക്ക് മരുന്ന് വാങ്ങണം")}, """ +
                """${item("Buy medicine for the cat", said = "പൂച്ചയ്ക്ക് മരുന്ന് വാങ്ങണം")}]}"""
        )

        val found = search(llm)("s1", listOf(seg("m1", cat, at(9))), zone, "en").todos

        assertEquals(listOf("Buy medicine for the cat"), found.map { it.text })
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.ECHOES_RULES)), drops.drops)
    }
}
