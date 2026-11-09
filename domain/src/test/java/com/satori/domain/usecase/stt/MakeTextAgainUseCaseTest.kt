package com.satori.domain.usecase.stt

import com.satori.domain.fakes.FakeFinishingModelRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.LARGE_MODEL
import com.satori.domain.fakes.TwoModelLlmRepository
import com.satori.domain.fakes.insight
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.LanguageDetector
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SegmentReading
import com.satori.domain.model.SpeechModelId
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoCapture
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.finish.LinesNeverReadException
import com.satori.domain.usecase.llm.FinishingModel
import com.satori.domain.usecase.llm.GenerateBatchInsightUseCase
import com.satori.domain.usecase.llm.GenerateSessionTitleUseCase
import com.satori.domain.usecase.llm.LoadFinishingModelUseCase
import com.satori.domain.usecase.llm.RewriteNotesUseCase
import com.satori.domain.usecase.todo.ExtractSessionTodosUseCase
import com.satori.domain.usecase.todo.ExtractTodosUseCase
import com.satori.domain.usecase.todo.FakeTimeParser
import com.satori.domain.usecase.todo.FoundTodosLog
import com.satori.domain.usecase.todo.ScriptedLlmRepository
import com.satori.domain.usecase.todo.TodoActions
import com.satori.domain.usecase.transcription.AutoNameSessionUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcribe again that changes a recording's language has its text made again from the new
 * transcript (brief item 2, the phone's case of 2026-10-15): a Malayalam note that Parakeet v3 had
 * made gibberish of was named after the gibberish and given an idea by it; made again in Malayalam,
 * its notes were rewritten with that name as their topic, and the name and the idea stayed. Here the
 * notes are written without the old name, the name is written again, the to-dos and ideas are
 * found again in English, and the old idea gives way to them while a to-do the owner kept stays.
 * Each piece of the old text goes only where what replaces it is ready (the 0.6.1 memory review):
 * a run stopped in any step, or one whose model doesn't load, leaves the rest as it was. Since 0.8.0
 * the Malayalam lines come with their English readings, which the page makes first (design R6): the
 * text is made from them as an English recording's is, the spotter included, and a transcript with a
 * line never read changes nothing. The sentences and readings are made up, of the shape of the
 * owner's.
 */
class MakeTextAgainUseCaseTest {

    // Tomorrow at ten I must go to the bank and hand over the loan papers; before that look over all the papers once more.
    private val bank = "നാളെ രാവിലെ പത്തു മണിക്ക് ബാങ്കിൽ പോയി ലോണിന്റെ രേഖകൾ കൊടുക്കണം, അതിനു മുമ്പ് എല്ലാ പേപ്പറുകളും ഒന്നുകൂടി നോക്കണം."

    // On the way home in the evening don't forget to buy medicine for mother, the pharmacy closes at eight.
    private val medicine = "വൈകുന്നേരം വീട്ടിലേക്ക് വരുമ്പോൾ അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്, ഫാർമസി എട്ടു മണിക്ക് അടയ്ക്കും."

    // Then next week call the gardener and talk about the wall.
    private val gardener = "പിന്നെ അടുത്ത ആഴ്ച തോട്ടത്തിലെ പണിക്കാരനെ വിളിച്ച് മതിലിന്റെ കാര്യം സംസാരിക്കണം."

    // What Parakeet v3 made of the same speech: Latin letters, no clear language.
    private val gibberish = listOf(
        segment("Uh a lay the porch in a key run on the wall here, then the marrow of a cotton.", at = 1_000L),
        segment("Ah no para in the kettle so they can say them all, a key run.", at = 2_000L),
        segment("It is the marrow of a cotton seen as I go by the wall.", at = 3_000L)
    )

    private val malayalam = listOf(segment(bank, at = 1_000L), segment(medicine, at = 2_000L), segment(gardener, at = 3_000L))

    private val note = "Instruction, never to be repeated in your answer: read the Malayalam below and write in English. " +
        "Read English words written in Malayalam letters as English."

    // What the gibberish gave the note.
    private val oldName = "Workflow Kettle Challenges"
    private val oldNote = insight("Workflow challenges", "- The workflow has problems.\n- The kettle needs a plan.", at = 4_000L)
    private val oldIdea = Todo(
        id = "idea", sessionId = "s1", text = "No para in the kettle", status = TodoStatus.IDEA, capture = TodoCapture.INFERRED,
        sourceSegmentIds = listOf("seg2000"), createdAt = 5_000L, updatedAt = 5_000L
    )

    // A suggestion of the old transcript the owner kept.
    private val kept = Todo(
        id = "kept", sessionId = "s1", text = "Water the garden", status = TodoStatus.OPEN, capture = TodoCapture.INFERRED,
        sourceSegmentIds = listOf("seg3000"), createdAt = 5_000L, updatedAt = 5_000L + 120_000L
    )

    /** The app's sentences for the topic and the lead-in, as it ships them in English. */
    private val resources = object : ResourceProvider by FakeResourceProvider() {
        override fun getTopicPrefix(localeCode: String) = "The topic is \"%1\$s\"."
        override fun getTextLeadIn(localeCode: String) = "What was said:"
        override fun getTodoExtractionPrompt(localeCode: String) = "todo prompt ($localeCode)"
    }

    // The owner writes notes in German; a Malayalam recording's are English all the same.
    private val settings = FakeSettingsRepository(AppSettings(notesLanguage = NotesLanguage.DE))

    private val drops = mutableListOf<Pair<Int, FoundTodosLog.Why>>()

    // What the field log hears of each to-do taken away.
    private val changes = ChangeLines()

    private val notesAnswer = "{\"title\": \"Bank papers and medicine\", \"points\": " +
        "[\"Hand over the loan papers at the bank tomorrow at ten.\", \"Buy medicine for mother on the way home.\"]}"

    private val todosAnswer = """{"items": [""" +
        """{"segment": "S1", "type": "task", "said": "ബാങ്കിൽ പോയി ലോണിന്റെ രേഖകൾ കൊടുക്കണം", "text": "Hand over the loan papers at the bank", "when": "none", "capture": "commitment"}, """ +
        """{"segment": "S2", "type": "task", "said": "അമ്മയ്ക്ക് മരുന്ന് വാങ്ങാൻ മറക്കരുത്", "text": "Buy medicine for mother", "when": "none", "capture": "explicit"}, """ +
        // A line copied as it was said, in Malayalam script: no item.
        """{"segment": "S3", "type": "idea", "said": "പണിക്കാരനെ വിളിച്ച്", "text": "$gardener"}""" +
        "]}"

    /** The use case over [recordings] and [todos], on [llm], with Gemma 4 E2B at [finishingModel] if any. */
    private fun makeAgain(
        recordings: FakeTranscriptionRepository,
        todos: HeldTodos,
        llm: LlmRepository,
        finishingModel: String? = null
    ): MakeTextAgainUseCase {
        val recording = HeldRecording(recordings)
        val load = LoadFinishingModelUseCase(llm, FakeFinishingModelRepository(path = finishingModel))
        val language = NotesLanguageResolver(settings, recording)
        return MakeTextAgainUseCase(
            transcriptionRepository = recording,
            rewriteNotes = RewriteNotesUseCase(recording, settings, GenerateBatchInsightUseCase(llm, settings, resources), load),
            autoNameSession = AutoNameSessionUseCase(recording, GenerateSessionTitleUseCase(llm, resources)),
            extractSessionTodos = ExtractSessionTodosUseCase(
                settings,
                recording,
                todos,
                ExtractTodosUseCase(llm, resources, FakeTimeParser(), now = { 9_000L }, foundLog = { _, count, why -> drops += count to why }),
                Reminders(),
                language
            ),
            loadFinishingModel = load,
            notesLanguage = language,
            todoRepository = todos,
            todoActions = TodoActions(todos, Reminders(), changes)
        )
    }

    // The reader's English of the three lines, made up as they are.
    private val readings = mapOf(
        bank to "Tomorrow morning at ten I have to go to the bank and hand over the loan papers, and before that look over all the papers once more.",
        medicine to "When coming home in the evening, don't forget to buy medicine for mother; the pharmacy closes at eight.",
        gardener to "Then next week I have to call the gardener and talk about the wall."
    )

    /**
     * The recording as Transcribe again leaves it once the Malayalam model's transcript is in, with the
     * readings of its lines the page makes before it makes the text again (EnsureReadingsUseCase, since
     * 0.8.0); with [read] false, none.
     */
    private fun transcribedAgainInMalayalam(recordings: FakeTranscriptionRepository, lines: List<TranscriptionSegment> = malayalam, read: Boolean = true) {
        recordings.segments = lines
        recordings.session = recordings.session?.copy(language = RecordingLanguage.MALAYALAM, transcriptModel = SpeechModelId.INDICCONFORMER_ML.id)
        recordings.readings = if (!read) emptyList() else lines.map { line ->
            SegmentReading(line.id, line.sessionId, SegmentReading.sourceHashOf(line.text), readings.getValue(line.text), model = "reader", score = -0.4, createdAt = 1L)
        }
    }

    /** The recording with the gibberish's name, note and idea, and the to-do the owner kept, made Malayalam. */
    private fun gibberishMadeMalayalam(): Pair<FakeTranscriptionRepository, HeldTodos> {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        transcribedAgainInMalayalam(recordings)
        return recordings to HeldTodos(oldIdea, kept)
    }

    @Test
    fun `a gibberish English name and idea give way to an English name, notes and to-dos of the Malayalam transcript`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        val todos = HeldTodos(oldIdea, kept)
        val llm = ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer))

        // The sheet's rule: the Malayalam model makes this English or German recording's text again.
        assertTrue(
            SpeechModelChoice.makesTextAgain(
                SpeechModelId.INDICCONFORMER_ML,
                RecordingLanguage.ENGLISH_OR_GERMAN,
                TranscriptLanguage.isIn(RecordingLanguage.ENGLISH_OR_GERMAN, gibberish)
            )
        )
        transcribedAgainInMalayalam(recordings)
        val made = makeAgain(recordings, todos, llm)("s1").getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.Written, made.notes)
        assertTrue(made.name)
        // The readings are English, so the spotter reads them too (0.8.0): the gardener's call as well.
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(count = 3), made.todos)
        assertEquals(MakeTextAgainUseCase.OldFinds(removed = 1, kept = 1), made.oldFinds)

        // The name is written again, in English, in place of the old one.
        assertEquals("Bank Papers and Medicine", recordings.session?.name)
        // The notes are written in English, without the old name as their topic.
        assertEquals(listOf("Bank papers and medicine"), recordings.insights.map { it.title })
        val (notesCall, notesPrompt) = llm.prompts[0]
        assertEquals("simple_listening prompt (en)", notesPrompt)
        assertFalse(notesCall, notesCall.contains(oldName))
        assertFalse(notesCall, notesCall.contains("The topic is"))
        // The readings as they are, as an English recording's text goes: no line on reading Malayalam (D6).
        assertEquals(malayalam.joinToString(" ") { readings.getValue(it.text) }, notesCall)
        assertTrue(llm.prompts.none { (text, _) -> text.contains(note) || LanguageDetector.isMalayalam(text) })
        assertEquals("title prompt (en)", llm.prompts[1].second)
        assertEquals("todo prompt (en)", llm.prompts[2].second)
        // The old idea was no repeat for the search, which didn't see it as noted.
        assertFalse(llm.prompts[2].first, llm.prompts[2].first.contains(oldIdea.text))
        assertTrue(llm.prompts[2].first, llm.prompts[2].first.contains(kept.text))
        // The to-dos are found again: the old idea goes, the to-do the owner kept stays, the new ones are English.
        assertEquals(
            listOf("Water the garden", "Hand over the loan papers at the bank", "Buy medicine for mother", "Call the gardener"),
            todos.stored.filter { it.sessionId == "s1" }.map { it.text }
        )
        assertTrue(todos.stored.none { LanguageDetector.isMalayalam(it.text) })
        assertEquals(listOf("To-do idea: IDEA -> gone (transcribed again)"), changes.lines)
        assertEquals(listOf(1 to FoundTodosLog.Why.WRITTEN_IN_ANOTHER_SCRIPT), drops)
    }

    @Test
    fun `the untouched finds give way to the new ones, those the owner changed and other recordings' stay`() = runBlocking {
        val minute = 60_000L
        fun found(id: String, text: String, status: TodoStatus, changedAfterMs: Long = 0L, sessionId: String = "s1") = Todo(
            id = id, sessionId = sessionId, text = text, status = status, capture = TodoCapture.INFERRED,
            sourceSegmentIds = listOf("seg1000"), createdAt = 1_000L, updatedAt = 1_000L + changedAfterMs,
            completedAt = if (status == TodoStatus.DONE) 1_000L + changedAfterMs else null
        )
        val todos = HeldTodos(
            // What the gibberish gave it, untouched since; one changed within seconds of being found is too.
            found("idea", "No para in the kettle", TodoStatus.IDEA),
            found("suggestion", "Lay the porch", TodoStatus.SUGGESTED),
            found("open", "Run the key on the wall", TodoStatus.OPEN),
            found("just-found", "Weigh the cotton", TodoStatus.SUGGESTED, changedAfterMs = 3_000L),
            // The owner's: kept, ticked, edited.
            found("kept", "Water the garden", TodoStatus.OPEN, changedAfterMs = 2 * minute),
            found("ticked", "Pay the rent", TodoStatus.DONE, changedAfterMs = 30 * minute),
            found("edited", "Book the train to the coast", TodoStatus.OPEN, changedAfterMs = 10 * minute),
            // Another recording's, untouched.
            found("elsewhere", "Call the plumber", TodoStatus.OPEN, sessionId = "s2")
        )
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        transcribedAgainInMalayalam(recordings)
        val llm = ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", """{"items": []}"""))

        val made = makeAgain(recordings, todos, llm)("s1").getOrThrow()

        // The model found nothing, and what the spotter reads in the English readings (0.8.0) takes the old ones' place.
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(count = 3), made.todos)
        assertEquals(MakeTextAgainUseCase.OldFinds(removed = 4, kept = 3), made.oldFinds)
        assertEquals(listOf("kept", "ticked", "edited", "elsewhere"), todos.stored.take(4).map { it.id })
        assertEquals(3, todos.stored.drop(4).count { it.sessionId == "s1" })
        assertEquals(
            listOf(
                "To-do idea: IDEA -> gone (transcribed again)",
                "To-do suggestion: SUGGESTED -> gone (transcribed again)",
                "To-do open: OPEN -> gone (transcribed again)",
                "To-do just-found: SUGGESTED -> gone (transcribed again)"
            ),
            changes.lines
        )
    }

    @Test
    fun `a recording that starts while the notes are written leaves the old name, notes, to-dos and ideas`() = runBlocking {
        val (recordings, todos) = gibberishMadeMalayalam()
        val llm = HeldLlm(ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer)))
        val notes = llm.holdAt("simple_listening prompt (en)")

        val run = launch { makeAgain(recordings, todos, llm)("s1") }
        notes.await()
        // The quick key: the page's work is cancelled where it is.
        run.cancel()
        run.join()

        assertEquals(oldName, recordings.session?.name)
        assertEquals(listOf(oldNote), recordings.insights)
        assertEquals(listOf(oldIdea, kept), todos.stored)
        assertTrue(changes.lines.isEmpty())
    }

    @Test
    fun `a recording that starts while the name is written keeps the new notes, and the old name and finds`() = runBlocking {
        val (recordings, todos) = gibberishMadeMalayalam()
        val llm = HeldLlm(ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer)))
        val title = llm.holdAt("title prompt (en)")

        val run = launch { makeAgain(recordings, todos, llm)("s1") }
        title.await()
        run.cancel()
        run.join()

        assertEquals(listOf("Bank papers and medicine"), recordings.insights.map { it.title })
        assertEquals(oldName, recordings.session?.name)
        assertEquals(listOf(oldIdea, kept), todos.stored)
    }

    @Test
    fun `a search stopped before the model has answered keeps the old finds beside the new notes and name`() = runBlocking {
        val (recordings, todos) = gibberishMadeMalayalam()
        val llm = HeldLlm(ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer)))
        val search = llm.holdAt("todo prompt (en)")

        val run = launch { makeAgain(recordings, todos, llm)("s1") }
        search.await()
        run.cancel()
        run.join()

        assertEquals(listOf("Bank papers and medicine"), recordings.insights.map { it.title })
        assertEquals("Bank Papers and Medicine", recordings.session?.name)
        assertEquals(listOf(oldIdea, kept), todos.stored)
        assertTrue(changes.lines.isEmpty())
    }

    @Test
    fun `a model that doesn't load leaves the old name, notes, to-dos and ideas`() = runBlocking {
        val (recordings, todos) = gibberishMadeMalayalam()
        // The live model's load is stopped, as the paced load stops one when memory stays short.
        val llm = HeldLlm(ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer)), loads = false)

        val made = makeAgain(recordings, todos, llm)("s1").getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.Failed, made.notes)
        assertFalse(made.name)
        assertEquals(ExtractSessionTodosUseCase.Outcome.Failed, made.todos)
        assertEquals(MakeTextAgainUseCase.OldFinds(removed = 0, kept = 2), made.oldFinds)
        assertEquals(oldName, recordings.session?.name)
        assertEquals(listOf(oldNote), recordings.insights)
        assertEquals(listOf(oldIdea, kept), todos.stored)
        assertTrue(changes.lines.isEmpty())
    }

    @Test
    fun `an old find the owner ticks while the search runs stays`() = runBlocking {
        val (recordings, todos) = gibberishMadeMalayalam()
        val llm = HeldLlm(ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer)))
        val search = llm.holdAt("todo prompt (en)", resumes = false)

        val run = launch { makeAgain(recordings, todos, llm)("s1") }
        search.await()
        // Make it a to-do, as the ideas page does: its last change is stamped now.
        todos.upsert(oldIdea.copy(status = TodoStatus.OPEN, updatedAt = 600_000L))
        llm.resume()
        run.join()

        assertEquals(listOf("idea", "kept"), todos.stored.take(2).map { it.id })
        // The new finds: the model's two and the spotter's call of the gardener.
        assertEquals(5, todos.stored.size)
        assertTrue(changes.lines.isEmpty())
    }

    @Test
    fun `a transcript with too little text for notes drops the old ones, and the name comes from what it says`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = null), gibberish, listOf(oldNote))
        val todos = HeldTodos()
        val llm = ScriptedLlmRepository(listOf("Medicine for Mother", """{"items": []}"""))

        transcribedAgainInMalayalam(recordings, listOf(segment(medicine, at = 1_000L)))
        val made = makeAgain(recordings, todos, llm)("s1").getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.TooLittle, made.notes)
        assertTrue(recordings.insights.isEmpty())
        assertEquals("Medicine for Mother", recordings.session?.name)
        // Named from the Malayalam line's reading, as an English recording is, with no line on reading it (D6);
        // the old notes are no input.
        assertEquals("title prompt (en)", llm.prompts.first().second)
        assertNull(llm.leads.first())
        assertTrue(llm.prompts.first().first.contains(readings.getValue(medicine)))
        assertFalse(LanguageDetector.isMalayalam(llm.prompts.first().first))
        assertFalse(llm.prompts.first().first.contains("workflow"))
    }

    @Test
    fun `notes that fail leave the old ones, and the name is never taken from them`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        // The notes' call fails as a native error does; the name's and the search's answer.
        val llm = ScriptedLlmRepository(listOf(null, "Bank Papers and Medicine", """{"items": []}"""))

        transcribedAgainInMalayalam(recordings)
        val made = makeAgain(recordings, HeldTodos(), llm)("s1").getOrThrow()

        assertEquals(MakeTextAgainUseCase.Notes.Failed, made.notes)
        assertEquals(listOf(oldNote), recordings.insights)
        assertEquals("Bank Papers and Medicine", recordings.session?.name)
        val titleCall = llm.prompts.single { it.second == "title prompt (en)" }.first
        assertFalse(titleCall, titleCall.contains("workflow has problems"))
        assertTrue(titleCall, titleCall.contains(readings.getValue(bank)))
    }

    @Test
    fun `a name that can't be written leaves the old one`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        // The notes' call fails as a native error does, and so does the name's: no title, and no new note to take one from.
        val llm = ScriptedLlmRepository(listOf(null, null, """{"items": []}"""))

        transcribedAgainInMalayalam(recordings)
        val made = makeAgain(recordings, HeldTodos(), llm)("s1").getOrThrow()

        assertFalse(made.name)
        assertEquals(oldName, recordings.session?.name)
    }

    @Test
    fun `a name the owner types while the text is made again stays, and still steers no notes`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        val llm = ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", """{"items": []}"""))
        // Typed as the new name is written.
        recordings.nameTypedMeanwhile = "Errands for mum"

        transcribedAgainInMalayalam(recordings)
        val made = makeAgain(recordings, HeldTodos(), llm)("s1").getOrThrow()

        assertFalse(made.name)
        assertEquals("Errands for mum", recordings.session?.name)
        assertFalse(llm.prompts.first().first.contains("The topic is"))
        assertFalse(llm.prompts.first().first.contains(oldName))
    }

    @Test
    fun `the model for the work after Stop is put in memory once, for the notes, the name and the to-dos`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = null), gibberish, listOf(oldNote))
        val llm = TwoModelLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", """{"items": []}"""))
        val models = mutableListOf<FinishingModel>()

        transcribedAgainInMalayalam(recordings)
        makeAgain(recordings, HeldTodos(), llm, finishingModel = LARGE_MODEL)("s1", onModel = { models += it }).getOrThrow()

        assertEquals(listOf<FinishingModel>(FinishingModel.Switched), models)
        assertEquals(listOf("switch $LARGE_MODEL", "prompt $LARGE_MODEL", "prompt $LARGE_MODEL", "prompt $LARGE_MODEL"), llm.log)
    }

    @Test
    fun `a Translate recording keeps its translation`() = runBlocking {
        val recordings = FakeTranscriptionRepository(
            session(name = "Weekly planning").copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "en"),
            gibberish,
            listOf(oldNote)
        )
        val todos = HeldTodos(oldIdea)
        val llm = ScriptedLlmRepository(listOf(notesAnswer))

        val made = makeAgain(recordings, todos, llm)("s1")

        assertTrue(made.isFailure)
        assertTrue(llm.prompts.isEmpty())
        assertEquals(listOf(oldNote), recordings.insights)
        assertEquals("Weekly planning", recordings.session?.name)
        assertEquals(listOf(oldIdea), todos.stored)
    }

    @Test
    fun `a Malayalam transcript whose lines were never read changes nothing`() = runBlocking {
        val recordings = FakeTranscriptionRepository(session(name = oldName), gibberish, listOf(oldNote))
        val todos = HeldTodos(oldIdea, kept)
        val llm = ScriptedLlmRepository(listOf(notesAnswer, "Bank Papers and Medicine", todosAnswer))
        transcribedAgainInMalayalam(recordings, read = false)

        val made = makeAgain(recordings, todos, llm)("s1")

        // Never the model on the Malayalam, nor text of the rest in place of the old (D7): the page made no readings.
        assertTrue(made.exceptionOrNull() is LinesNeverReadException)
        assertTrue(llm.prompts.isEmpty())
        assertEquals(oldName, recordings.session?.name)
        assertEquals(listOf(oldNote), recordings.insights)
        assertEquals(listOf(oldIdea, kept), todos.stored)
        assertTrue(changes.lines.isEmpty())
    }

    @Test
    fun `a recording that is gone changes nothing`() = runBlocking {
        val recordings = FakeTranscriptionRepository(null)
        val todos = HeldTodos(oldIdea)

        val made = makeAgain(recordings, todos, ScriptedLlmRepository(listOf(notesAnswer)))("s1")

        assertTrue(made.isFailure)
        assertNull(recordings.session)
        assertEquals(listOf(oldIdea), todos.stored)
    }

    /**
     * [scripted]'s answers, each call waiting first where a test holds it ([holdAt], by its system
     * prompt) until it is resumed or cancelled; with [loads] false the model can't be put in memory,
     * as a load the phone's memory stopped.
     */
    private class HeldLlm(private val scripted: ScriptedLlmRepository, private val loads: Boolean = true) : LlmRepository by scripted {
        private val holds = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val resumed = CompletableDeferred<Unit>()
        private val resumes = mutableMapOf<String, Boolean>()

        /**
         * The call with [systemPrompt] waits: the deferred completes once it is there. It waits until
         * cancelled, or with [resumes] false until [resume].
         */
        fun holdAt(systemPrompt: String, resumes: Boolean = true): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also {
                holds[systemPrompt] = it
                this.resumes[systemPrompt] = resumes
            }

        fun resume() {
            resumed.complete(Unit)
        }

        override suspend fun reloadModel(): Result<Unit> =
            if (loads) scripted.reloadModel() else Result.failure(java.io.IOException("The load of live.gguf was stopped: MemFree stayed under 150 MB for 10 s"))

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> =
            generateInsight(text, systemPrompt, maxTokens, grammar, lead = null)

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?, lead: String?): Flow<String> = flow {
            holds[systemPrompt]?.let { reached ->
                reached.complete(Unit)
                if (resumes[systemPrompt] == false) resumed.await() else CompletableDeferred<Unit>().await()
            }
            emitAll(scripted.generateInsight(text, systemPrompt, maxTokens, grammar, lead))
        }
    }
}
