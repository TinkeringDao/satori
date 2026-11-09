package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * Text in the wrong script goes in every call (0.7.3), with a line and a count: the last note
 * after Stop and a live note, a recording's name (and the note titles that stand in for one), the
 * to-dos and ideas. The transcript and the words a to-do's answer copies from its segment are
 * never held to it. The prompts are the app's own; every sample is made up, none is the owner's.
 */
class WrongScriptCallsTest {

    private val shipped = ShippedPrompts()
    private val settings = FakeSettingsRepository(AppSettings(simpleListeningIntervalSeconds = 0))

    // Made up: "Tomorrow I'll take the bike to the shop, the brakes squeak and the back light is broken. It
    // should be ready by Saturday, then we ride to the lake. The man at the shop said it won't cost much."
    private val malayalam = "നാളെ സൈക്കിൾ കടയിൽ കൊണ്ടുപോകും, ബ്രേക്ക് ശബ്ദമുണ്ടാക്കുന്നു, പിന്നിലെ ലൈറ്റും പൊട്ടി. " +
        "ശനിയാഴ്ചയ്ക്ക് മുൻപ് ശരിയാകണം, പിന്നെ ഞങ്ങൾ തടാകത്തിലേക്ക് പോകും. കടയിലെ ആൾ പറഞ്ഞു വില കുറവായിരിക്കും എന്ന്."
    private val english = "Tomorrow I'll take the bike to the shop, the brakes squeak and the back light is broken. " +
        "It should be ready by Saturday, then we ride to the lake. The man at the shop said it won't cost much."

    // Made up, in Telugu: "bike repair" and "the brakes squeak", "fix the light".
    private val telugu = """{"title": "సైకిల్ మరమ్మత్తు", "points": ["బ్రేకులు శబ్దం చేస్తున్నాయి", "లైట్ బాగుచేయాలి"]}"""
    private val bike = """{"title": "Bike repair", "points": ["Take the bike to the shop tomorrow.", "Ready by Saturday."]}"""

    private class Lines : NotesLog, PromptEchoLog {
        val heard = mutableListOf<String>()
        override fun log(event: NotesLog.Event) {
            heard += NotesLog.line(event)
        }

        override fun dropped(sessionId: String, count: Int, what: PromptEchoLog.What) {
            heard += PromptEchoLog.line(sessionId, count, what)
        }
    }

    private val lines = Lines()

    private fun finalNote(llm: LlmRepository) = runBlocking {
        GenerateFinalInsightUseCase(llm, settings, shipped, lines, lines)(
            pendingText = malayalam, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        ).getOrThrow()
    }

    @Test
    fun `a note in Telugu script is asked once more, and the second answer is the note`() {
        val note = finalNote(ScriptedLlmRepository(listOf(telugu, bike)))!!

        assertEquals("Bike repair", note.title)
        assertEquals(
            listOf(
                "Notes of s1: no point left in the answer (every point left out), asking once more",
                "Notes of s1: the second answer kept, 2 lines",
                "Dropped 3 note lines in another script in s1"
            ),
            lines.heard
        )
    }

    @Test
    fun `a point with a Malayalam name in it stays, a title and a point in other scripts go`() {
        val mixed = """{"title": "Велосипед", "points": ["Take the bike to ഷിബു's shop.", "ബ്രേക്ക് ശരിയാക്കണം", "Ready by Saturday."]}"""

        val note = finalNote(ScriptedLlmRepository(listOf(mixed)))!!

        assertNull(note.title)
        assertEquals("- Take the bike to ഷിബു's shop.\n- Ready by Saturday.", note.content)
        assertEquals(listOf("Dropped 2 note lines in another script in s1"), lines.heard)
    }

    @Test
    fun `a live note's points in another script go and are counted`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("""{"title": "Bike repair", "points": ["Take the bike to the shop.", "బ్రేకులు శబ్దం చేస్తున్నాయి"]}"""))

        val notes = SyncSttLlmUseCase(llm, settings, shipped, lines, lines)(
            transcriptionFlow = flowOf(segment(english, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).toList()

        assertEquals("- Take the bike to the shop.", notes.single().content)
        assertEquals(listOf("Dropped 1 note lines in another script in s1"), lines.heard)
    }

    @Test
    fun `a name in another script is no name, and the chain goes on to the next answer`() = runBlocking {
        val segments = malayalam.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        // Since 0.8.0 the name is asked of the lines' readings (design R6): made up as the lines are.
        val read = listOf(
            "Tomorrow I'll take the bike to the shop",
            "the brakes squeak",
            "and the back light is broken. It should be ready by Saturday",
            "then we ride to the lake. The man at the shop said it won't cost much."
        )
        val repository = FakeTranscriptionRepository(session(), segments).apply {
            readings = segments.mapIndexed { i, line ->
                SegmentReading(line.id, "s1", SegmentReading.sourceHashOf(line.text), read[i], model = "reader", score = -0.4, createdAt = 1L)
            }
        }
        val llm = ScriptedLlmRepository(listOf("సైకిల్ మరమ్మత్తు", "Bike Repair Before Saturday"))

        val named = AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(llm, shipped, lines, lines), lines)("s1", "en")

        assertEquals("Bike Repair Before Saturday", named.getOrThrow())
        assertEquals(
            listOf(
                "Dropped 1 names in another script in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            lines.heard
        )
    }

    @Test
    fun `a note title in another script never stands in for the name`() = runBlocking {
        val segments = malayalam.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }
        // Notes an earlier version stored: a title in Telugu script, then one in Latin letters.
        val notes = listOf(insight("సైకిల్ మరమ్మత్తు", "- Take the bike to the shop.", at = 1L), insight("Bike Shop Visit", "- Ready by Saturday.", at = 2L))
        val repository = FakeTranscriptionRepository(session(), segments, notes)
        val llm = ScriptedLlmRepository(listOf("Notes", "Recording"))

        val named = AutoNameSessionUseCase(repository, GenerateSessionTitleUseCase(llm, shipped, lines, lines), lines)("s1", "en")

        assertEquals("Bike Shop Visit", named.getOrThrow())
    }

    @Test
    fun `a to-do in another script goes with its reason, and the Malayalam words copied for one are never held to it`() = runBlocking {
        val zone = ZoneId.of("Asia/Kolkata")
        val said = "നാളെ സൈക്കിൾ കടയിൽ കൊണ്ടുപോകും"
        val llm = FakeLlmRepository(
            answer = """{"items": [""" +
                """{"segment": "S1", "type": "task", "said": "$said", "text": "సైకిల్ షాపుకు తీసుకెళ్ళాలి", "when": "none", "capture": "commitment"}, """ +
                """{"segment": "S1", "type": "task", "said": "$said", "text": "Take the bike to the shop", "when": "none", "capture": "commitment"}]}"""
        )
        val drops = mutableListOf<Pair<Int, FoundTodosLog.Why>>()
        val search = ExtractTodosUseCase(llm, shipped, FakeTimeParser(), now = { 0L }, newId = { "t1" }, foundLog = { _, count, why -> drops += count to why })

        val found = search("s1", listOf(TranscriptionSegment("m1", "s1", "$said.", timestamp = 1_000L, isComplete = true)), zone, "en").todos

        assertEquals(listOf("Take the bike to the shop"), found.map { it.text })
        assertEquals(listOf(1 to FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT), drops)
        assertEquals("Dropped 1 found items written in another script in s1", FoundTodosLog.line("s1", 1, FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT))
    }

    // The review of 0.7.3: a script the transcript has no letter of drops a piece half Latin as it is.

    @Test
    fun `a Malayalam recording's point with Telugu words in it goes, one with a Malayalam name stays`() {
        // Made up, in Telugu: "for the bike" in an English point.
        val mixed = """{"title": "Bike repair", "points": ["Brakes సైకిల్ కోసం today.", "Take the bike to ഷിബു's shop.", "Ready by Saturday."]}"""

        val note = finalNote(ScriptedLlmRepository(listOf(mixed)))!!

        assertEquals("- Take the bike to ഷിബു's shop.\n- Ready by Saturday.", note.content)
        assertEquals(listOf("Dropped 1 note lines in another script in s1"), lines.heard)
    }

    @Test
    fun `a name with a word of a script the transcript has no letter of is no name`() = runBlocking {
        val segments = english.split(", ").mapIndexed { i, text -> segment(text, at = i.toLong()) }

        val attempt = GenerateSessionTitleUseCase(ScriptedLlmRepository(listOf("Bike Shop 修理")), shipped, lines, lines).attempt(segments, emptyList(), "en")

        assertEquals(GenerateSessionTitleUseCase.Attempt.Rejected(GenerateSessionTitleUseCase.Rejection.OTHER_SCRIPT), attempt)
    }

    @Test
    fun `the checks of a call know the scripts of what it read`() {
        assertEquals(AnswerDrop.OTHER_SCRIPT, AnswerChecks(spoken = malayalam).dropOf("Bluetooth firmware కోసం update"))
        assertEquals(null, AnswerChecks(spoken = malayalam).dropOf("Call അമ്മ about the rent"))
        assertEquals(AnswerDrop.OTHER_SCRIPT, AnswerChecks(spoken = english).dropOf("Call അമ്മ about the rent"))
        // A theme's summary reads no speech: the share alone decides.
        assertEquals(null, AnswerChecks().dropOf("Call അമ്മ about the rent"))
    }
}
