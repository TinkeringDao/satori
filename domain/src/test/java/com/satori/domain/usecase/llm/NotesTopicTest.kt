package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.usecase.sync.SyncSttLlmUseCase
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The notes prompts give a recording's topic, or its name for a rewrite, to the model above what
 * was said in the user message, never in the system prompt: that is the same for every recording
 * of a kind and language, so the engine can keep its encoded state, and keeps nothing of one. The
 * prompts' lead-in to what was said follows the topic, so the topic doesn't read as said.
 */
class NotesTopicTest {

    /** The app's topic sentences and lead-ins, with the mode prompts and the German keys of the other doubles. */
    private val resources = object : ResourceProvider by LocalizedResourceProvider() {
        override fun getTopicPrefix(localeCode: String) =
            if (localeCode.startsWith("de")) "Das Thema ist \"%1\$s\"." else "The topic is \"%1\$s\"."

        override fun getTextLeadIn(localeCode: String) =
            if (localeCode.startsWith("de")) "Was gesagt wurde:" else "What was said:"
    }

    /** What a notes call's user message starts with for [topic] in English, up to what was said. */
    private fun above(topic: String) = "The topic is \"$topic\".\n\nWhat was said:\n"

    private val talk = "We agreed to move the launch to March, and the team will update the plan " +
        "before Friday so that everyone can review it on Monday morning."
    private val germanTalk = "Wir haben vereinbart, den Start auf März zu verschieben, und das Team aktualisiert " +
        "den Plan vor Freitag, damit ihn alle am Montagmorgen prüfen können."
    private val answer = "{\"title\": \"Launch moves to March\", \"points\": [\"The launch moves to March.\"]}"

    // Twelve lines of about 500 characters: two parts and a merge.
    private val longTalk = (1..12).map {
        segment("The workshop got a new bench, and the old shelves go to the basement next week. ".repeat(6), at = it.toLong())
    }

    // The settings hold the prompts of the app language, sent while no language is known.
    private val appLanguage = AppSettings(shortMeetingSystemPrompt = "short_meeting prompt (app language)")

    private fun finalNote(llm: FakeLlmRepository, topic: String?, language: String? = "en", text: String = talk) = runBlocking {
        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(appLanguage), resources)(
            pendingText = text, sessionId = "s1", mode = RecordingMode.SHORT_MEETING, outputLanguage = language, topic = topic
        )
    }

    @Test
    fun `the last note's system prompt is the same whatever the topic, which goes above the lead-in to what was said`() {
        val llm = FakeLlmRepository(answer = answer)

        finalNote(llm, topic = "Launch plan")
        finalNote(llm, topic = "Garden shed")
        finalNote(llm, topic = null)

        assertEquals(List(3) { "short_meeting prompt (en)" }, llm.prompts.map { it.second })
        assertEquals(
            listOf(above("Launch plan") + talk, above("Garden shed") + talk, talk),
            llm.prompts.map { it.first }
        )
    }

    @Test
    fun `the topic's sentence and the lead-in are in the notes' language, and English while none is known`() {
        val llm = FakeLlmRepository(answer = "{\"titel\": \"Start im März\", \"punkte\": [\"Der Start ist im März.\"]}")

        finalNote(llm, topic = "Startplan", language = "de", text = germanTalk)
        finalNote(llm, topic = "Startplan", language = null)

        assertEquals("Das Thema ist \"Startplan\".\n\nWas gesagt wurde:\n$germanTalk", llm.prompts[0].first)
        assertEquals("short_meeting prompt (de)", llm.prompts[0].second)
        assertEquals(above("Startplan") + talk, llm.prompts[1].first)
        // No language known: the app language's prompt from the settings, nothing added to it.
        assertEquals("short_meeting prompt (app language)", llm.prompts[1].second)
    }

    @Test
    fun `every part and the merge carry the topic, under the system prompt another recording gets`() = runBlocking {
        // Each part its own note: since 0.7.3 a part that repeats the one before is none (NoteRepeats).
        val llm = FakeLlmRepository(
            answers = listOf(
                "{\"title\": \"Workshop tidy-up\", \"points\": [\"Move the old shelves to the basement.\"], \"action_items\": []}",
                "{\"title\": \"New bench\", \"points\": [\"Set up the new bench by the window.\"], \"action_items\": []}",
                "{\"title\": \"Workshop tidy-up\", \"points\": [\"Move the old shelves to the basement.\"], \"action_items\": []}"
            )
        )
        val batch = GenerateBatchInsightUseCase(llm, FakeSettingsRepository(), resources)

        batch(longTalk, "s1", RecordingMode.SHORT_MEETING, outputLanguage = "en", topic = "Workshop")
        batch(longTalk, "s2", RecordingMode.SHORT_MEETING, outputLanguage = "en", topic = "Basement")

        // Two parts and a merge for each recording.
        assertEquals(List(6) { "short_meeting prompt (en)" }, llm.prompts.map { it.second })
        val (first, second) = llm.prompts.map { it.first }.chunked(3)
        assertTrue(first.all { it.startsWith(above("Workshop")) })
        assertTrue(second.all { it.startsWith(above("Basement")) })
        // The first part is the first ten lines, 4,800 characters.
        assertEquals(
            above("Workshop") + longTalk.take(10).joinToString(" ") { it.text.trim() },
            first[0]
        )
        val part = "Workshop tidy-up\n- Move the old shelves to the basement."
        assertEquals(above("Workshop") + "$part\n\n---\n\nNew bench\n- Set up the new bench by the window.", first[2])
    }

    @Test
    fun `a rewrite gives the recording's name with the text, and two recordings the same system prompt`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        val settings = FakeSettingsRepository(AppSettings(notesLanguage = NotesLanguage.EN))
        val said = listOf(talk, "The budget review is next week, and the licence list goes out to the whole team by Monday.")

        for ((id, name) in listOf("s1" to "Launch plan", "s2" to "Budget review")) {
            val lines = said.mapIndexed { index, text -> segment(text, at = index + 1L, sessionId = id) }
            val recording = FakeTranscriptionRepository(session(id, name).copy(mode = RecordingMode.SHORT_MEETING), lines)
            RewriteNotesUseCase(
                recording,
                settings,
                GenerateBatchInsightUseCase(llm, settings, resources),
                LoadFinishingModelUseCase(llm, FakeFinishingModelRepository())
            )(id).getOrThrow()
        }

        val text = said.joinToString(" ")
        assertEquals(
            listOf(above("Launch plan") + text, above("Budget review") + text),
            llm.prompts.map { it.first }
        )
        assertEquals(listOf("short_meeting prompt (en)", "short_meeting prompt (en)"), llm.prompts.map { it.second })
    }

    @Test
    fun `a live note's language comes from what was said alone, and the topic goes above it`() = runBlocking {
        val llm = FakeLlmRepository(answer = "{\"titel\": \"Raum\", \"punkte\": [\"Der Raum ist gebucht.\"]}")
        val read = mutableListOf<String>()
        val said = "Bitte den Raum für das Freitagstreffen buchen heute"

        SyncSttLlmUseCase(llm, FakeSettingsRepository(AppSettings(simpleListeningIntervalSeconds = 0)), resources)(
            transcriptionFlow = flowOf(segment(said, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            topic = "Team meeting",
            notesLanguage = { text -> read += text; "de" }
        ).toList()

        assertEquals(listOf(said), read)
        assertEquals("Das Thema ist \"Team meeting\".\n\nWas gesagt wurde:\n$said", llm.prompts.single().first)
        assertEquals("simple_listening prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `a translation gets no topic, which its model would translate with the rest`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Wir verschieben den Start auf März.")
        val translation = AppSettings(translationIntervalSeconds = 0, translationSystemPrompt = "Translate into {target_language}.")

        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(translation), resources)(
            pendingText = talk, sessionId = "s1", mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de", topic = "Launch plan"
        )
        SyncSttLlmUseCase(llm, FakeSettingsRepository(translation), resources)(
            transcriptionFlow = flowOf(segment(talk, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "de",
            topic = "Launch plan"
        ).toList()

        assertEquals(listOf(talk, talk), llm.prompts.map { it.first })
        assertEquals(listOf("Translate into German.", "Translate into German."), llm.prompts.map { it.second })
    }

    @Test
    fun `a blank topic adds nothing, and leaves no instructions to repeat`() {
        for (topic in listOf(" ", null)) {
            val prompt = NotesTopic.prompt(talk, topic, RecordingMode.SHORT_MEETING, "en", resources)
            assertEquals(talk, prompt.text)
            assertTrue(prompt.instructions.isEmpty())
        }
    }

    @Test
    fun `a topic's instructions are its sentence without the topic and the lead-in`() {
        val prompt = NotesTopic.prompt(talk, "Launch plan", RecordingMode.SHORT_MEETING, "en", resources)

        assertEquals(above("Launch plan") + talk, prompt.text)
        // The topic is the recording's: a note may say it again.
        assertEquals(listOf("The topic is \"\".", "What was said:"), prompt.instructions)
    }

    @Test
    fun `a call asked again ends its line of instructions with the order to write about the subject`() {
        val prompt = NotesTopic.prompt(talk, "Launch plan", RecordingMode.SHORT_MEETING, "en", resources, retry = true)
        val order = "Instruction, never to be repeated in your answer: " +
            "write what is said about the subject, never about this recording, its text or its language."

        // Under the topic and above the lead-in, as the line on a text's language goes.
        assertEquals("The topic is \"Launch plan\".\n$order\n\nWhat was said:\n$talk", prompt.text)
        // The name of the notes' language, which "its language" stands for, is never sent but is an instruction's word.
        assertEquals(listOf("The topic is \"\".", order, "What was said:", "English"), prompt.instructions)
        assertFalse(prompt.text.contains("English"))
        assertEquals("Deutsch", NotesTopic.prompt(germanTalk, null, RecordingMode.SHORT_MEETING, "de", ShippedPrompts(), retry = true).instructions.last())
        // A first call has no such word.
        assertEquals(listOf("The topic is \"\".", "What was said:"), NotesTopic.prompt(talk, "Launch plan", RecordingMode.SHORT_MEETING, "en", resources).instructions)
        // The orders to read another language come first.
        val german = NotesTopic.prompt(germanTalk, null, RecordingMode.SHORT_MEETING, "en", resources, retry = true)
        assertEquals(
            "Instruction, never to be repeated in your answer: read the German below and write in English. " +
                "Write what is said about the subject, never about this recording, its text or its language.",
            german.instructions.first()
        )
        // A translation, which no answer is filtered for, gets none.
        assertEquals(talk, NotesTopic.prompt(talk, null, RecordingMode.REAL_TIME_TRANSLATION, "de", resources, retry = true).text)
    }

    @Test
    fun `the order of a call asked again is the app's own, in both languages`() {
        assertEquals(ResourceProvider.NOTES_RETRY, shipped("values", "prompt_notes_retry"))
        assertEquals(
            "Festhalten, was über das Thema gesagt wird, nie etwas über diese Aufnahme, ihren Text oder ihre Sprache.",
            shipped("values-de", "prompt_notes_retry")
        )
    }

    @Test
    fun `the lead-in under a topic is the last line of every notes prompt, in both languages`() {
        for (folder in listOf("values", "values-de")) {
            val leadIn = shipped(folder, "session_text_lead_in")
            for (prompt in listOf("prompt_simple_listening", "prompt_short_meeting", "prompt_long_meeting")) {
                assertEquals("$folder: $prompt", leadIn, shipped(folder, prompt).lines().last())
            }
        }
        assertEquals(ResourceProvider.TEXT_LEAD_IN, shipped("values", "session_text_lead_in"))
    }

    /**
     * The string [name] of the app's res/[folder]/strings_grille_ai.xml, with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder, next to the app's.
     */
    private fun shipped(folder: String, name: String): String {
        val file = File("../app/src/main/res/$folder/strings_grille_ai.xml")
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        val raw = (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == name }.textContent
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\'", "'")
    }
}
