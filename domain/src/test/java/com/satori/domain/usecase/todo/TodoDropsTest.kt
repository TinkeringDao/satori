package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.time.RuleBasedTimeParser
import com.satori.domain.usecase.llm.ShippedPrompts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Every found item the search drops is counted under its reason (0.7.2): on 2026-10-16 an answer of
 * four items whose text was only a time phrase went without a line, and with no time picked the
 * same items read as "not in their segment" and one became a suggestion. Real time parser, the
 * app's own prompts; the segments and answers are made up, of the en1 feed's shape.
 */
class TodoDropsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(minute: Int) = ZonedDateTime.of(2026, 10, 16, 14, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, minute: Int) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = at(minute), isComplete = true)

    private val english = listOf(
        seg("a", "I have to call the bank tomorrow at 10 about the loan.", 35),
        seg("b", "The dentist appointment is on Friday at 3 pm.", 36),
        seg("c", "I need to buy a birthday present for Anna this weekend.", 37),
        seg("d", "And the rent has to be paid by the end of the month.", 38),
        seg("e", "The weather was lovely at the lake all afternoon.", 39)
    )
    private val german = listOf(
        seg("a", "Ich muss morgen um 10 die Bank anrufen.", 35),
        seg("b", "Am Freitag um 15 Uhr habe ich den Zahnarzttermin.", 36),
        seg("c", "Bis Ende des Monats muss die Miete bezahlt sein.", 37)
    )

    /** Answers each call with one item per time the prompt lists, its text that time's phrase alone. */
    private class TimesOnly(private val picksTime: Boolean, private val extra: Int = 0) : LlmRepository by FakeLlmRepository() {
        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            var segment = 0
            val items = mutableListOf<String>()
            for (line in text.lines()) {
                Regex("""^S(\d+) """).find(line)?.let { segment = it.groupValues[1].toInt() }
                Regex("""^\s+(T\d+) = '([^']+)'""").find(line)?.let { m ->
                    val whenId = if (picksTime) m.groupValues[1] else "none"
                    items += """{"segment": "S$segment", "type": "task", "text": "${m.groupValues[2]}", "when": "$whenId", "capture": "commitment"}"""
                }
            }
            repeat(extra) { items += items.last() }
            emit("""{"items": [${items.joinToString(", ")}]}""")
        }
    }

    private class Drops : FoundTodosLog {
        val heard = mutableListOf<String>()
        override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) {
            heard += FoundTodosLog.line(sessionId, count, why)
        }
    }

    private val drops = Drops()

    private fun find(llm: LlmRepository, segments: List<TranscriptionSegment>, language: String = "en", existing: List<Todo> = emptyList()) =
        runBlocking {
            ExtractTodosUseCase(llm, ShippedPrompts(), RuleBasedTimeParser(), now = { 0L }, foundLog = drops)(
                "s1", segments, zone, language, existingTodos = existing, spotsOutright = false
            )
        }

    @Test
    fun `items that are only the time they picked all go, and say so`() {
        val found = find(TimesOnly(picksTime = true), english)

        assertTrue(found.todos.isEmpty())
        assertTrue(found.answered)
        assertEquals(listOf("Dropped 4 found items that are only a time in s1"), drops.heard)
    }

    @Test
    fun `items that are only a time but picked none go for that reason too, and none is kept as a suggestion`() {
        val found = find(TimesOnly(picksTime = false), english)

        assertTrue(found.todos.map { it.text }.toString(), found.todos.isEmpty())
        assertEquals(listOf("Dropped 4 found items that are only a time in s1"), drops.heard)
    }

    @Test
    fun `German items that are only a time go the same way`() {
        val found = find(TimesOnly(picksTime = true), german, language = "de")

        assertTrue(found.todos.isEmpty())
        assertEquals(1, drops.heard.size)
        assertTrue(drops.heard.single(), drops.heard.single().endsWith("found items that are only a time in s1"))
    }

    @Test
    fun `items past the answer's limit are counted`() {
        // Four times listed, and seven more copies: eleven items, one past the ten an answer is read for.
        find(TimesOnly(picksTime = true, extra = 7), english)

        assertEquals(listOf("Dropped 1 found items over the answer's limit in s1", "Dropped 10 found items that are only a time in s1"), drops.heard)
    }

    private fun item(segment: String, text: String, whenId: String = "none", capture: String = "commitment") =
        """{"segment": "$segment", "type": "task", "text": "$text", "when": "$whenId", "capture": "$capture"}"""

    @Test
    fun `each other reason an item goes for is counted once, in the order they are checked`() {
        // A question keeps no "?" once its text is cleaned, so TodoGateTest has that reason.
        val answer = """{"items": [""" + listOf(
            item("S9", "Call the bank about the loan"),
            item("S1", " "),
            item("S1", "Bank"),
            item("S1", "Call the bank about the"),
            item("S5", "The weather was lovely at the lake all afternoon", capture = "inferred"),
            item("S3", "Buy a birthday present for Anna"),
            item("S1", "Call the bank about the loan")
        ).joinToString(", ") + "]}"
        // The recording's own to-do from the same line: the search found it before.
        val own = Todo(
            id = "t0", sessionId = "s1", text = "Buy a birthday present for Anna", status = TodoStatus.OPEN,
            sourceSegmentIds = listOf("c"), createdAt = 0L, updatedAt = 0L
        )

        val found = find(ScriptedLlmRepository(listOf(answer)), english, existing = listOf(own))

        assertEquals(listOf("Call the bank about the loan"), found.todos.map { it.text })
        assertEquals(
            listOf(
                "Dropped 1 found items that cite no segment in s1",
                "Dropped 1 found items without a text in s1",
                "Dropped 1 found items too short to name a thing to do in s1",
                "Dropped 1 found items cut off mid-phrase in s1",
                "Dropped 1 found items that copy their sentence without a cue in s1",
                "Dropped 1 found items already noted in s1"
            ),
            drops.heard
        )
    }

    @Test
    fun `a Malayalam part's items without a segment or a text are counted as well`() {
        // Made up: "Tomorrow at ten I have to call the bank."
        val malayalam = listOf(seg("a", "നാളെ പത്തു മണിക്ക് എനിക്ക് ബാങ്കിൽ വിളിക്കണം.", 35))
        val answer = """{"items": [{"segment": "S4", "type": "task", "text": "Call the bank", "when": "none", "capture": "commitment", "said": "ബാങ്കിൽ വിളിക്കണം"}, """ +
            """{"segment": "S1", "type": "task", "text": "", "when": "none", "capture": "commitment", "said": "ബാങ്കിൽ വിളിക്കണം"}]}"""

        val found = find(ScriptedLlmRepository(listOf(answer)), malayalam)

        assertTrue(found.todos.isEmpty())
        assertEquals(listOf("Dropped 1 found items that cite no segment in s1", "Dropped 1 found items without a text in s1"), drops.heard)
    }

    @Test
    fun `every reason's line is the field log's form`() {
        assertEquals("Dropped 4 found items that are only a time in s1", FoundTodosLog.line("s1", 4, FoundTodosLog.Why.ONLY_A_TIME))
        assertEquals(
            "Dropped 1 found items that are appointments without a usable time in s1",
            FoundTodosLog.line("s1", 1, FoundTodosLog.Why.NO_USABLE_TIME)
        )
    }
}
