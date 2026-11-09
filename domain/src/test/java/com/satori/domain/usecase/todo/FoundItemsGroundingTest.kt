package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.time.RuleBasedTimeParser
import com.satori.domain.time.TimeExpressionParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The search keeps only found items said in the segments they cite (brief item 10): on 2026-10-14
 * an English meeting got four items of other recordings, each with a time of the meeting, through
 * the noted list of its header (here the prompt's own example and three items of their shape,
 * paraphrased). They are dropped before the rule that keeps a repeat for a new time, and the noted
 * list shows only what can be said again in the part; a real repeat with a new time is still kept.
 * A Malayalam recording's English items are checked by the words the model copied from their
 * segments. A heading and a greeting are no items, but a reminder or an appointment that starts
 * with an ordinal is; a reminder the spotter made plain ("einzukaufen": "Einkaufen") is in its
 * segment, and a word in capitals is no hesitation.
 */
class FoundItemsGroundingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private var ids = 0

    /** The drops the search told the field log of. */
    private class Drops : FoundTodosLog {
        val drops = mutableListOf<Triple<String, Int, FoundTodosLog.Why>>()
        override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) {
            drops += Triple(sessionId, count, why)
        }
    }

    private val drops = Drops()

    private fun search(llm: FakeLlmRepository, parser: TimeExpressionParser = FakeTimeParser()) =
        ExtractTodosUseCase(llm, TodoResources(), parser, now = { 0L }, newId = { "todo-${++ids}" }, foundLog = drops)

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 14): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long, session: String = "s1") =
        TranscriptionSegment(id = id, sessionId = session, text = text, timestamp = time, isComplete = true)

    private fun item(segment: String, text: String, type: String = "task", whenId: String = "none", capture: String = "explicit", said: String? = null): String {
        val quote = said?.let { """, "said": "$it"""" }.orEmpty()
        return if (type == "idea") """{"segment": "$segment", "type": "idea"$quote, "text": "$text"}"""
        else """{"segment": "$segment", "type": "$type"$quote, "text": "$text", "when": "$whenId", "capture": "$capture"}"""
    }

    private fun items(vararg items: String) = """{"items": [${items.joinToString(", ")}]}"""

    /** A pending item of another recording. */
    private fun known(text: String, status: TodoStatus = TodoStatus.OPEN) = Todo(
        id = "known-$text", sessionId = "other", text = text, status = status,
        sourceSegmentIds = listOf("elsewhere"), createdAt = 0L, updatedAt = 0L
    )

    // The synthetic English meeting feed (en-meeting.wav), as the phone transcribed it on 2026-10-14.
    private val meeting = listOf(
        seg(
            "a", "Good morning everyone. Let's get started with the weekly planning meeting. First, the status of the " +
                "website relaunch. The design is finished and the developers started on the new booking page on Monday. " +
                "The main risk is the payment integration. Because the provider changed their interface last month. " +
                "Tom will check the new documentation and report back by Wednesday.", at(18, 7, 30)
        ),
        seg(
            "b", "Second, the budget. We spent about 60% of the quarterly budget so far, which is on track. Marketing " +
                "asked an extra 2,000 euros for the trade fair in November. We agreed to approve it if the travel costs " +
                "stay below the plan. Maria will send the updated numbers to finance by Thursday. Third, hiring.", at(18, 7, 50)
        ),
        seg(
            "c", "We have two open positions for backend developers. The first round of interviews is next week. Please " +
                "send me your availability for Tuesday and Wednesday afternoon. I will book the rooms once I have " +
                "everyone's answers. Fourth, the customer feedback from the last survey. Most customers like the new " +
                "app, but many complain that the login takes too long.", at(18, 8, 10)
        ),
        seg(
            "d", "The team will look into it and propose a fix in the next sprint. Let's also add a short question about " +
                "the login to the next survey. Finally, a reminder that the office is closed on Friday for the team " +
                "event. We meet at the main entrance at 9.30. Remind me to order the lunch for the team event tomorrow " +
                "morning. That's all for today. Thanks everyone.", at(18, 8, 32)
        ),
        seg("e", "And see you next week.", at(18, 8, 33))
    )

    private val copies = listOf("Bake something for six weeks", "Weigh how exact", "The places were and to visit them", "Make the clock bigger")

    @Test
    fun `the four copies of other recordings' items are dropped, whatever time the meeting gave them, and the log counts them`() = runBlocking {
        val parser = FakeTimeParser().at("tomorrow morning", LocalDateTime.of(2026, 10, 15, 9, 0))
        val llm = FakeLlmRepository(
            answer = items(
                *copies.map { item("S4", it, whenId = "T1") }.toTypedArray(),
                item("S4", "Order the lunch for the team event", whenId = "T1"),
                item("S3", "Book the rooms once I have everyone's answers", capture = "commitment")
            )
        )
        val existing = listOf(known(copies[0]), known(copies[1]), known(copies[2], TodoStatus.IDEA), known(copies[3], TodoStatus.IDEA))

        val found = search(llm, parser)("s1", meeting, zone, "en", existingTodos = existing).todos.map { it.text }

        assertTrue(found.toString(), copies.none { it in found })
        assertTrue(found.toString(), "Order the lunch for the team event" in found)
        assertTrue(found.toString(), found.any { it.startsWith("Book the rooms") })
        assertEquals(Triple("s1", 4, FoundTodosLog.Why.NOT_IN_SEGMENT), drops.drops.first())
        // Nothing the meeting speaks of: the noted list shows none of them.
        val noted = llm.prompts.single().first.lines()[1]
        assertTrue(noted, copies.none { noted.contains(it) })
        assertTrue(noted, noted.endsWith("said for it: -"))
    }

    @Test
    fun `a heading of the meeting's list and its last greeting are no items`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                item("S2", "Second, the budget", capture = "inferred"),
                item("S5", "See you", type = "appointment", whenId = "none", capture = "commitment"),
                item("S4", "Order the lunch for the team event")
            )
        )

        val found = search(llm)("s1", meeting, zone, "en").todos.map { it.text }

        assertFalse(found.toString(), "Second, the budget" in found || "Second the budget" in found)
        assertFalse(found.toString(), found.any { it.startsWith("See you") })
        assertTrue(found.toString(), "Order the lunch for the team event" in found)
        assertTrue(drops.drops.toString(), drops.drops.any { it.third == FoundTodosLog.Why.HEADING_OR_GREETING })
    }

    @Test
    fun `the same task said again in a new recording with a new time is shown as noted and kept`() = runBlocking {
        val friday = LocalDateTime.of(2026, 10, 16, 12, 0)
        val parser = FakeTimeParser().at("on Friday at noon", friday)
        val segments = listOf(seg("n1", "Remind me to order the lunch for the team event on Friday at noon.", at(9, 0), session = "new"))
        val llm = FakeLlmRepository(answer = items(item("S1", "Order the lunch for the team event", whenId = "T1")))

        val found = search(llm, parser)("new", segments, zone, "en", existingTodos = listOf(known("Order the lunch for the team event"))).todos

        assertTrue(llm.prompts.single().first.lines()[1].endsWith("said for it: \"Order the lunch for the team event\""))
        assertEquals(listOf("Order the lunch for the team event"), found.map { it.text })
        assertEquals(friday.atZone(zone).toInstant().toEpochMilli(), found.single().dueAt)
        assertTrue(drops.drops.isEmpty())
    }

    @Test
    fun `a Malayalam part's items are kept by the words copied from their segments, made-up ones dropped`() = runBlocking {
        val segments = listOf(
            // Tomorrow morning call the doctor, then send the report.
            seg("m1", "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ റിപ്പോർട്ട് അയക്കണം.", at(9, 0)),
            // Go to the shop this evening and buy milk and bread.
            seg("m2", "ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം.", at(9, 1))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Call the doctor", capture = "commitment", said = "ഡോക്ടറെ വിളിക്കണം"),
                item("S2", "Buy milk and bread", said = "പാലും റൊട്ടിയും വാങ്ങണം"),
                // The prompt's example, with words the segment doesn't hold.
                item("S1", "Make the clock bigger", type = "idea", said = "ക്ലോക്ക് വലുതാക്കണം"),
                // An answer without the grammar copied nothing: the English text alone isn't in Malayalam.
                item("S2", "Send the report", capture = "inferred")
            )
        )

        val found = search(llm)("s1", segments, zone, "en").todos

        assertEquals(listOf("Call the doctor", "Buy milk and bread"), found.map { it.text })
        // No English or German cue can be read in Malayalam: the model's capture decides.
        assertEquals(listOf(TodoStatus.OPEN, TodoStatus.OPEN), found.map { it.status })
        assertEquals(Triple("s1", 2, FoundTodosLog.Why.NOT_IN_SEGMENT), drops.drops.single())
        // The call asks for the copied words, and the header says why.
        val (text, _) = llm.prompts.single()
        assertTrue(text, text.contains("read the Malayalam below and write in English."))
        assertTrue(text, text.contains(ResourceProvider.TODO_SAID))
        val todoShape = Json.parseToJsonElement((llm.grammars.single() as com.satori.domain.repository.OutputGrammar.JsonSchema).schema)
            .jsonObject["properties"]!!.jsonObject["items"]!!.jsonObject["items"]!!.jsonObject["anyOf"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("segment", "type", "said", "text", "when", "capture"), todoShape["properties"]!!.jsonObject.keys.toList())
    }

    /** The items found in [sentence], said at nine on its own, by the spotter, with a model that finds none. */
    private fun spotted(sentence: String, language: String) = runBlocking {
        search(FakeLlmRepository(answer = items()), RuleBasedTimeParser())("s1", listOf(seg("one", sentence, at(9, 0))), zone, language).todos
    }

    @Test
    fun `German reminders said with a zu infinitive stay open to-dos with their time`() {
        val reminders = mapOf(
            "Nicht vergessen, morgen einzukaufen." to "Einkaufen",
            "Erinnere mich daran, morgen abzuwaschen." to "Abwaschen",
            "Erinnere mich morgen daran, aufzuräumen." to "Aufräumen",
            "Denk daran, heute Abend anzurufen." to "Anrufen"
        )

        for ((sentence, text) in reminders) {
            val todo = spotted(sentence, "de").singleOrNull()

            assertEquals(sentence, text, todo?.text)
            assertEquals(sentence, TodoStatus.OPEN, todo?.status)
            assertNotNull(sentence, todo?.dueAt)
        }
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `a reminder or an appointment that starts with an ordinal is no heading`() {
        val meeting = spotted("Remind me about the second meeting with Anna on Friday at 3.", "en").single()
        val dentist = spotted("Note to self: second dentist appointment on Monday at 10.", "en").single()

        assertEquals("Second meeting with Anna", meeting.text)
        assertEquals(TodoStatus.OPEN, meeting.status)
        assertNotNull(meeting.dueAt)
        assertEquals("Second dentist appointment", dentist.text)
        assertNotNull(dentist.dueAt)
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `the model's appointment that starts with an ordinal is kept with its time, its heading without one is not`() = runBlocking {
        val friday = LocalDateTime.of(2026, 10, 16, 15, 0)
        val parser = FakeTimeParser().at("on Friday at 3", friday)
        val segments = listOf(
            seg("p1", "Third, the planning. We have the second meeting with Anna on Friday at 3.", at(9, 0))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Second meeting with Anna", type = "appointment", whenId = "T1", capture = "commitment"),
                item("S1", "Third, the planning", capture = "inferred")
            )
        )

        val found = search(llm, parser)("s1", segments, zone, "en").todos

        assertEquals(listOf("Second meeting with Anna"), found.map { it.text })
        assertEquals(friday.atZone(zone).toInstant().toEpochMilli(), found.single().dueAt)
        assertEquals(Triple("s1", 1, FoundTodosLog.Why.HEADING_OR_GREETING), drops.drops.single())
    }

    @Test
    fun `the prompt's example is dropped from a segment that only says something big`() = runBlocking {
        val segments = listOf(seg("b1", "That is a big problem for the move, so we should rent a van.", at(9, 0)))
        val llm = FakeLlmRepository(
            answer = items(item("S1", "Make the clock bigger", type = "idea"), item("S1", "Rent a van", capture = "inferred"))
        )

        val found = search(llm)("s1", segments, zone, "en").todos.map { it.text }

        assertFalse(found.toString(), "Make the clock bigger" in found)
        assertTrue(found.toString(), "Rent a van" in found)
        assertEquals(Triple("s1", 1, FoundTodosLog.Why.NOT_IN_SEGMENT), drops.drops.single())
    }

    @Test
    fun `a word in capitals is no hesitation, so a reminder keeps it`() {
        assertEquals("Take mom to the ER", spotted("Remind me to take mom to the ER tomorrow.", "en").single().text)
    }

    @Test
    fun `an English part asks for no copied words`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S4", "Order the lunch for the team event")))

        search(llm)("s1", meeting, zone, "en")

        val (text, _) = llm.prompts.single()
        assertFalse(text, text.contains(ResourceProvider.TODO_SAID))
        assertFalse(text, text.contains(ResourceProvider.INSTRUCTION_LEAD))
    }
}
