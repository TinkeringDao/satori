package com.satori.domain.usecase.stt

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SpeechModelId
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.NotesLog
import com.satori.domain.usecase.llm.PromptEchoLog
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.llm.ShippedPrompts
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Make text again and Rewrite notes of a recording about a notes app (0.7.2): its points about
 * notes, recordings and speech stay, and a generic first name gets the second try from the new
 * transcript. In 0.7.1 Make text again ended "notes failed" with the old notes and name in place,
 * and an English Rewrite notes under the recording's name failed with "The model wrote no notes".
 * The prompts are the app's own; the lines and answers are made up.
 */
class TextAgainSubjectTest {

    private val strings = ShippedPrompts()
    private val settings = FakeSettingsRepository(AppSettings())
    private val heard = mutableListOf<String>()
    private val echoes = PromptEchoLog { id, n, what -> heard += PromptEchoLog.line(id, n, what) }
    private val notesLog = NotesLog { heard += NotesLog.line(it) }

    private val malayalam = listOf(
        segment("ഈ ആപ്പ് മീറ്റിംഗുകൾ റെക്കോർഡ് ചെയ്യും, പിന്നെ നോട്ട്സും മിനിറ്റ്സും തരും.", at = 1_000L),
        segment("ഇതിന്റെ എഐ ഫോണിൽ തന്നെ ഓഫ്‌ലൈനായി പ്രവർത്തിക്കും, ഇന്റർനെറ്റ് വേണ്ട.", at = 2_000L),
        segment("എഴുതാൻ ബുദ്ധിമുട്ടുള്ള ആളുകൾക്ക് ഇത് വലിയ സഹായമാണ്, അവർക്ക് സംസാരിച്ചാൽ മതി.", at = 3_000L)
    )

    // The reader's English of the three lines (0.8.0), made up as they are: since R6 the text is made from them.
    private val read = listOf(
        "This app records meetings, then gives notes and minutes.",
        "Its AI works offline on the phone itself; no internet is needed.",
        "It is a big help for people who find writing hard, as all they have to do is speak to it."
    )

    // What Parakeet v3 made of it before: Latin letters, no clear language (made up).
    private val oldNote = insight("Workflow kettle", "- The kettle has a plan.", at = 4_000L)

    private val subjectPoints = """{"title": "Voice Notes and Minutes", "points": ["AI writes the notes.", "Speech to text.", "Notes from recordings."]}"""

    private fun makeAgain(recordings: FakeTranscriptionRepository, llm: ScriptedLlmRepository): MakeTextAgainUseCase {
        val recording = HeldRecording(recordings)
        val load = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository())
        val language = NotesLanguageResolver(settings, recording)
        val todos = HeldTodos()
        return MakeTextAgainUseCase(
            transcriptionRepository = recording,
            rewriteNotes = RewriteNotesUseCase(recording, settings, GenerateBatchInsightUseCase(llm, settings, strings, echoLog = echoes, notesLog = notesLog), load),
            autoNameSession = AutoNameSessionUseCase(recording, GenerateSessionTitleUseCase(llm, strings, echoes, notesLog), notesLog),
            extractSessionTodos = ExtractSessionTodosUseCase(
                settings, recording, todos, ExtractTodosUseCase(llm, strings, FakeTimeParser(), now = { 9_000L }), Reminders(), language
            ),
            loadFinishingModel = load,
            notesLanguage = language,
            todoRepository = todos,
            todoActions = TodoActions(todos, Reminders(), ChangeLines())
        )
    }

    @Test
    fun `made again in Malayalam, its points about notes and recordings are the new notes and a name in the subject's words replaces the old`() = runBlocking {
        val recordings = FakeTranscriptionRepository(
            session(name = "Workflow Kettle Challenges").copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id),
            malayalam,
            listOf(oldNote)
        ).apply {
            readings = malayalam.mapIndexed { i, line ->
                SegmentReading(line.id, line.sessionId, SegmentReading.sourceHashOf(line.text), read[i], model = "reader", score = -0.4, createdAt = 1L)
            }
        }
        // The first name is generic for these notes; the second, from the transcript, is the subject's.
        val llm = ScriptedLlmRepository(listOf(subjectPoints, "Meeting Notes & Minutes", "Offline Meeting App", """{"items": []}"""))

        val made = makeAgain(recordings, llm)("s1").getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.Written, made.notes)
        assertTrue(made.name)
        assertEquals(listOf("- AI writes the notes.\n- Speech to text.\n- Notes from recordings."), recordings.insights.map { it.content })
        assertEquals("Offline Meeting App", recordings.session?.name)
        assertEquals(
            listOf(
                "Dropped 1 note titles that name only the kind of text in s1",
                "Dropped 1 names that name only the kind of text in s1",
                "Name of s1: asking once more, from its transcript",
                "Name of s1: from its transcript, after 2 answers"
            ),
            heard
        )
    }

    @Test
    fun `an English recording's Rewrite notes under its name keeps the same points`() = runBlocking {
        val english = listOf(
            segment("This app records meetings and gives notes and minutes, and its AI runs offline on the phone.", at = 1_000L),
            segment("It helps people who find writing hard, they only have to speak and the notes are written for them.", at = 2_000L),
            segment("Everything stays on the phone, so it also works on a plane or in the basement.", at = 2_500L)
        )
        val recordings = FakeTranscriptionRepository(session(name = "Offline meeting app"), english, listOf(insight("Old", "- Old point.", at = 3_000L)))
        val llm = ScriptedLlmRepository(listOf(subjectPoints))
        val rewrite = RewriteNotesUseCase(
            recordings, settings, GenerateBatchInsightUseCase(llm, settings, strings, echoLog = echoes, notesLog = notesLog),
            LoadFinishingModelUseCase(llm, FakeFinishingModelRepository())
        )

        assertTrue(rewrite("s1").isSuccess)
        assertTrue(llm.prompts.first().first.startsWith("The topic is \"Offline meeting app\"."))
        assertEquals(1, llm.prompts.size)
        assertEquals(listOf("- AI writes the notes.\n- Speech to text.\n- Notes from recordings."), recordings.insights.map { it.content })
    }
}
