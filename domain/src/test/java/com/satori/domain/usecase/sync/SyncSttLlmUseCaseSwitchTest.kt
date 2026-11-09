package com.satori.domain.usecase.sync

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
import com.satori.domain.usecase.llm.answerKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A recording switched to another kind while it is recorded ("Record as"): the live notes go on
 * in the new mode from their next interval, with its interval, its length of answer and its prompt
 * in the notes' language; the note being written as the switch comes is written as it began; the
 * transcript is read on as it comes, and what was said since the last note stays for the next one.
 * A translation is never switched, nor switched into.
 */
class SyncSttLlmUseCaseSwitchTest {

    // A meeting's answer: a voice note reads its title and points, a meeting its action items too.
    private val answer = """{"title": "Room booking", "points": ["Book the room for Friday."], "action_items": ["Send the agenda to the team"]}"""

    // The next note's own: since 0.7.3 a live note that repeats the one before is none (NoteRepeats).
    private val agenda = """{"title": "Agenda", "points": ["The agenda goes out today."], "action_items": ["Send the agenda to the team"]}"""

    private val first = segment("We still have to book the room for the Friday review", at = 1)
    private val second = segment("and the agenda goes out to the whole team today", at = 2)

    private fun useCase(llm: LlmRepository, settings: AppSettings) =
        SyncSttLlmUseCase(llm, FakeSettingsRepository(settings), FakeResourceProvider())

    @Test
    fun `a voice note switched to a meeting writes its next note as a meeting's, reading the transcript on`() = runBlocking {
        val llm = CallLog(FakeLlmRepository(answers = listOf(answer, agenda)))
        val modes = Channel<RecordingMode>(Channel.UNLIMITED)
        val firstWritten = CompletableDeferred<Unit>()
        var collections = 0
        val talk = flow {
            collections++
            emit(first)
            firstWritten.await()
            modes.send(RecordingMode.SHORT_MEETING)
            modes.close()
            // The first note has ended, and the switch is in.
            delay(100)
            emit(second)
        }
        val insights = mutableListOf<LlmInsight>()

        useCase(llm, AppSettings(simpleListeningIntervalSeconds = 0, shortMeetingIntervalSeconds = 0))(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            modeChanges = modes.consumeAsFlow()
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }

        assertEquals(listOf("simple_listening prompt (en)", "short_meeting prompt (en)"), llm.calls.map { it.systemPrompt })
        assertEquals(listOf(512, 600), llm.calls.map { it.maxTokens })
        assertEquals(listOf(listOf("title", "points"), listOf("title", "points", "action_items")), llm.calls.map { it.keys })
        // The voice note's note stays as it was written; the meeting's has its action items.
        assertNull(insights[0].tasks)
        assertEquals("[\"Send the agenda to the team\"]", insights[1].tasks)
        assertEquals(listOf(listOf(first.id), listOf(second.id)), insights.map { it.sourceSegmentIds })
        // The transcript was read once, on through the switch.
        assertEquals(1, collections)
    }

    @Test
    fun `the next interval is the new mode's, and what was said before the switch is read in it`() = runBlocking {
        // A meeting notes once an hour, a voice note at once.
        val settings = AppSettings(shortMeetingIntervalSeconds = 3_600, simpleListeningIntervalSeconds = 0)
        val llm = CallLog(FakeLlmRepository(answers = listOf(answer, agenda)))
        val modes = Channel<RecordingMode>(Channel.UNLIMITED)
        val talk = flow {
            // Within the meeting's hour: no note.
            emit(first)
            modes.send(RecordingMode.SIMPLE_LISTENING)
            modes.close()
            delay(100)
            emit(second)
        }

        val insights = useCase(llm, settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SHORT_MEETING,
            outputLanguage = "en",
            modeChanges = modes.consumeAsFlow()
        ).toList()

        assertEquals("simple_listening prompt (en)", llm.calls.single().systemPrompt)
        assertEquals("${first.text} ${second.text}", llm.calls.single().text)
        assertEquals(listOf(first.id, second.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `a switch to a longer interval holds the next note back until it has passed`() = runBlocking {
        val settings = AppSettings(simpleListeningIntervalSeconds = 0, longMeetingIntervalMinutes = 10)
        val llm = CallLog(FakeLlmRepository(answers = listOf(answer, agenda)))
        val modes = Channel<RecordingMode>(Channel.UNLIMITED)
        val firstWritten = CompletableDeferred<Unit>()
        val talk = flow {
            emit(first)
            firstWritten.await()
            modes.send(RecordingMode.LONG_MEETING)
            modes.close()
            delay(100)
            // Ten minutes from the last note have not passed.
            emit(second)
        }
        val insights = mutableListOf<LlmInsight>()

        useCase(llm, settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            modeChanges = modes.consumeAsFlow()
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }

        assertEquals(1, llm.calls.size)
        assertEquals(listOf(first.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `a note being written as the switch comes is written to its end as it began`() = runBlocking {
        val hold = CompletableDeferred<Unit>()
        val llm = CallLog(FakeLlmRepository(answers = listOf(answer, agenda)), holdFirst = hold)
        val modes = Channel<RecordingMode>(Channel.UNLIMITED)
        val firstWritten = CompletableDeferred<Unit>()
        val talk = flow {
            emit(first)
            // The first note is being written when the recording is switched.
            llm.generating.await()
            modes.send(RecordingMode.SHORT_MEETING)
            modes.close()
            delay(100)
            hold.complete(Unit)
            firstWritten.await()
            delay(100)
            emit(second)
        }
        val insights = mutableListOf<LlmInsight>()

        useCase(llm, AppSettings(simpleListeningIntervalSeconds = 0, shortMeetingIntervalSeconds = 0))(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            modeChanges = modes.consumeAsFlow()
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }

        // The note under way: a voice note's, from its prompt to the reading of its answer.
        assertEquals("simple_listening prompt (en)", llm.calls[0].systemPrompt)
        assertEquals(512, llm.calls[0].maxTokens)
        assertEquals(listOf("title", "points"), llm.calls[0].keys)
        assertNull(insights[0].tasks)
        // The next one: a meeting's.
        assertEquals("short_meeting prompt (en)", llm.calls[1].systemPrompt)
        assertEquals(600, llm.calls[1].maxTokens)
        assertEquals("[\"Send the agenda to the team\"]", insights[1].tasks)
    }

    @Test
    fun `a voice note is not switched into a translation`() = runBlocking {
        val llm = CallLog(FakeLlmRepository(answers = listOf(answer, agenda)))
        val modes = Channel<RecordingMode>(Channel.UNLIMITED).apply {
            trySend(RecordingMode.REAL_TIME_TRANSLATION)
            close()
        }
        // The switch comes before the first line.
        val talk = flow {
            delay(100)
            emit(first)
        }

        useCase(llm, AppSettings(simpleListeningIntervalSeconds = 0, translationIntervalSeconds = 0))(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            modeChanges = modes.consumeAsFlow()
        ).toList()

        assertEquals("simple_listening prompt (en)", llm.calls.single().systemPrompt)
        assertEquals(listOf("title", "points"), llm.calls.single().keys)
    }

    @Test
    fun `a translation is not switched`() = runBlocking {
        val llm = CallLog(FakeLlmRepository(answer = "Der Raum ist für Freitag gebucht."))
        val settings = AppSettings(
            translationIntervalSeconds = 0,
            shortMeetingIntervalSeconds = 0,
            translationSystemPrompt = "Translate into {target_language}."
        )
        val modes = Channel<RecordingMode>(Channel.UNLIMITED).apply {
            trySend(RecordingMode.SHORT_MEETING)
            close()
        }
        val talk = flow {
            delay(100)
            emit(first)
        }

        val insights = useCase(llm, settings)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.REAL_TIME_TRANSLATION,
            outputLanguage = "de",
            modeChanges = modes.consumeAsFlow()
        ).toList()

        assertEquals("Translate into German.", llm.calls.single().systemPrompt)
        assertEquals(256, llm.calls.single().maxTokens)
        assertEquals("Der Raum ist für Freitag gebucht.", insights.single().content)
    }
}

/**
 * [fake], keeping each note's call to the engine ([calls]): its text, its system prompt, its
 * length of answer and the keys its grammar asks for. The first call waits for [holdFirst] when one
 * is given, and [generating] completes as it begins.
 */
private class CallLog(
    private val fake: FakeLlmRepository,
    private val holdFirst: CompletableDeferred<Unit>? = null
) : LlmRepository by fake {
    class Call(val text: String, val systemPrompt: String?, val maxTokens: Int, val keys: List<String>)

    val calls = mutableListOf<Call>()
    val generating = CompletableDeferred<Unit>()

    override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
        calls += Call(text, systemPrompt, maxTokens, grammar?.answerKeys().orEmpty())
        if (calls.size == 1) {
            generating.complete(Unit)
            holdFirst?.await()
        }
        emitAll(fake.generateInsight(text, systemPrompt, maxTokens, grammar))
    }
}
