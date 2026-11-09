package com.satori.domain.usecase.todo

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.segment
import com.satori.domain.fakes.session
import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.Todo
import com.satori.domain.model.TodoStatus
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.repository.TodoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractSessionTodosUseCaseTest {

    private val bank = segment("Remind me to call the bank about the loan.", at = 1_000L)
    private val plumber = segment("And I need to ask the plumber about the leaking tap.", at = 2_000L)

    private fun answer(vararg items: Pair<String, String>) =
        """{"todos": [${items.joinToString(", ") { (id, text) ->
            """{"segment": "$id", "text": "$text", "when": "none", "kind": "task", "capture": "explicit", "certainty": "confirmed"}"""
        }}]}"""

    private class Setup(
        llm: LlmRepository,
        settings: AppSettings = AppSettings(),
        segments: List<TranscriptionSegment>,
        val todos: StoredTodos = StoredTodos(),
        val scheduler: CountingScheduler = CountingScheduler(),
        recording: TranscriptionSession? = session(),
        store: TodoRepository = todos
    ) {
        val repository = FakeTranscriptionRepository(recording, segments)
        private val settingsRepository = FakeSettingsRepository(settings)
        private var ids = 0
        val useCase = ExtractSessionTodosUseCase(
            settingsRepository,
            repository,
            store,
            ExtractTodosUseCase(llm, TodoResources(), FakeTimeParser(), now = { 5L }, newId = { "todo-${++ids}" }),
            scheduler,
            NotesLanguageResolver(settingsRepository, repository) { "en" }
        )
    }

    @Test
    fun `stores the to-dos found and arms the reminders once`() = runBlocking {
        val setup = Setup(
            FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan", "S2" to "Ask the plumber about the tap")),
            segments = listOf(bank, plumber)
        )
        val outcome = setup.useCase("s1")
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(2), outcome)
        assertEquals(
            listOf("Call the bank about the loan", "Ask the plumber about the tap"),
            setup.todos.stored.map { it.text }
        )
        assertTrue(setup.todos.stored.all { it.sessionId == "s1" && it.status == TodoStatus.OPEN })
        assertEquals(1, setup.scheduler.calls)
    }

    @Test
    fun `a Translate recording's to-dos are written in its target language, from the German, with no German one of the spotter`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = """{"items": [{"segment": "S1", "type": "task", "said": "das Mittagessen für den Ausflug zu bestellen", """ +
                """"text": "Order lunch for the trip", "when": "none", "capture": "explicit"}]}"""
        )
        val lunch = segment("Erinnere mich daran, morgen früh das Mittagessen für den Ausflug zu bestellen.", at = 1_000L)
        val translation = session().copy(mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "en")
        val setup = Setup(llm, segments = listOf(lunch), recording = translation)

        setup.useCase("s1")

        assertEquals(listOf("Order lunch for the trip"), setup.todos.stored.map { it.text })
        val (text, system) = llm.prompts.single()
        assertEquals("todo prompt (en)", system)
        assertTrue(text, text.contains("read the German below and write in English."))
        assertTrue(text, text.contains(ResourceProvider.TODO_SAID))
    }

    @Test
    fun `does nothing while the AI is off`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank"))
        val setup = Setup(llm, settings = AppSettings(llmEnabled = false), segments = listOf(bank))
        assertEquals(ExtractSessionTodosUseCase.Outcome.Skipped, setup.useCase("s1"))
        assertTrue(llm.prompts.isEmpty())
        assertEquals(0, llm.reloads)
        assertTrue(setup.todos.stored.isEmpty())
        assertEquals(0, setup.scheduler.calls)
    }

    @Test
    fun `does nothing when nothing complete was said`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank"))
        val partial = segment("Remind me to call", at = 1L, complete = false)
        val setup = Setup(llm, segments = listOf(partial))
        assertEquals(ExtractSessionTodosUseCase.Outcome.Skipped, setup.useCase("s1"))
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `does nothing for a recording that is gone`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank"))
        val setup = Setup(llm, segments = listOf(bank), recording = null)
        assertEquals(ExtractSessionTodosUseCase.Outcome.Skipped, setup.useCase("s1"))
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a model that fails still stores the to-dos said outright, with no error`() = runBlocking {
        val llm = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        val setup = Setup(llm, segments = listOf(bank))
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(1), setup.useCase("s1"))
        assertEquals(listOf("Call the bank about the loan"), setup.todos.stored.map { it.text })
        assertEquals(1, setup.scheduler.calls)
    }

    @Test
    fun `a model that cannot load still stores the to-dos said outright, with no error`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank"), reloadFailure = IllegalStateException("no model"))
        val setup = Setup(llm, segments = listOf(bank))
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(1), setup.useCase("s1"))
        assertEquals(listOf("Call the bank about the loan"), setup.todos.stored.map { it.text })
        assertTrue(llm.prompts.isEmpty())
    }

    @Test
    fun `a store that fails keeps what was stored, arms its reminders and reports it`() = runBlocking {
        val todos = StoredTodos(failAfter = 1)
        val setup = Setup(
            FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan", "S2" to "Ask the plumber about the tap")),
            segments = listOf(bank, plumber),
            todos = todos
        )
        assertEquals(ExtractSessionTodosUseCase.Outcome.Failed, setup.useCase("s1"))
        assertEquals(listOf("Call the bank about the loan"), todos.stored.map { it.text })
        assertEquals(1, setup.scheduler.calls)
    }

    @Test
    fun `an alarm that cannot be armed does not fail the search`() = runBlocking {
        val setup = Setup(
            FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan")),
            segments = listOf(bank),
            scheduler = CountingScheduler(failWith = IllegalStateException("no alarm permission"))
        )
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(1), setup.useCase("s1"))
        assertEquals(1, setup.scheduler.calls)
    }

    @Test
    fun `cancellation is passed on and stores nothing`() {
        val cancelled = object : LlmRepository by FakeLlmRepository() {
            override fun generateInsight(
                text: String,
                systemPrompt: String?,
                maxTokens: Int,
                grammar: OutputGrammar?
            ): Flow<String> = flow { throw CancellationException("the screen is gone") }
        }
        val setup = Setup(cancelled, segments = listOf(bank))
        assertThrows(CancellationException::class.java) { runBlocking { setup.useCase("s1") } }
        assertTrue(setup.todos.stored.isEmpty())
        assertEquals(0, setup.scheduler.calls)
    }

    @Test
    fun `a cancel that comes as the finds are stored keeps all of them and arms their reminders`() = runBlocking {
        val todos = StoredTodos()
        val storing = CompletableDeferred<Unit>()
        val stored = CompletableDeferred<Unit>()
        // As Room stores a row: on its own thread, then back in the caller, where a cancel shows.
        val slowStore = object : TodoRepository by todos {
            override suspend fun upsert(todo: Todo) {
                todos.upsert(todo)
                storing.complete(Unit)
                stored.await()
            }
        }
        val setup = Setup(
            FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan", "S2" to "Ask the plumber about the tap")),
            segments = listOf(bank, plumber),
            todos = todos,
            store = slowStore
        )
        var outcome: ExtractSessionTodosUseCase.Outcome? = null

        // A recording that starts stops Find to-dos and ideas again as the first find is stored.
        val search = launch { outcome = setup.useCase("s1") }
        storing.await()
        search.cancel()
        stored.complete(Unit)
        search.join()

        assertEquals(listOf("Call the bank about the loan", "Ask the plumber about the tap"), todos.stored.map { it.text })
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(2), outcome)
        assertEquals(1, setup.scheduler.calls)
    }

    @Test
    fun `reads the transcript in the language that was spoken`() = runBlocking {
        val german = segment("Wir müssen den Termin mit dem Vermieter nicht verschieben, weil die Heizung noch kaputt ist.", at = 1_000L)
        val llm = FakeLlmRepository(answer = answer())
        val setup = Setup(llm, segments = listOf(german))
        setup.useCase("s1")
        assertEquals("todo prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `tells the model which to-dos the recording already has`() = runBlocking {
        val known = Todo(
            id = "known", sessionId = "s1", text = "Call the bank about the loan", status = TodoStatus.OPEN,
            sourceSegmentIds = listOf("seg1000"), createdAt = 0L, updatedAt = 0L
        )
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan"))
        val setup = Setup(llm, segments = listOf(bank), todos = StoredTodos(known))
        val outcome = setup.useCase("s1")
        assertTrue(llm.prompts.single().first.contains("said for it: \"Call the bank about the loan\"\n"))
        // The model repeated it, so it is not stored a second time.
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(0), outcome)
        assertEquals(listOf("known"), setup.todos.stored.map { it.id })
    }

    // ─── Finds that take the place of the old transcript's (Transcribe again in another language) ──

    // What an old transcript gave the recording, untouched since.
    private val old = Todo(
        id = "old", sessionId = "s1", text = "Call the bank about the loan", status = TodoStatus.OPEN,
        sourceSegmentIds = listOf("seg1000"), createdAt = 0L, updatedAt = 0L
    )

    @Test
    fun `finds in place of old ones are no repeats of them, and the old ones go once the new ones are stored`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan"))
        val setup = Setup(llm, segments = listOf(bank), todos = StoredTodos(old))
        val takenAway = mutableListOf<Pair<Set<String>, List<String>>>()

        val outcome = setup.useCase("s1", replacing = setOf("old")) { ids -> takenAway += ids to setup.todos.stored.map { it.id } }

        // Not shown to the model as noted, nor dropped as its repeat.
        assertFalse(llm.prompts.single().first.contains("said for it: \"Call the bank about the loan\"\n"))
        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(1), outcome)
        // Taken away after the new one is in.
        assertEquals(listOf(setOf("old") to listOf("old", "todo-1")), takenAway)
    }

    @Test
    fun `a search with nothing to replace takes nothing away`() = runBlocking {
        val setup = Setup(FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan")), segments = listOf(bank))
        var calls = 0

        setup.useCase("s1") { calls++ }

        assertEquals(0, calls)
    }

    @Test
    fun `a search the model couldn't read stores nothing in place of old ones, which stay`() = runBlocking {
        // Not loaded: only what is said outright would be found, which the old finds don't give way to.
        val notLoaded = FakeLlmRepository(answer = answer("S1" to "Call the bank"), reloadFailure = IllegalStateException("no model"))
        val failing = FakeLlmRepository(failWith = IllegalStateException("native crash"))
        for (llm in listOf(notLoaded, failing)) {
            val setup = Setup(llm, segments = listOf(bank), todos = StoredTodos(old))
            var calls = 0

            assertEquals(ExtractSessionTodosUseCase.Outcome.Failed, setup.useCase("s1", replacing = setOf("old")) { calls++ })

            assertEquals(listOf("old"), setup.todos.stored.map { it.id })
            assertEquals(0, calls)
            assertEquals(0, setup.scheduler.calls)
        }
    }

    @Test
    fun `a cancel that comes as the finds in place of old ones are stored still takes the old ones away`() = runBlocking {
        val todos = StoredTodos(old)
        val storing = CompletableDeferred<Unit>()
        val stored = CompletableDeferred<Unit>()
        val slowStore = object : TodoRepository by todos {
            override suspend fun upsert(todo: Todo) {
                todos.upsert(todo)
                storing.complete(Unit)
                stored.await()
            }
        }
        val setup = Setup(
            FakeLlmRepository(answer = answer("S1" to "Call the bank about the loan")),
            segments = listOf(bank),
            todos = todos,
            store = slowStore
        )
        val takenAway = mutableListOf<Set<String>>()

        val search = launch { setup.useCase("s1", replacing = setOf("old")) { takenAway += it } }
        storing.await()
        search.cancel()
        stored.complete(Unit)
        search.join()

        assertEquals(listOf(setOf("old")), takenAway)
    }

    private fun idea(segment: String, text: String) = """{"segment": "$segment", "type": "idea", "text": "$text"}"""

    private fun guess(segment: String, text: String) =
        """{"segment": "$segment", "type": "task", "text": "$text", "when": "none", "capture": "inferred"}"""

    private fun items(vararg items: String) = """{"items": [${items.joinToString(", ")}]}"""

    @Test
    fun `counts the ideas and suggestions it stored, and the suggestions over the cap`() = runBlocking {
        val segments = listOf(
            bank,
            segment("It would be good to have a dark mode.", at = 2_000L),
            segment("The gutter looks blocked.", at = 3_000L),
            segment("The printer is out of ink.", at = 4_000L),
            segment("The garden looks dry.", at = 5_000L),
            segment("The bike tyres look flat.", at = 6_000L)
        )
        val llm = FakeLlmRepository(
            answer = items(
                idea("S2", "Dark mode"),
                guess("S3", "Clear the gutter"),
                guess("S4", "Buy printer ink"),
                guess("S5", "Water the garden"),
                guess("S6", "Pump up the bike tyres")
            )
        )
        val setup = Setup(llm, segments = segments)

        val outcome = setup.useCase("s1")

        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(count = 5, ideas = 1, suggested = 3, overCap = 1), outcome)
        assertEquals(
            listOf(
                "Call the bank about the loan" to TodoStatus.OPEN,
                "Dark mode" to TodoStatus.IDEA,
                "Clear the gutter" to TodoStatus.SUGGESTED,
                "Buy printer ink" to TodoStatus.SUGGESTED,
                "Water the garden" to TodoStatus.SUGGESTED
            ),
            setup.todos.stored.map { it.text to it.status }
        )
    }

    @Test
    fun `shows the model the recording's own items, every pending to-do and the ideas of the 30 days before it`() = runBlocking {
        val day = 24 * 60 * 60 * 1000L
        fun known(id: String, text: String, status: TodoStatus, session: String = "other", created: Long = 39 * day) =
            Todo(id = id, sessionId = session, text = text, status = status, createdAt = created, updatedAt = created)
        val store = StoredTodos(
            known("own", "Book the hotel", TodoStatus.DONE, session = "s1"),
            known("open", "Pay the rent", TodoStatus.OPEN),
            known("suggested", "Clear the gutter", TodoStatus.SUGGESTED),
            known("recent", "Dark mode for the cover", TodoStatus.IDEA, created = 11 * day),
            known("old", "Weekly summary of the notes", TodoStatus.IDEA, created = 9 * day),
            known("done", "Fix the bike", TodoStatus.DONE)
        )
        val llm = FakeLlmRepository(answer = answer())
        // It speaks of each of them, so each one the search loads can be said again in it.
        val all = segment(
            "Remind me to call the bank, the hotel, pay the rent, clear the gutter, the dark mode for the cover, " +
                "a weekly summary of the notes, and fix the bike.",
            at = 1_000L
        )

        Setup(llm, segments = listOf(all), todos = store, recording = session().copy(createdAt = 40 * day)).useCase("s1")

        // Its own first, then the ideas, then the other pending to-dos.
        val noted = "\"Book the hotel\"; \"Dark mode for the cover\"; \"Pay the rent\"; \"Clear the gutter\""
        assertTrue(llm.prompts.single().first, llm.prompts.single().first.contains("said for it: $noted\n"))
    }

    @Test
    fun `a recording still gets its to-dos when the other recordings' can't be read`() = runBlocking {
        val store = StoredTodos(othersFail = IllegalStateException("database closed"))
        val setup = Setup(FakeLlmRepository(answer = answer()), segments = listOf(bank), todos = store)

        assertEquals(ExtractSessionTodosUseCase.Outcome.Found(1), setup.useCase("s1"))
        assertEquals(listOf("Call the bank about the loan"), store.stored.map { it.text })
    }
}
