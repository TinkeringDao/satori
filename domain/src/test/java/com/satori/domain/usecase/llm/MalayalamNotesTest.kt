package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Malayalam transcript's notes and name are asked for in English, with the system prompts of
 * English, and the user message has a line of instructions to read the Malayalam, English words
 * in Malayalam letters as English, and to write in English; for the name also to give its topic in
 * 2 to 5 words. Its characters cost more tokens, so each call reads fewer of them.
 */
class MalayalamNotesTest {

    // Tomorrow morning call the doctor, then send the meeting's report (eight words).
    private val sentence = "നാളെ രാവിലെ ഡോക്ടറെ വിളിക്കണം, പിന്നെ മീറ്റിംഗിന്റെ റിപ്പോർട്ട് അയക്കണം."

    private val note = "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
        "Read English words written in Malayalam letters as English."

    private val topicOrder = "Name the topic of the text below in 2 to 5 words, in English."

    /** The lead-in the notes prompts end with, and the topic sentence, as the app ships them in English. */
    private val resources = object : ResourceProvider by FakeResourceProvider() {
        override fun getTopicPrefix(localeCode: String) = "The topic is \"%1\$s\"."
        override fun getTextLeadIn(localeCode: String) = "What was said:"
    }

    private val answer = "{\"title\": \"Doctor and report\", \"points\": [\"Call the doctor tomorrow morning.\"]}"

    /** [count] of the sentence, as one text. */
    private fun malayalam(count: Int) = List(count) { sentence }.joinToString(" ")

    @Test
    fun `the last note of a Malayalam tail is asked for in English, with the line of instructions on reading it`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        val text = malayalam(3)

        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(), resources)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        )
        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(), resources)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en", topic = "Errands"
        )

        assertEquals("simple_listening prompt (en)", llm.prompts[0].second)
        assertEquals("$note\n\nWhat was said:\n$text", llm.prompts[0].first)
        // The topic first, the instructions on a line of their own, both above the lead-in, so neither reads as said.
        assertEquals("The topic is \"Errands\".\n$note\n\nWhat was said:\n$text", llm.prompts[1].first)
    }

    @Test
    fun `a final note reads the last 3,600 characters of a Malayalam tail, as many tokens as 6,000 of English`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        val text = malayalam(80)

        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(), resources)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SIMPLE_LISTENING, outputLanguage = "en"
        )

        assertEquals(3_600, GenerateFinalInsightUseCase.maxUserPromptChars(text))
        assertEquals("$note\n\nWhat was said:\n${text.takeLast(3_600)}", llm.prompts.single().first)
    }

    @Test
    fun `the pass cuts a Malayalam transcript into smaller parts, each told so, and merges their English points as they are`() = runBlocking {
        // Each part its own note: since 0.7.3 a part that repeats one before it is none (NoteRepeats).
        val llm = FakeLlmRepository(
            answers = listOf(
                answer,
                "{\"title\": \"Meeting report\", \"points\": [\"Send the meeting's report.\"]}",
                "{\"title\": \"Morning plan\", \"points\": [\"Both before noon.\"]}",
                answer
            )
        )
        // Twelve lines of about 520 characters: as many as two parts of English, three of Malayalam.
        val lines = (1..12).map { segment(malayalam(7), at = it.toLong()) }
        val englishLines = (1..12).map { segment("The workshop got a new bench and the shelves go to the basement. ".repeat(8), at = it.toLong()) }

        GenerateBatchInsightUseCase(llm, FakeSettingsRepository(), resources)(lines, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en")
        val malayalamCalls = llm.prompts.map { it.first }
        llm.prompts.clear()
        GenerateBatchInsightUseCase(llm, FakeSettingsRepository(), resources)(englishLines, "s2", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en")
        val englishCalls = llm.prompts.map { it.first }

        // Three parts and the merge, against two parts and the merge.
        assertEquals(4, malayalamCalls.size)
        assertEquals(3, englishCalls.size)
        val parts = malayalamCalls.dropLast(1)
        assertTrue(parts.all { it.startsWith("$note\n\nWhat was said:\n") })
        assertTrue(parts.all { it.removePrefix("$note\n\nWhat was said:\n").length <= 3_000 })
        // The merge reads the parts' English titles and points: nothing to tell.
        assertTrue(malayalamCalls.last().startsWith("Doctor and report\n"))
    }

    @Test
    fun `a Malayalam transcript without notes is named in English, as its topic, the line before at most 1,800 of its characters`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Doctor and report"))
        val lines = (1..60).map { segment(sentence, at = it.toLong()) }

        val title = GenerateSessionTitleUseCase(llm, resources)(lines, insights = emptyList(), languageCode = "en").getOrThrow()

        assertEquals("Doctor and report", title)
        assertEquals("title prompt (en)", llm.prompts.single().second)
        assertEquals("$note $topicOrder", llm.leads.single())
        val transcript = lines.joinToString(" ") { it.text }
        assertEquals("$note $topicOrder\n\n${transcript.take(1_800)}", llm.prompts.single().first)
    }

    @Test
    fun `named from its English notes, a Malayalam recording is asked for its topic in 2 to 5 words, and nothing on reading Malayalam`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Doctor and report"))
        val lines = (1..6).map { segment(sentence, at = it.toLong()) }
        val notes = listOf(insight("Doctor and report", "- Call the doctor tomorrow morning.\n- Send the report of the meeting.", at = 9L))

        GenerateSessionTitleUseCase(llm, resources)(lines, notes, languageCode = "en")

        assertEquals(
            "Instruction, never to be repeated in your answer: name the topic of the text below in 2 to 5 words, in English.",
            llm.leads.single()
        )
        assertEquals(
            "${llm.leads.single()}\n\n- Doctor and report: Call the doctor tomorrow morning; Send the report of the meeting",
            llm.prompts.single().first
        )
    }

    @Test
    fun `an English recording's name gets no line of instructions`() = runBlocking {
        val llm = ScriptedLlmRepository(listOf("Doctor and report"))
        val lines = listOf(segment("Tomorrow morning I need to call the doctor and then send the report of the meeting.", at = 1L))

        GenerateSessionTitleUseCase(llm, resources)(lines, insights = emptyList(), languageCode = "en")

        assertNull(llm.leads.single())
    }
}
