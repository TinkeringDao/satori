package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Only real to-dos (brief 0.7.1, part A.2). On 2026-10-16 the 1B gave a Malayalam reading that
 * asked for nothing three items: the to-do prompt's order on the "said" field as an open to-do,
 * and a sentence on what the text describes as an open to-do and as an idea. Items of those kinds
 * are dropped and counted; a real reminder stays; the live model's to-dos of a Malayalam line need
 * a request or a commitment in it, Gemma 4 E2B's don't; the same text is never both a to-do and an
 * idea; and English and German to-dos come out as before. The sentences are made up, glossed in
 * English above the Malayalam ones; none is a line of the owner's.
 */
class RealTodosTest {

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

    /** Gemma 4 E2B in memory: the answers of [llm], given by the model for the work after Stop. */
    private class LargeModel(llm: FakeLlmRepository) : LlmRepository by llm {
        override val isSwitchedModelInMemory: Boolean = true
    }

    private fun search(llm: LlmRepository) =
        ExtractTodosUseCase(llm, TodoResources(), FakeTimeParser(), now = { 0L }, newId = { "todo-${++ids}" }, foundLog = drops)

    private fun at(minute: Int): Long = ZonedDateTime.of(2026, 10, 16, 10, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun seg(id: String, text: String, time: Long) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = time, isComplete = true)

    private fun item(segment: String, text: String, type: String = "task", capture: String = "explicit", said: String? = null): String {
        val quote = said?.let { """, "said": "$it"""" }.orEmpty()
        return if (type == "idea") """{"segment": "$segment", "type": "idea"$quote, "text": "$text"}"""
        else """{"segment": "$segment", "type": "$type"$quote, "text": "$text", "when": "none", "capture": "$capture"}"""
    }

    private fun items(vararg items: String) = """{"items": [${items.joinToString(", ")}]}"""

    // In the old town a farmer lived with his son.
    private val story = "പഴയ പട്ടണത്തിൽ ഒരു കർഷകൻ തന്റെ മകനോടൊപ്പം ജീവിച്ചിരുന്നു."

    // In the rainy season the river overflowed, and the children played on its bank.
    private val river = "മഴക്കാലത്ത് പുഴ നിറഞ്ഞൊഴുകി, കുട്ടികൾ കരയിൽ കളിച്ചു."

    // Tomorrow morning I have to go to the bank and hand over the loan papers.
    private val bank = "നാളെ രാവിലെ ബാങ്കിൽ പോയി ലോൺ രേഖകൾ കൊടുക്കണം."

    // This month the electricity bill must be paid ("current" in Malayalam letters).
    private val bill = "ഈ മാസം കറന്റ് ബിൽ അടയ്ക്കണം."

    // Tomorrow evening, a long journey.
    private val journey = "നാളെ വൈകുന്നേരം ഒരു നീണ്ട യാത്ര."

    // In the evening, don't forget to buy medicine for mother.
    private val medicine = "വൈകുന്നേരം അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്."

    // On Thursday there is an appointment at the dentist's.
    private val dentist = "വ്യാഴാഴ്ച ദന്തഡോക്ടറുടെ അടുത്ത് അപ്പോയിന്റ്മെന്റ് ഉണ്ട്."

    // Tomorrow at eleven there is a meeting with Ravi.
    private val meeting = "നാളെ പതിനൊന്ന് മണിക്ക് രവിയുമായി യോഗം ഉണ്ട്."

    // I'll give him the rent: a promise, with no time and no "must".
    private val rent = "ഞാൻ അവന് വാടക കൊടുക്കാം."

    // Please send me that receipt.
    private val receipt = "ആ രസീത് ഒന്ന് അയച്ചുതരണം."

    // A good pair of glasses for father must be bought at the shop: the verb at the clause's end.
    private val glasses = "അച്ഛന് വേണ്ടി നല്ല ഒരു കണ്ണട കടയിൽ നിന്ന് വാങ്ങണം."

    // An old man cried, thinking of his daughter: a reading's line.
    private val oldMan = "ഒരു വൃദ്ധൻ തന്റെ മകളെ ഓർത്ത് കരഞ്ഞു."

    // That letter must be written.
    private val letter = "ആ കത്ത് എഴുതണം."

    @Test
    fun `a Malayalam appointment names what is on without a verb and is kept on both models, its day its cue on the 1B`() = runBlocking {
        val answer = items(
            item("S1", "Dentist appointment on Thursday", type = "appointment", said = "ദന്തഡോക്ടറുടെ അടുത്ത് അപ്പോയിന്റ്മെന്റ്"),
            item("S2", "Meeting with Ravi", type = "appointment", said = "രവിയുമായി യോഗം")
        )
        val segments = listOf(seg("m1", dentist, at(0)), seg("m2", meeting, at(1)))

        val small = search(FakeLlmRepository(answer = answer))("s1", segments, zone, "en").todos
        val large = search(LargeModel(FakeLlmRepository(answer = answer)))("s1", segments, zone, "en").todos

        val kept = listOf("Dentist appointment on Thursday" to TodoStatus.OPEN, "Meeting with Ravi" to TodoStatus.OPEN)
        assertEquals(kept, small.map { it.text to it.status })
        assertEquals(kept, large.map { it.text to it.status })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `the 1B's Malayalam reminders of a promise, a request for the speaker and a verb at the clause's end are kept`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Give him the rent", capture = "commitment", said = "അവന് വാടക കൊടുക്കാം"),
                item("S2", "Send the receipt", capture = "explicit", said = "ആ രസീത് ഒന്ന് അയച്ചുതരണം"),
                item("S3", "Buy glasses for father", capture = "commitment", said = "അച്ഛന് വേണ്ടി നല്ല")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", rent, at(0)), seg("m2", receipt, at(1)), seg("m3", glasses, at(2))), zone, "en").todos

        assertEquals(listOf("Give him the rent", "Send the receipt", "Buy glasses for father"), found.map { it.text })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `a reading's retelling is no idea of the 1B, and its subjectless or story-named descriptions are dropped on both models`() = runBlocking {
        val answer = items(
            item("S1", "An old man's longing for his daughter", type = "idea", said = "തന്റെ മകളെ ഓർത്ത്"),
            item("S1", "A sad story about an old man and his daughter", type = "idea", said = "ഒരു വൃദ്ധൻ തന്റെ മകളെ"),
            item("S1", "Describes an old man's grief", capture = "explicit", said = "മകളെ ഓർത്ത് കരഞ്ഞു"),
            item("S1", "Tells the story of an old man", capture = "explicit", said = "ഒരു വൃദ്ധൻ")
        )
        val segments = listOf(seg("m1", oldMan, at(0)))

        val small = search(FakeLlmRepository(answer = answer))("s1", segments, zone, "en").todos
        assertTrue(small.toString(), small.isEmpty())
        assertEquals(
            listOf(Triple("s1", 3, FoundTodosLog.Why.DESCRIBE_THE_TEXT), Triple("s1", 1, FoundTodosLog.Why.NO_MALAYALAM_IDEA)),
            drops.drops
        )

        drops.drops.clear()
        val large = search(LargeModel(FakeLlmRepository(answer = answer)))("s1", segments, zone, "en").todos
        // Gemma 4 E2B is not held to the cues: only the descriptions go.
        assertEquals(listOf("An old man's longing for his daughter" to TodoStatus.IDEA), large.map { it.text to it.status })
        assertEquals(listOf(Triple("s1", 3, FoundTodosLog.Why.DESCRIBE_THE_TEXT)), drops.drops)
    }

    @Test
    fun `a to-do of only words about writing and letters is no echo of the prompt`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Write the letter", capture = "commitment", said = "ആ കത്ത് എഴുതണം")))

        val found = search(llm)("s1", listOf(seg("m1", letter, at(0))), zone, "en").todos

        assertEquals(listOf("Write the letter" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `an idea that says a to-do's thing with an -ing is the same text`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Pay the electricity bill", capture = "commitment", said = "കറന്റ് ബിൽ അടയ്ക്കണം"),
                item("S1", "Paying the electricity bill", type = "idea", said = "കറന്റ് ബിൽ അടയ്ക്കണം")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", bill, at(0))), zone, "en").todos

        assertEquals(listOf("Pay the electricity bill" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.SAME_AS_ANOTHER)), drops.drops)
    }

    @Test
    fun `English items whose first words read like a recording or a book reporting are kept`() = runBlocking {
        val segments = listOf(
            seg("e1", "It would be nice if the recording states were shown on the cover screen.", at(0)),
            seg("e2", "I need to book talks with the publisher for March.", at(1))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Recording states shown on the cover screen", type = "idea"),
                item("S2", "Book talks with the publisher", capture = "commitment")
            )
        )

        val found = search(llm)("s1", segments, zone, "en").todos

        assertEquals(
            listOf("Recording states shown on the cover screen" to TodoStatus.IDEA, "Book talks with the publisher" to TodoStatus.OPEN),
            found.map { it.text to it.status }
        )
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `a reading that asks for nothing gives none of the 1B's three items, and the reminder beside it stays`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                // The order on said, written as the text of a to-do.
                item("S1", "English words are copied as they are written there, at most 8 words", said = "പഴയ പട്ടണത്തിൽ ഒരു കർഷകൻ"),
                // What the text describes, as a to-do and as an idea.
                item("S1", "The text describes a sad story about a farmer's", capture = "inferred", said = "ഒരു കർഷകൻ തന്റെ മകനോടൊപ്പം"),
                item("S1", "The text describes a sad story about a farmer's", type = "idea", said = "ഒരു കർഷകൻ തന്റെ മകനോടൊപ്പം"),
                item("S2", "Hand over the loan papers at the bank", capture = "commitment", said = "ബാങ്കിൽ പോയി ലോൺ രേഖകൾ കൊടുക്കണം")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", story, at(0)), seg("m2", bank, at(1))), zone, "en").todos

        assertEquals(listOf("Hand over the loan papers at the bank" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertEquals(
            listOf(Triple("s1", 1, FoundTodosLog.Why.ECHOES_PROMPT), Triple("s1", 2, FoundTodosLog.Why.DESCRIBE_THE_TEXT)),
            drops.drops
        )
    }

    @Test
    fun `an item that writes a key of the answer into its text is an echo of the prompt`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Capture: explicit, for the bank papers", said = "ബാങ്കിൽ പോയി ലോൺ രേഖകൾ")))

        val found = search(llm)("s1", listOf(seg("m1", bank, at(0))), zone, "en").todos

        assertTrue(found.toString(), found.isEmpty())
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.ECHOES_PROMPT)), drops.drops)
    }

    @Test
    fun `a real Malayalam reminder whose cue stands right after the quoted words is kept`() = runBlocking {
        val llm = FakeLlmRepository(answer = items(item("S1", "Buy medicine for mother", said = "അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ")))

        val found = search(llm)("s1", listOf(seg("m1", medicine, at(0))), zone, "en").todos

        assertEquals(listOf("Buy medicine for mother" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `the live model's to-do of a Malayalam line that asks for nothing is dropped, Gemma 4 E2B's is kept`() = runBlocking {
        val answer = items(item("S1", "Take the children to the river", capture = "inferred", said = "കുട്ടികൾ കരയിൽ കളിച്ചു"))

        val small = search(FakeLlmRepository(answer = answer))("s1", listOf(seg("m1", river, at(0))), zone, "en").todos
        assertTrue(small.toString(), small.isEmpty())
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.NO_MALAYALAM_CUE)), drops.drops)

        drops.drops.clear()
        val large = search(LargeModel(FakeLlmRepository(answer = answer)))("s1", listOf(seg("m1", river, at(0))), zone, "en").todos
        assertEquals(listOf("Take the children to the river"), large.map { it.text })
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
    }

    @Test
    fun `a to-do of a Malayalam line whose English names no action is dropped, whichever model wrote it`() = runBlocking {
        val answer = items(item("S1", "A long journey tomorrow evening", capture = "inferred", said = "ഒരു നീണ്ട യാത്ര"))

        val small = search(FakeLlmRepository(answer = answer))("s1", listOf(seg("m1", journey, at(0))), zone, "en").todos
        val large = search(LargeModel(FakeLlmRepository(answer = answer)))("s1", listOf(seg("m1", journey, at(0))), zone, "en").todos

        assertTrue(small.isEmpty() && large.isEmpty())
        assertEquals(List(2) { Triple("s1", 1, FoundTodosLog.Why.NO_ACTION) }, drops.drops)
    }

    @Test
    fun `the same text is never both a to-do and an idea, the one found first stays`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Pay the electricity bill", capture = "commitment", said = "കറന്റ് ബിൽ അടയ്ക്കണം"),
                item("S1", "Pay the electricity bill", type = "idea", said = "കറന്റ് ബിൽ അടയ്ക്കണം")
            )
        )

        val found = search(llm)("s1", listOf(seg("m1", bill, at(0))), zone, "en").todos

        assertEquals(listOf("Pay the electricity bill" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertEquals(listOf(Triple("s1", 1, FoundTodosLog.Why.SAME_AS_ANOTHER)), drops.drops)
    }

    @Test
    fun `of an idea and a to-do of the same words the stronger stays, whichever came first`() = runBlocking {
        val segments = listOf(seg("m1", bill, at(0)))
        val idea = item("S1", "Pay the electricity bill", type = "idea", said = "കറന്റ് ബിൽ അടയ്ക്കണം")

        // A promise takes the place of the idea found before it.
        val promised = search(
            FakeLlmRepository(answer = items(idea, item("S1", "Pay the electricity bill", capture = "commitment", said = "കറന്റ് ബിൽ അടയ്ക്കണം")))
        )("s1", segments, zone, "en").todos
        // An idea stays before a to-do the model only inferred, a suggestion.
        val inferred = search(
            FakeLlmRepository(answer = items(idea, item("S1", "Pay the electricity bill", capture = "inferred", said = "കറന്റ് ബിൽ അടയ്ക്കണം")))
        )("s1", segments, zone, "en").todos

        assertEquals(listOf(TodoStatus.OPEN), promised.map { it.status })
        assertEquals(listOf(TodoStatus.IDEA), inferred.map { it.status })
        assertEquals(List(2) { Triple("s1", 1, FoundTodosLog.Why.SAME_AS_ANOTHER) }, drops.drops)
    }

    @Test
    fun `in English too, an idea with a to-do's words and a to-do with the text of the recording's own idea are dropped`() = runBlocking {
        val segments = listOf(
            seg("e1", "I need to book the hotel for the trip.", at(0)),
            seg("e2", "It would be nice to book the hotel for the trip.", at(1)),
            seg("e3", "Remind me to export the notes as a PDF.", at(2))
        )
        val ownIdea = Todo(
            id = "idea-1", sessionId = "s1", text = "Export the notes as a PDF", status = TodoStatus.IDEA,
            sourceSegmentIds = listOf("earlier"), createdAt = 0L, updatedAt = 0L
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Book the hotel for the trip", capture = "commitment"),
                item("S2", "Book the hotel for the trip", type = "idea"),
                item("S3", "Export the notes as a PDF")
            )
        )

        val found = search(llm)("s1", segments, zone, "en", existingTodos = listOf(ownIdea)).todos

        assertEquals(listOf("Book the hotel for the trip" to TodoStatus.OPEN), found.map { it.text to it.status })
        assertEquals(listOf(Triple("s1", 2, FoundTodosLog.Why.SAME_AS_ANOTHER)), drops.drops)
    }

    @Test
    fun `English to-dos and ideas come out as before, and no check of this release drops any of them`() = runBlocking {
        val segments = listOf(
            seg("e1", "Remind me to call the bank about the loan.", at(0)),
            seg("e2", "I need to renew the passport before the trip.", at(1)),
            seg("e3", "There should be an option to export the notes.", at(2))
        )
        val llm = FakeLlmRepository(
            answer = items(
                item("S1", "Call the bank about the loan"),
                item("S2", "Renew the passport before the trip", capture = "commitment"),
                item("S3", "Option to export the notes", type = "idea")
            )
        )

        val found = search(llm)("s1", segments, zone, "en").todos

        assertEquals(
            listOf(
                "Call the bank about the loan" to TodoStatus.OPEN,
                "Renew the passport before the trip" to TodoStatus.OPEN,
                "Option to export the notes" to TodoStatus.IDEA
            ),
            found.map { it.text to it.status }
        )
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
        assertFalse(llm.prompts.single().first.contains(ResourceProvider.INSTRUCTION_LEAD))
    }

    @Test
    fun `German to-dos and ideas come out as before, and no check of this release drops any of them`() = runBlocking {
        val segments = listOf(
            seg("d1", "Morgen muss ich die Steuererklärung abgeben.", at(0)),
            seg("d2", "Es wäre schön, wenn die App eine dunkle Ansicht hätte.", at(1))
        )
        val llm = FakeLlmRepository(
            answer = items(item("S1", "Steuererklärung abgeben", capture = "commitment"), item("S2", "Dunkle Ansicht für die App", type = "idea"))
        )

        val found = search(llm)("s1", segments, zone, "de").todos

        assertEquals(
            listOf("Steuererklärung abgeben" to TodoStatus.OPEN, "Dunkle Ansicht für die App" to TodoStatus.IDEA),
            found.map { it.text to it.status }
        )
        assertTrue(drops.drops.toString(), drops.drops.isEmpty())
        assertFalse(llm.prompts.single().first.contains(ResourceProvider.INSTRUCTION_LEAD))
    }
}
