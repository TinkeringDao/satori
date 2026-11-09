package com.satori.domain.usecase.transcription

import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.LanguageDetector
import com.satori.domain.model.SegmentReading
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.todo.AnswerLog
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording with enough words for notes ends with a name (0.7.2): an answer that is no name gets
 * one more try from the transcript, then the notes' title stands in, then the default name shows,
 * and a line says which. On 2026-10-17 a Malayalam note about a voice-notes app got no name and no
 * line: its note had no title left, and the one answer, in the subject's own words, was taken for a
 * name of the kind of text. The prompts are the app's own; the transcripts, notes and answers are
 * made up, none the owner's.
 */
class NameNeverEmptyTest {

    private val shipped = ShippedPrompts()

    // 24 words: enough for notes, so the name gets its second try.
    private val talk = listOf(
        segment("We need to book the venue for the wedding in May, and the band has to confirm by Friday.", at = 1),
        segment("Anna asks for the guest list next week so the caterer can plan the menu.", at = 2)
    )

    // Made up: "This app records meetings, then gives notes and minutes. Its AI works offline on the
    // phone itself. It is a big help for people who find writing hard."
    private val malayalam = listOf(
        segment("ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും.", at = 1),
        segment("ഇതിന്റെ എഐ ഫോണിൽ തന്നെ ഓഫ്‌ലൈനായി പ്രവർത്തിക്കും, ഇന്റർനെറ്റ് വേണ്ട.", at = 2),
        segment("എഴുതാൻ ബുദ്ധിമുട്ടുള്ള ആളുകൾക്ക് ഇത് വലിയ സഹായമാണ്, അവർക്ക് സംസാരിച്ചാൽ മതി.", at = 3)
    )

    // The reader's English of the three lines (0.8.0), made up as they are: since R6 the name is asked of them.
    private val read = listOf(
        "This app records meetings, then gives notes and minutes.",
        "Its AI works offline on the phone itself; no internet is needed.",
        "It is a big help for people who find writing hard; they only have to speak."
    )

    /** [repo] with the readings of [malayalam]'s lines, as the Reading step stores them. */
    private fun read(repo: FakeTranscriptionRepository) = repo.apply {
        readings = malayalam.mapIndexed { i, line ->
            SegmentReading(line.id, line.sessionId, SegmentReading.sourceHashOf(line.text), read[i], model = "reader", score = -0.4, createdAt = 1L)
        }
    }

    // The note the 11:31 name read: English points about the app, no title.
    private val appNote = insight(null, "- Records meetings and gives notes and minutes.\n- The AI runs offline on the phone.\n- Helps people who find writing hard.", at = 5)

    /** The field log's lines of a run, the echoes' and the notes' in one list, in order. */
    private class Lines : NotesLog, PromptEchoLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }
    }

    private class Kept : AnswerLog {
        val events = mutableListOf<String>()
        override fun todoAnswer(sessionId: String, window: Int, answer: String) = Unit
        override fun answer(sessionId: String, call: String, answer: String) {
            events += "$sessionId-$call: $answer"
        }

        override fun clear(sessionId: String, calls: String) {
            events += "clear $sessionId-$calls"
        }
    }

    private val lines = Lines()
    private val kept = Kept()

    private fun name(repo: FakeTranscriptionRepository, llm: ScriptedLlmRepository, replacing: String? = null) = runBlocking {
        AutoNameSessionUseCase(repo, GenerateSessionTitleUseCase(llm, shipped, lines, lines, kept), lines, kept)("s1", "en", replacing = replacing)
    }

    @Test
    fun `an answer of the kind of text is asked once more from the transcript, not the notes`() {
        val repo = FakeTranscriptionRepository(session(), talk, listOf(insight(null, "- Book the venue for May.", at = 5)))
        val llm = ScriptedLlmRepository(listOf("Audio Notes", "Wedding in May"))

        assertEquals("Wedding in May", name(repo, llm).getOrThrow())

        assertEquals("Wedding in May", repo.session?.name)
        assertEquals("- Book the venue for May", llm.prompts[0].first)
        assertTrue(llm.prompts[1].first, llm.prompts[1].first.startsWith("We need to book the venue"))
        assertEquals(
            listOf(
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            lines.heard
        )
    }

    @Test
    fun `an empty answer and one that repeats its prompt are asked once more too`() {
        val empty = FakeTranscriptionRepository(session(), talk)
        assertEquals("Wedding in May", name(empty, ScriptedLlmRepository(listOf("Title:", "Wedding in May"))).getOrThrow())
        assertEquals("Dropped 1 names that are empty in s1", lines.heard.first())

        lines.heard.clear()
        // A Malayalam recording's name is asked of its readings (0.8.0), with an English recording's prompt and no
        // line of instructions; asked of the Malayalam itself, as the debug rerun's reader=off asks, an echo of the
        // order to name the topic in 2 to 5 words is no name.
        val malayalamRepo = read(FakeTranscriptionRepository(session(), malayalam, listOf(appNote)))
        val empty2 = ScriptedLlmRepository(listOf("Title:", "Offline Meeting App"))
        assertEquals("Offline Meeting App", name(malayalamRepo, empty2).getOrThrow())
        assertEquals(
            listOf(
                "Dropped 1 names that are empty in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            lines.heard
        )
        assertTrue(empty2.leads.all { it == null })

        lines.heard.clear()
        val echo = ScriptedLlmRepository(listOf("Topic in English"))
        val attempt = runBlocking { GenerateSessionTitleUseCase(echo, shipped, lines, lines).attempt(malayalam, emptyList(), "en") }
        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.ECHO), attempt)
        assertEquals(listOf("Dropped 1 names that repeat their prompt in s1"), lines.heard)
    }

    @Test
    fun `the 11-31 shape, a Malayalam note with an untitled note about a notes app, is named in the subject's words`() {
        val repo = read(FakeTranscriptionRepository(session(), malayalam, listOf(appNote)))
        // "Voice" is no word of the notes: generic. The second, from the transcript, is the notes' own words.
        val llm = ScriptedLlmRepository(listOf("Voice Notes & Minutes", "Meeting Notes & Minutes"))

        assertEquals("Meeting Notes & Minutes", name(repo, llm).getOrThrow())

        // The first read the English notes, the second the transcript's readings (0.8.0), with no orders: never Malayalam.
        assertFalse(LanguageDetector.isMalayalam(llm.prompts[0].first))
        assertFalse(LanguageDetector.isMalayalam(llm.prompts[1].first))
        assertTrue(llm.prompts[1].first.contains(read.first()))
        assertEquals(listOf(null, null), llm.leads)
        assertEquals("Name of s1: from its transcript, after 2 answers", lines.heard.last())
    }

    @Test
    fun `two answers that are no name leave the notes' title, then the default name, each with its line`() {
        val withTitle = FakeTranscriptionRepository(session(), talk, listOf(insight("Wedding planning", "- Book the venue for May.", at = 5)))
        assertEquals("Wedding planning", name(withTitle, ScriptedLlmRepository(listOf("Summary", "Recording"))).getOrThrow())
        assertEquals("Name of s1: the notes' title, after 2 answers", lines.heard.last())

        lines.heard.clear()
        val untitled = FakeTranscriptionRepository(session(), talk, listOf(insight(null, "- Book the venue for May.", at = 5)))
        val none = name(untitled, ScriptedLlmRepository(listOf("Summary", "Audio notes")))
        assertNull(none.getOrThrow())
        assertNull(untitled.session?.name)
        assertEquals("Name of s1: none after 2 answers, the default name shows", lines.heard.last())
    }

    @Test
    fun `a model that fails is not asked again, a note's title stands in, and without one the line names the failure`() {
        val withTitle = FakeTranscriptionRepository(session(), talk, listOf(insight("Wedding planning", "- Book the venue.", at = 5)))
        val failing = ScriptedLlmRepository(listOf(null))
        assertEquals("Wedding planning", name(withTitle, failing).getOrThrow())
        assertEquals(1, failing.prompts.size)
        assertEquals(listOf("Name of s1: the notes' title, after 1 answer"), lines.heard)

        lines.heard.clear()
        val untitled = FakeTranscriptionRepository(session(), talk)
        assertTrue(name(untitled, ScriptedLlmRepository(listOf(null))).isFailure)
        assertEquals(listOf("Name of s1: none, the model failed (IllegalStateException); the default name shows"), lines.heard)
    }

    @Test
    fun `a recording with fewer words than notes need gets one answer`() {
        val short = FakeTranscriptionRepository(session(), listOf(segment("Book the venue for the wedding in May", at = 1)))
        val llm = ScriptedLlmRepository(listOf("Summary", "Wedding in May"))

        assertNull(name(short, llm).getOrThrow())
        assertEquals(1, llm.prompts.size)
        assertEquals(
            listOf("Dropped 1 names that name only the kind of text in s1", "Name of s1: none after 1 answer, the default name shows"),
            lines.heard
        )
    }

    @Test
    fun `the name to replace stays when neither answer is a name`() {
        val repo = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk)

        assertNull(name(repo, ScriptedLlmRepository(listOf("Summary", "Notes")), replacing = "Workflow Kettle Challenges").getOrThrow())
        assertEquals("Workflow Kettle Challenges", repo.session?.name)
    }

    @Test
    fun `each answer is kept under its tag, once the name's old ones went`() {
        val repo = FakeTranscriptionRepository(session(), talk)

        name(repo, ScriptedLlmRepository(listOf("Summary", "Wedding in May")))

        assertEquals(listOf("clear s1-name", "s1-name-0: Summary", "s1-name-1: Wedding in May"), kept.events)
    }

    @Test
    fun `German names follow the same chain`() {
        val german = listOf(
            segment("Wir müssen den Saal für die Hochzeit im Mai buchen, und die Band muss bis Freitag zusagen.", at = 1),
            segment("Anna möchte die Gästeliste nächste Woche, damit der Caterer das Menü planen kann.", at = 2)
        )
        val repo = FakeTranscriptionRepository(session(), german)
        val llm = ScriptedLlmRepository(listOf("Sprachnotiz", "Hochzeit im Mai"))

        val named = runBlocking {
            AutoNameSessionUseCase(repo, GenerateSessionTitleUseCase(llm, shipped, lines, lines, kept), lines, kept)("s1", "de")
        }

        assertEquals("Hochzeit im Mai", named.getOrThrow())
        assertEquals(shipped.getSessionTitlePrompt("de"), llm.prompts[1].second)
        assertEquals("Name of s1: from its transcript, after 2 answers", lines.heard.last())
    }

    @Test
    fun `one answer's verdict says why it is no name`() = runBlocking {
        val useCase = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Audio Transcription Summary")), shipped, lines, lines, kept)
        assertEquals(
            GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.GENERIC),
            useCase.attempt(talk, emptyList(), "en")
        )
        val empty = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("")), shipped, lines, lines, kept)
        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.EMPTY), empty.attempt(talk, emptyList(), "en"))
        assertEquals(GenerateSessionTitleUseCase.Attempt.NoInput, empty.attempt(listOf(segment("Hello", at = 1)), emptyList(), "en"))
        val failed = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf(null)), shipped).attempt(talk, emptyList(), "en")
        assertTrue(failed is GenerateSessionTitleUseCase.Attempt.Failed)
        // A name of the notes' words stays, asked from the transcript, with the notes as its subject.
        val fromTranscript = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Meeting Notes & Minutes")), shipped)
            .attempt(malayalam, emptyList(), "en", answer = 1, about = listOf(appNote))
        assertEquals(GenerateSessionTitleUseCase.Attempt.Titled("Meeting Notes & Minutes", weak = true), fromTranscript)
        // One the rules keep alone is no weak one.
        val specific = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Offline Meeting App")), shipped)
            .attempt(malayalam, emptyList(), "en", answer = 1, about = listOf(appNote))
        assertEquals(GenerateSessionTitleUseCase.Attempt.Titled("Offline Meeting App"), specific)
    }

    // ─── The review of 0.7.2 ────────────────────────────────────────────────────

    // A budget meeting, made up: its notes say "meeting", "notes" and "minutes", as a meeting's do.
    private val meeting = listOf(
        segment("Okay, let's start the meeting. Tom, can you take the notes today?", at = 1),
        segment("We need to agree the budget for the third quarter, and the marketing spend goes up by ten percent.", at = 2),
        segment("Anna sends the minutes by Friday, and we meet again in two weeks.", at = 3)
    )
    private val meetingNotes = "- The meeting agreed the Q3 budget, marketing up ten percent.\n- Tom takes the notes; Anna sends the minutes by Friday."

    @Test
    fun `a generic name in a meeting's own words gives way to its note's specific title`() {
        val repo = FakeTranscriptionRepository(session(), meeting, listOf(insight("Q3 Budget Review", meetingNotes, at = 5)))
        val llm = ScriptedLlmRepository(listOf("Meeting Minutes", "Budget Plan"))

        assertEquals("Q3 Budget Review", name(repo, llm).getOrThrow())

        assertEquals("Q3 Budget Review", repo.session?.name)
        // No second answer: the note's title is the name.
        assertEquals(1, llm.prompts.size)
        assertEquals(
            listOf("Dropped 1 names that name only the kind of text in s1", "Name of s1: the notes' title, after 1 answer"),
            lines.heard
        )
    }

    @Test
    fun `a generic second answer from the transcript gives way to the note's title too, and a specific name is kept`() {
        val titled = FakeTranscriptionRepository(session(), meeting, listOf(insight("Q3 Budget Review", meetingNotes, at = 5)))
        assertEquals("Q3 Budget Review", name(titled, ScriptedLlmRepository(listOf("Summary", "Meeting Notes & Minutes"))).getOrThrow())
        assertEquals("Name of s1: the notes' title, after 2 answers", lines.heard.last())

        lines.heard.clear()
        val specific = FakeTranscriptionRepository(session(), meeting, listOf(insight("Q3 Budget Review", meetingNotes, at = 5)))
        assertEquals("Marketing Spend Rises", name(specific, ScriptedLlmRepository(listOf("Marketing Spend Rises"))).getOrThrow())
        assertEquals(listOf("Name of s1: from its notes, after 1 answer"), lines.heard)
    }

    @Test
    fun `without a specific note title the generic name in the subject's words is the name, and a weak note title stands in last`() {
        // The 11:31 shape: the note has no title.
        val untitled = FakeTranscriptionRepository(session(), meeting, listOf(insight(null, meetingNotes, at = 5)))
        assertEquals("Meeting Minutes", name(untitled, ScriptedLlmRepository(listOf("Meeting Minutes"))).getOrThrow())
        assertEquals(listOf("Name of s1: from its notes, after 1 answer"), lines.heard)

        // Two notes, the first titled only in its own words, the second specifically: the specific one stands in.
        lines.heard.clear()
        val two = FakeTranscriptionRepository(
            session(), meeting,
            listOf(insight("Meeting Notes and Minutes", meetingNotes, at = 5), insight("Marketing Budget", "- Marketing up ten percent.", at = 6))
        )
        assertEquals("Marketing Budget", name(two, ScriptedLlmRepository(listOf("Summary", "Recording"))).getOrThrow())

        // Only the weak one: it stands in.
        val weak = FakeTranscriptionRepository(session(), meeting, listOf(insight("Meeting Notes and Minutes", meetingNotes, at = 5)))
        assertEquals("Meeting Notes and Minutes", name(weak, ScriptedLlmRepository(listOf("Summary", "Recording"))).getOrThrow())
    }

    @Test
    fun `German - a generic name in a meeting's own words gives way to the note's title`() {
        val besprechung = listOf(
            segment("Also, fangen wir mit der Besprechung an. Tom, kannst du heute die Notizen machen?", at = 1),
            segment("Wir müssen das Budget für das dritte Quartal festlegen, das Marketing bekommt zehn Prozent mehr.", at = 2),
            segment("Anna schickt das Protokoll bis Freitag, und wir treffen uns in zwei Wochen wieder.", at = 3)
        )
        val notes = "- Die Besprechung legte das Budget fest.\n- Tom macht die Notizen; Anna schickt das Protokoll bis Freitag."
        val repo = FakeTranscriptionRepository(session(), besprechung, listOf(insight("Budget drittes Quartal", notes, at = 5)))

        val named = runBlocking {
            AutoNameSessionUseCase(repo, GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Zusammenfassung", "Protokoll der Besprechung")), shipped, lines, lines, kept), lines, kept)("s1", "de")
        }

        assertEquals("Budget drittes Quartal", named.getOrThrow())
        assertEquals("Name of s1: the notes' title, after 2 answers", lines.heard.last())
    }

    @Test
    fun `Make text again that finds no name says the old name stays, not that the default shows`() {
        val repo = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk)

        assertNull(name(repo, ScriptedLlmRepository(listOf("Summary", "Notes")), replacing = "Workflow Kettle Challenges").getOrThrow())

        assertEquals("Workflow Kettle Challenges", repo.session?.name)
        assertEquals("Name of s1: none after 2 answers, the old name stays", lines.heard.last())
        lines.heard.clear()
        val failing = FakeTranscriptionRepository(session(name = "Workflow Kettle Challenges"), talk)
        assertTrue(name(failing, ScriptedLlmRepository(listOf(null)), replacing = "Workflow Kettle Challenges").isFailure)
        assertEquals(listOf("Name of s1: none, the model failed (IllegalStateException); the old name stays"), lines.heard)
    }

    @Test
    fun `a Malayalam recording's name that only names its language, or only says it is a recording, is no name`() {
        // Asked of the Malayalam itself, as the debug rerun's reader=off asks: from the English notes, then from the
        // transcript. Since 0.8.0 the finishing asks of the readings, where the language's name never comes up.
        val asked = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Malayalam Audio", "Malayalam Conversation")), shipped, lines, lines)
        val echo = GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.ECHO)

        assertEquals(echo, runBlocking { asked.attempt(malayalam, listOf(appNote), "en") })
        assertEquals(echo, runBlocking { asked.attempt(malayalam, emptyList(), "en", answer = 1, about = listOf(appNote)) })
        assertEquals(List(2) { "Dropped 1 names that repeat their prompt in s1" }, lines.heard)
        // A name of the subject with the language's name in it stays.
        lines.heard.clear()
        val festivalNote = insight(null, "- The Malayalam film festival opens on Saturday.", at = 5)
        val festival = runBlocking {
            GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Malayalam Film Festival")), shipped, lines, lines).attempt(malayalam, listOf(festivalNote), "en")
        }
        assertEquals("Malayalam Film Festival", (festival as GenerateSessionTitleUseCase.Attempt.Titled).title)
    }

    @Test
    fun `a junk note's self-description makes no name of the kind of text`() {
        // Made up: what a junk transcript's notes could say of it, kept as a point where nothing filtered it.
        val junk = FakeTranscriptionRepository(session(), talk, listOf(insight("Audio Recording", "- This is an audio recording.\n- Transcript of the recording.", at = 5)))
        val llm = ScriptedLlmRepository(listOf("Audio Recording", "Recording Transcript"))

        assertNull(name(junk, llm).getOrThrow())

        assertEquals(2, llm.prompts.size)
        assertEquals("Name of s1: none after 2 answers, the default name shows", lines.heard.last())
    }
}
