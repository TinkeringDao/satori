package com.satori.domain.usecase.sync

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.LocalizedResourceProvider
import com.satori.domain.usecase.llm.answerKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSttLlmUseCaseTest {

    // An interval of 0 s runs an inference for the first segment with enough words. The settings
    // hold the app language's prompt, which is sent while no language is known.
    private val settings = AppSettings(
        simpleListeningIntervalSeconds = 0,
        simpleListeningSystemPrompt = "Summarise what was said."
    )

    // With a language known, that language's prompt is sent.
    private val shippedPrompts = AppSettings(simpleListeningIntervalSeconds = 0)

    private fun useCase(llm: LlmRepository, settings: AppSettings) =
        SyncSttLlmUseCase(llm, FakeSettingsRepository(settings), FakeResourceProvider())

    @Test
    fun `a failed inference reaches the caller and ends nothing`() = runBlocking {
        val llm = FakeLlmRepository(failWith = IllegalStateException("model unloaded"))
        val errors = mutableListOf<Exception>()
        val insights = useCase(llm, settings)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            onInferenceError = { errors += it }
        ).toList()
        assertTrue(insights.isEmpty())
        assertEquals("model unloaded", errors.single().message)
    }

    @Test
    fun `a successful inference becomes an insight`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val errors = mutableListOf<Exception>()
        val insights = useCase(llm, settings)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            onInferenceError = { errors += it }
        ).toList()
        assertEquals(1, insights.size)
        assertTrue(errors.isEmpty())
        assertEquals("Summarise what was said.", llm.prompts.single().second)
    }

    @Test
    fun `an interval is written in the language its text is in`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Raum\nDer Raum ist gebucht.")
        val asked = mutableListOf<String>()
        val text = "Bitte den Raum für das Freitagstreffen buchen heute"
        useCase(llm, shippedPrompts)(
            transcriptionFlow = flowOf(segment(text, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            notesLanguage = { read -> asked += read; "de" }
        ).toList()
        assertEquals(listOf(text), asked)
        assertEquals("simple_listening prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `the language follows the talk from one interval to the next`() = runBlocking {
        // Each note its own: since 0.7.3 a live note that repeats one before it is none (NoteRepeats).
        val llm = FakeLlmRepository(answers = listOf("Room\nThe room is booked.", "Raum\nDer Raum ist gebucht."))
        val firstWritten = CompletableDeferred<Unit>()
        val languages = ArrayDeque(listOf("en", "de"))
        val talk = flow {
            emit(segment("Please book the room for the Friday review", at = 1))
            firstWritten.await()
            // The first inference has ended and a new one may start.
            delay(100)
            emit(segment("Bitte den Raum für das Freitagstreffen buchen heute", at = 2))
        }
        val insights = mutableListOf<LlmInsight>()
        useCase(llm, shippedPrompts)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            notesLanguage = { languages.removeFirst() }
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }
        assertEquals(2, insights.size)
        assertEquals(
            listOf("simple_listening prompt (en)", "simple_listening prompt (de)"),
            llm.prompts.map { it.second }
        )
    }

    @Test
    fun `a translation keeps its target language whatever is said`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Der Raum ist gebucht.")
        val translation = AppSettings(
            translationIntervalSeconds = 0,
            translationSystemPrompt = "Translate into {target_language}."
        )
        useCase(llm, translation)(
            transcriptionFlow = flowOf(segment("Please book the room for Friday", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "de",
            notesLanguage = { error("A translation has its own language") }
        ).toList()
        assertEquals("Translate into German.", llm.prompts.single().second)
        // A translation is free text.
        assertNull(llm.grammars.single())
    }

    // The synthetic German meeting feed (de-besprechung.wav) of 2026-10-14: its first three lines as
    // the recognizer gave them out, each after the words it was still on. On the phone the live
    // translations read those words too, then each whole line again.
    private val first = "Guten Morgen zusammen, fangen wir mit der Wochenplanung an. Erstens der Umzug ins neue Büro. " +
        "Die Möbel werden am Montag geliefert. Peter kümmert sich um die Schlüssel. Zweitens das Budget."
    private val second = "Das passt zum Plan. Für die Messe im November brauchen wir zusätzlich 2000 Euro. Wir haben " +
        "beschlossen, das freizugeben, wenn die Reisekosten im Rahmen bleiben."
    private val third = "Die neue App kommt gut an, aber die Anmeldung dauert zu lange. Zum Schluss, am Freitag ist das Büro geschlossen."

    /** A model that answers each call only once the test lets it ([go]), so lines come while it translates. */
    private class HeldModel(private val answer: String) : LlmRepository by FakeLlmRepository() {
        val prompts = mutableListOf<String>()
        val started = List(3) { CompletableDeferred<Unit>() }
        val go = List(3) { CompletableDeferred<Unit>() }

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            val call = prompts.size
            prompts += text
            started[call].complete(Unit)
            go[call].await()
            emit(answer)
        }
    }

    @Test
    fun `a translation that starts while the next line is partial reads finished lines only, each once`() = runBlocking {
        val model = HeldModel("Good morning everyone.")
        val translation = AppSettings(translationIntervalSeconds = 0, translationSystemPrompt = "Translate into {target_language}.")
        val talk = flow {
            emit(segment("Guten Morgen zusammen, fangen wir mit der Wochenplanung an, erstens der Umzug", at = 1, complete = false))
            emit(segment(first, at = 2))
            model.started[0].await()
            // Said while the first line is translated: waits for the next turn.
            emit(segment("Das passt zum Plan, für die Messe im November", at = 3, complete = false))
            emit(segment(second, at = 4))
            model.go[0].complete(Unit)
            delay(100)
            // The next translation starts as the third line is still being said.
            emit(segment("Die neue App kommt gut an", at = 5, complete = false))
            model.started[1].await()
            emit(segment(third, at = 6))
            model.go[1].complete(Unit)
            delay(100)
            emit(segment("Danke euch", at = 7, complete = false))
            model.started[2].await()
            model.go[2].complete(Unit)
        }

        val translations = useCase(model, translation)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "en"
        ).toList()

        assertEquals(listOf(first, second, third), model.prompts)
        assertEquals(listOf(listOf("seg2"), listOf("seg4"), listOf("seg6")), translations.map { it.sourceSegmentIds })
    }

    @Test
    fun `a translation reads finished lines only, each once, so the three windows of a German meeting say nothing twice`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Good morning everyone.")
        val translation = AppSettings(translationIntervalSeconds = 0, translationSystemPrompt = "Translate into {target_language}.")
        val talk = flowOf(
            segment("Guten Morgen zusammen, fangen wir mit der Wochenplanung an, erstens der Umzug", at = 1, complete = false),
            segment(first, at = 2),
            segment("Das passt zum Plan, für die Messe im November", at = 3, complete = false),
            segment(second, at = 4),
            segment("Die neue App kommt gut an", at = 5, complete = false),
            segment(third, at = 6)
        )

        val translations = useCase(llm, translation)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "en"
        ).toList()

        assertEquals(listOf(first, second, third), llm.prompts.map { it.first })
        // Each cites the line it read, so the final translation after Stop reads only the others.
        assertEquals(listOf(listOf("seg2"), listOf("seg4"), listOf("seg6")), translations.map { it.sourceSegmentIds })
    }

    @Test
    fun `a translation's wrapping quotes go, quotes inside it stay`() = runBlocking {
        val translation = AppSettings(translationIntervalSeconds = 0, translationSystemPrompt = "Translate into {target_language}.")
        fun translated(answer: String) = runBlocking {
            useCase(FakeLlmRepository(answer = answer), translation)(
                transcriptionFlow = flowOf(segment("Das passt zum Plan.", at = 1)),
                sessionId = "s1",
                mode = RecordingMode.REAL_TIME_TRANSLATION,
                outputLanguage = "en"
            ).toList().single().content
        }

        assertEquals("It fits the plan.", translated("“It fits the plan.”"))
        assertEquals("It fits the plan.", translated("\"It fits the plan.\""))
        assertEquals("He said \"it fits\" and left.", translated("He said \"it fits\" and left."))
        assertEquals("“Yes,” he said, “it fits.”", translated("“Yes,” he said, “it fits.”"))
    }

    @Test
    fun `an interval is held to the notes grammar of its language and read with its keys`() = runBlocking {
        val llm = FakeLlmRepository(answer = "{\"titel\": \"Raum für Freitag\", \"punkte\": [\"Raum für Freitag buchen.\"]}")
        val text = "Bitte den Raum für das Freitagstreffen buchen heute"
        val insights = SyncSttLlmUseCase(llm, FakeSettingsRepository(shippedPrompts), LocalizedResourceProvider())(
            transcriptionFlow = flowOf(segment(text, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            notesLanguage = { "de" }
        ).toList()

        assertEquals(listOf("titel", "punkte"), llm.grammars.single().answerKeys())
        assertEquals("Raum für Freitag", insights.single().title)
        assertEquals("- Raum für Freitag buchen.", insights.single().content)
    }

    @Test
    fun `an answer with nothing in it makes no note`() = runBlocking {
        val llm = FakeLlmRepository(answer = "{\"title\": \"...\", \"points\": [\"...\"]}")
        val insights = useCase(llm, shippedPrompts)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).toList()

        assertEquals(1, llm.prompts.size)
        assertTrue(insights.isEmpty())
    }

    @Test
    fun `the model reads what was said as it was said`() = runBlocking {
        val llm = FakeLlmRepository(answer = "{\"title\": \"Room booking\", \"points\": [\"Book the room for Friday.\"]}")
        val said = "Please book the room for the Friday review."
        useCase(llm, shippedPrompts)(
            transcriptionFlow = flowOf(segment(said, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).toList()

        // No label and no lines as context in front of it: the prompt leads in to the text itself.
        assertEquals(said, llm.prompts.single().first)
        assertEquals("simple_listening prompt (en)", llm.prompts.single().second)
    }

    @Test
    fun `a refused turn makes no call, and the next note reads what was said meanwhile`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events, free = false)
        val first = segment("Please book the room for the Friday review", at = 1)
        val second = segment("and send the agenda to the whole team today", at = 2)
        val talk = flow {
            emit(first)
            // The older recording is finished, and the engine is free again.
            turn.free = true
            emit(second)
        }

        val insights = useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        // Nothing touched the engine for the refused interval.
        assertEquals(listOf("refused", "take", "prepare", "reload", "generate", "release"), events)
        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
        assertEquals("${first.text} ${second.text}", fake.prompts.single().first)
        assertEquals(listOf(first.id, second.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `an interval with too few words to write about takes no turn`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events)
        val short = segment("Right, the next point", at = 1)
        val long = segment("Please book the room for the Friday review", at = 2)

        val insights = useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = flowOf(short, long),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        // A turn taken for the short line alone would never be given back: its interval ends at
        // the word check, and the engine would be held for good.
        assertEquals(listOf("take", "prepare", "reload", "generate", "release"), events)
        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
        assertEquals("${short.text} ${long.text}", fake.prompts.single().first)
        assertEquals(listOf(short.id, long.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `a line the recognizer was on as a turn was refused is read whole once the turn is free`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events, free = false)
        val first = segment("Please book the room for the Friday review", at = 1)
        val partial = segment("Then call the caterer about", at = 2, complete = false)
        val completed = segment("Then call the caterer about the dessert menu", at = 2)
        val talk = flow {
            emit(first)
            // The interval ends on the line being said, while the engine is busy.
            emit(partial)
            turn.free = true
            emit(completed)
        }

        val insights = useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        assertEquals(listOf("refused", "refused", "take", "prepare", "reload", "generate", "release"), events)
        // No note read the partial line, so none of its words count as read.
        assertEquals("${first.text} ${completed.text}", fake.prompts.single().first)
        assertEquals(listOf(first.id, completed.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `what was said through a long refusal is read as far as one note holds it`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events, free = false)
        // About three prompts' worth, so what no note can read any more is dropped on the way.
        val said = (1L..120L).map { at -> segment("Point $at of the plan: " + "and then the next step ".repeat(12).trim(), at = at) }
        val talk = flow {
            said.dropLast(1).forEach { emit(it) }
            turn.free = true
            emit(said.last())
        }

        val insights = useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        val joined = said.joinToString(" ") { it.text }
        assertTrue(joined.length > 30_000)
        assertEquals(joined.takeLast(12_000), fake.prompts.single().first)
        // Cited: the segments the prompt holds whole.
        val promptStart = joined.length - 12_000
        var offset = 0
        val whole = said.filter { line -> (offset >= promptStart).also { offset += line.text.length + 1 } }
        assertEquals(whole.map { it.id }, insights.single().sourceSegmentIds)
    }

    @Test
    fun `the live model is prepared inside the turn before it loads, and nothing sets the system prompt`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events)

        useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        assertEquals(listOf("take", "prepare", "reload", "generate", "release"), events)
        assertEquals(1, turn.released)
        // The note carries its own system prompt instead.
        assertEquals("Summarise what was said.", fake.prompts.single().second)
    }

    @Test
    fun `the turn is given back when the note fails`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(failWith = IllegalStateException("model unloaded"))
        val turn = ScriptedTurn(events)

        useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
    }

    @Test
    fun `the turn is given back when the note is cancelled`() = runBlocking {
        val events = mutableListOf<String>()
        val llm = LoggingLlm(events, FakeLlmRepository(answer = "Room booking\nThe room is booked."), hold = CompletableDeferred())
        val turn = ScriptedTurn(events)
        val notes = launch {
            useCase(llm, settings)(
                transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
                sessionId = "s1",
                mode = RecordingMode.SIMPLE_LISTENING,
                outputLanguage = null,
                turn = turn
            ).collect {}
        }
        llm.generating.await()

        // Stop: the note being written is dropped.
        notes.cancelAndJoin()

        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
        assertEquals("release", events.last())
    }

    @Test
    fun `the turn is given back when the notes stop just as a note takes it`() = runBlocking {
        val events = mutableListOf<String>()
        val llm = LoggingLlm(events, FakeLlmRepository(answer = "Room booking\nThe room is booked."), hold = CompletableDeferred())
        val turn = ScriptedTurn(events)
        val notes = launch(start = CoroutineStart.LAZY) {
            useCase(llm, settings)(
                transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
                sessionId = "s1",
                mode = RecordingMode.SIMPLE_LISTENING,
                outputLanguage = null,
                turn = turn
            ).collect {}
        }
        // Stop comes before the note has begun.
        turn.onTake = { notes.cancel() }

        notes.start()
        notes.join()

        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
    }

    @Test
    fun `a note cites only the segments its prompt holds whole`() = runBlocking {
        val events = mutableListOf<String>()
        val fake = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val turn = ScriptedTurn(events, free = false)
        // Together longer than one prompt holds: the start of the first one is left out.
        val first = segment("alpha ".repeat(1_000).trim(), at = 1)
        val second = segment("bravo ".repeat(834).trim(), at = 2)
        val third = segment("charlie ".repeat(625).trim(), at = 3)
        val talk = flow {
            emit(first)
            emit(second)
            turn.free = true
            emit(third)
        }

        val insights = useCase(LoggingLlm(events, fake), settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            turn = turn
        ).toList()

        val prompt = fake.prompts.single().first
        assertEquals(12_000, prompt.length)
        assertTrue(prompt.endsWith("${second.text} ${third.text}"))
        // The first segment was read in part, so the Notes step after Stop still reads it.
        assertEquals(listOf(second.id, third.id), insights.single().sourceSegmentIds)
        assertEquals(1, turn.taken)
        assertEquals(1, turn.released)
    }

    @Test
    fun `a line the last note read whole while it was partial is cited by the next note`() = runBlocking {
        val fake = FakeLlmRepository(answers = listOf("Room booking\nThe room is booked for Friday.", "Caterer\nCall the caterer about the dessert menu."))
        val said = "Please book the room for the Friday review"
        val next = segment("Then call the caterer about the dessert menu", at = 2)
        val insights = noteOnPartialThen(fake, said, segment(said, at = 1), next)

        // A partial line is no source: it is not stored yet.
        assertEquals(emptyList<String>(), insights[0].sourceSegmentIds)
        // Nothing of the line was new, so its words are not read again, but it is covered.
        assertEquals(next.text, fake.prompts[1].first)
        assertEquals(listOf("seg1", next.id), insights[1].sourceSegmentIds)
    }

    @Test
    fun `the rest of a line the last note read in part is read once`() = runBlocking {
        val fake = FakeLlmRepository(answers = listOf("Room booking\nThe room is booked for Friday.", "Lunch\nLunch for six is ordered."))
        val said = "Please book the room for the Friday review"
        val complete = segment("$said and order lunch for six", at = 1)
        val insights = noteOnPartialThen(fake, said, complete)

        assertEquals("and order lunch for six", fake.prompts[1].first)
        assertEquals(listOf(complete.id), insights[1].sourceSegmentIds)
    }

    /**
     * A note on the partial line [partial] ("seg1"), then, once it is written, the [later]
     * segments, each of which may start the next note.
     */
    private suspend fun noteOnPartialThen(
        llm: FakeLlmRepository,
        partial: String,
        vararg later: TranscriptionSegment
    ): List<LlmInsight> {
        val firstWritten = CompletableDeferred<Unit>()
        val talk = flow {
            emit(segment(partial, at = 1, complete = false))
            firstWritten.await()
            // The first inference has ended and a new one may start.
            delay(100)
            later.forEach { emit(it) }
        }
        val insights = mutableListOf<LlmInsight>()
        useCase(llm, settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }
        return insights
    }

    @Test
    fun `a language that cannot be found leaves the prompt as it was`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Room booking\nThe room is booked for Friday.")
        val insights = useCase(llm, settings)(
            transcriptionFlow = flowOf(segment("Please book the room for the Friday review", at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = null,
            notesLanguage = { throw IllegalStateException("database closed") }
        ).toList()
        assertEquals(1, insights.size)
        assertEquals("Summarise what was said.", llm.prompts.single().second)
    }
}
