package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.schema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A Malayalam recording's to-dos and ideas are written in English (brief item 2c, phone evidence of
 * 2026-10-15): a recording switched to Malayalam while it recorded got its two Malayalam lines,
 * copied word for word, as open to-dos in Malayalam script. An item written mostly in Malayalam
 * script is dropped and counted; an English item of a Malayalam segment is kept by the Malayalam
 * words the model copied from that segment, which are never stored; a part with Malayalam lines
 * among lines in Latin letters asks for those words and says the text is Malayalam; and English
 * and German parts are read as before. The sentences are made up, of the shape of the owner's.
 */
class MalayalamTodosInEnglishTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private var ids = 0

    /** The drops the search told the field log of. */
    private class Drops : FoundTodosLog {
        val drops = mutableListOf<Triple<String, Int, FoundTodosLog.Why>>()
        override fun dropped(sessionId: String, count: Int, why: FoundTodosLog.Why) {
            drops += Triple(sessionId, count, why)
        }
    }

    private val drops = Drops()

    private fun search(llm: FakeLlmRepository) =
        ExtractTodosUseCase(llm, TodoResources(), FakeTimeParser(), now = { 0L }, newId = { "todo-${++ids}" }, foundLog = drops)

    private fun at(hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(2026, 10, 15, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = time, isComplete = true)

    private fun item(segment: String, text: String, type: String = "task", capture: String = "explicit", said: String? = null): String {
        val quote = said?.let { """, "said": "$it"""" }.orEmpty()
        return if (type == "idea") """{"segment": "$segment", "type": "idea"$quote, "text": "$text"}"""
        else """{"segment": "$segment", "type": "$type"$quote, "text": "$text", "when": "none", "capture": "$capture"}"""
    }

    private fun items(vararg items: String) = """{"items": [${items.joinToString(", ")}]}"""

    // Tomorrow morning I have to go to the bank and hand over the loan papers.
    private val bank = "നാളെ രാവിലെ ബാങ്കിൽ പോയി ലോൺ രേഖകൾ കൊടുക്കണം."

    // In the evening, don't forget to buy medicine for mother.
    private val medicine = "വൈകുന്നേരം അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്."

    // At this week's meeting everyone said the cost and the time of the new house must be looked at again.
    private val meeting = "ഈ ആഴ്ചത്തെ യോഗത്തിൽ പുതിയ വീടിന്റെ പണിയുടെ ചെലവും സമയവും ഒന്നുകൂടി നോക്കണം എന്ന് എല്ലാവരും പറഞ്ഞു."

    // What Parakeet v3 writes of Malayalam speech before the recording is switched to Malayalam.
    private val beforeTheSwitch = listOf(
        seg("p1", "Then a lay the porch in a key run on the wall here.", at(10, 15, 4)),
        seg("p2", "Ah no para in the kettle so they can say them all.", at(10, 15, 8)),
        seg("p3", "It is the marrow of a cotton seen as I go by.", at(10, 15, 12))
    )

    @Test
    fun `a long Malayalam line given as a task is dropped and counted, an English item of the same line is kept`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", meeting, said = meeting.split(' ').take(8).joinToString(" ")),
                item("S1", "Check the cost of the new house", capture = "commitment", said = "ചെലവും സമയവും ഒന്നുകൂടി നോക്കണം")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", meeting, at(10, 0))), zone, "en").todos

        assertEquals(listOf("Check the cost of the new house"), found.map { it.text })
        assertEquals(Triple("s1", 1, FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT), drops.drops.single())
    }

    @Test
    fun `the two Malayalam lines of a recording switched to Malayalam are dropped as copies, not stored as open to-dos`() = runBlocking {
        val segments = beforeTheSwitch + listOf(seg("m1", bank, at(10, 15, 20)), seg("m2", medicine, at(10, 15, 26)))
        val llm = FakeLlmRepository(
            answer = items(item("S4", bank, said = bank), item("S5", medicine))
        )

        val found = search(llm)("s1", segments, zone, "en").todos

        assertTrue(found.toString(), found.isEmpty())
        assertEquals(Triple("s1", 2, FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT), drops.drops.single())
        // Most of the part is in Latin letters, yet the call says to read the Malayalam and asks for the copied words.
        val (text, _) = llm.prompts.single()
        assertTrue(text, text.contains("read the Malayalam below and write in English."))
        assertTrue(text, text.contains(ResourceProvider.TODO_SAID))
        assertEquals(listOf("segment", "type", "said", "text", "when", "capture"), llm.grammars.single().todoKeys())
    }

    @Test
    fun `a Malayalam reminder written in English is kept by the words of its segment, and those words are not stored`() = runBlocking {
        val said = "അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്"
        val llm = FakeLlmRepository(
            answer = items(
                item("S2", "Buy medicine for mother", said = said),
                // Words the segment doesn't hold, under an English text that may be anything.
                item("S1", "Book a room for the trip", capture = "inferred", said = "മുറി ബുക്ക് ചെയ്യണം")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", bank, at(9, 0)), seg("m2", medicine, at(9, 1))), zone, "en").todos

        val reminder = found.single()
        assertEquals("Buy medicine for mother", reminder.text)
        assertEquals(TodoStatus.OPEN, reminder.status)
        assertEquals(listOf("m2"), reminder.sourceSegmentIds)
        // The copied words checked the item; nothing of them is kept with it.
        assertTrue(reminder.toString(), listOf(reminder.text, reminder.timePhrase.orEmpty()).none { said in it || "മരുന്ന്" in it })
        assertEquals(Triple("s1", 1, FoundTodosLog.Why.NOT_IN_SEGMENT), drops.drops.single())
    }

    @Test
    fun `an item of a line in Latin letters among Malayalam ones is checked by its own words`() = runBlocking {
        val segments = listOf(
            seg("e1", "I need to renew the parking permit this week.", at(11, 0)),
            seg("m1", bank, at(11, 1)),
            seg("m2", medicine, at(11, 2))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Renew the parking permit", capture = "commitment", said = "renew the parking permit"),
                // The words copied are the segment's, but the text is not what it says.
                item("S1", "Book a room for the trip", capture = "commitment", said = "renew the parking permit"),
                item("S3", "Buy medicine for mother", said = "അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ")
            )
        )

        val found = search(llm)("s1", segments, zone, "en").todos

        assertEquals(listOf("Renew the parking permit", "Buy medicine for mother"), found.map { it.text })
        assertEquals(Triple("s1", 1, FoundTodosLog.Why.NOT_IN_SEGMENT), drops.drops.single())
    }

    @Test
    fun `an English part keeps an item with a name in Malayalam letters, and asks for no copied words`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Call ഗീത about the rent", capture = "explicit")))

        val found = search(llm)("s1", listOf(seg("e1", "Remind me to call ഗീത about the rent tomorrow.", at(12, 0))), zone, "en").todos

        assertEquals(listOf("Call ഗീത about the rent"), found.map { it.text })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
        val (text, _) = llm.prompts.single()
        assertTrue(text, !text.contains(ResourceProvider.TODO_SAID))
        assertTrue(text, !text.contains(ResourceProvider.INSTRUCTION_LEAD))
    }

    @Test
    fun `a German part whose to-dos are English is still checked by the German words copied for each item`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(item("S1", "Order the office furniture", capture = "commitment", said = "die Möbel für das Büro bestellen"))
        )
        val segments = listOf(seg("d1", "Morgen muss ich noch die Möbel für das Büro bestellen, sonst wird es zu spät.", at(13, 0)))

        val found = search(llm)("s1", segments, zone, "en", spotsOutright = false).todos

        assertEquals(listOf("Order the office furniture"), found.map { it.text })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
        assertTrue(llm.prompts.single().first.contains(ResourceProvider.TODO_SAID))
    }

    /** The keys of a to-do in the answer a call was held to, in the order the model writes them. */
    private fun OutputGrammar?.todoKeys(): List<String> =
        schema().getValue("properties").jsonObject.getValue("items").jsonObject.getValue("items").jsonObject
            .getValue("anyOf").jsonArray[0].jsonObject.getValue("properties").jsonObject.keys.toList()
}
