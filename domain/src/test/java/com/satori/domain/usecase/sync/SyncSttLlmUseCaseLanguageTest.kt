package com.satori.domain.usecase.sync

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.LlmRepository
import com.satori.domain.repository.OutputGrammar
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live notes and the recording's language: none while it is in Malayalam, which the small live
 * model reads poorly (the notes are all written after Stop); switched to Malayalam, they stop from
 * the next interval; switched back, they go on with what was said in English or German, never with
 * a line in Malayalam script. The sentences are made up for the tests; the Malayalam ones say "we
 * have to book the room for Friday" and "the agenda goes to the team today".
 */
class SyncSttLlmUseCaseLanguageTest {

    private val answer = """{"title": "Room booking", "points": ["Book the room for Friday."]}"""

    private val english = segment("We still have to book the room for the Friday review", at = 1)
    private val malayalam = segment("വെള്ളിയാഴ്ചത്തേക്ക് മുറി ബുക്ക് ചെയ്യണം", at = 2)
    private val moreMalayalam = segment("അജണ്ട ഇന്ന് ടീമിന് അയക്കണം", at = 3)

    private val settings = AppSettings(simpleListeningIntervalSeconds = 0)

    /** The engine's turn, counting how often a note asks for it. */
    private class CountingTurn : LiveNoteTurn {
        val asks = AtomicInteger()

        override fun tryTake(): Boolean {
            asks.incrementAndGet()
            return true
        }

        override suspend fun prepare() = Unit
        override suspend fun release() = Unit
    }

    /** [fake], keeping the text of each note's call. */
    private class Calls(private val fake: FakeLlmRepository) : LlmRepository by fake {
        val texts: MutableList<String> = mutableListOf()

        override fun generateInsight(text: String, systemPrompt: String?, maxTokens: Int, grammar: OutputGrammar?): Flow<String> = flow {
            texts += text
            emitAll(fake.generateInsight(text, systemPrompt, maxTokens, grammar))
        }
    }

    private fun useCase(llm: LlmRepository) = SyncSttLlmUseCase(llm, FakeSettingsRepository(settings), FakeResourceProvider())

    @Test
    fun `a Malayalam recording gets no live note, and no turn at the engine is asked for`() = runBlocking {
        val llm = Calls(FakeLlmRepository(answer = answer))
        val turn = CountingTurn()
        val talk = flow {
            emit(malayalam)
            emit(segment("ഇനിയും ഒരു വാക്യം, കുറച്ചു കൂടി വാക്കുകളോടെ", at = 4))
            emit(moreMalayalam)
        }

        val insights = useCase(llm)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            turn = turn,
            recordingLanguage = RecordingLanguage.MALAYALAM
        ).toList()

        assertTrue(insights.isEmpty())
        assertTrue(llm.texts.isEmpty())
        assertEquals(0, turn.asks.get())
    }

    @Test
    fun `switched to Malayalam, the notes stop from their next interval`() = runBlocking {
        val llm = Calls(FakeLlmRepository(answer = answer))
        val languages = Channel<RecordingLanguage>(Channel.UNLIMITED)
        val firstWritten = CompletableDeferred<Unit>()
        val talk = flow {
            emit(english)
            firstWritten.await()
            languages.send(RecordingLanguage.MALAYALAM)
            languages.close()
            delay(100)
            // What the English or German model still finishes after the switch is no note's either.
            emit(segment("and the agenda goes out to the whole team today", at = 5))
            emit(malayalam)
        }
        val insights = mutableListOf<LlmInsight>()

        useCase(llm)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            recordingLanguageChanges = languages.consumeAsFlow()
        ).collect { insight ->
            insights += insight
            firstWritten.complete(Unit)
        }

        assertEquals(listOf(english.text), llm.texts)
        assertEquals(listOf(listOf(english.id)), insights.map { it.sourceSegmentIds })
    }

    @Test
    fun `switched back, the notes go on with what was said in English or German before and after, never a Malayalam line`() = runBlocking {
        val llm = Calls(FakeLlmRepository(answer = answer))
        val languages = Channel<RecordingLanguage>(Channel.UNLIMITED)
        // Too few words for a note on their own.
        val before = segment("Call the bank", at = 1)
        val after = segment("about the loan and the papers", at = 6)
        val talk = flow {
            emit(before)
            languages.send(RecordingLanguage.MALAYALAM)
            delay(100)
            emit(malayalam)
            languages.send(RecordingLanguage.ENGLISH_OR_GERMAN)
            languages.close()
            delay(100)
            // The Malayalam model finishing the utterance it was on as the recording switched back.
            emit(moreMalayalam)
            emit(after)
        }

        val insights = useCase(llm)(
            transcriptionFlow = talk,
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en",
            recordingLanguageChanges = languages.consumeAsFlow()
        ).toList()

        assertEquals(listOf("${before.text} ${after.text}"), llm.texts)
        assertEquals(listOf(before.id, after.id), insights.single().sourceSegmentIds)
    }

    @Test
    fun `an English recording with a Malayalam word in a line gets its notes as before`() = runBlocking {
        val llm = Calls(FakeLlmRepository(answer = answer))
        val line = segment("We still have to book the hall for the സദ്യ on Friday after the review", at = 1)

        val insights = useCase(llm)(
            transcriptionFlow = flow { emit(line) },
            sessionId = "s1",
            mode = RecordingMode.SIMPLE_LISTENING,
            outputLanguage = "en"
        ).toList()

        assertEquals(listOf(line.text), llm.texts)
        assertEquals(listOf(line.id), insights.single().sourceSegmentIds)
    }
}
