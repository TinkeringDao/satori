package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notes of a whole recording: every part and their merge follow the notes format. */
class GenerateBatchInsightUseCaseTest {

    // Twelve lines of about 500 characters: two parts and a merge.
    private val longTalk = (1..12).map {
        segment("The workshop got a new bench, and the old shelves go to the basement next week. ".repeat(6), at = it.toLong())
    }

    private val partAnswer = "{\"title\": \"Workshop tidy-up\", \"points\": [\"Move the old shelves to the basement.\", " +
        "\"Set up the new bench.\"], \"action_items\": [\"Buy wall hooks\"]}"

    // The second part's own note: since 0.7.3 a part that repeats the one before is none (NoteRepeats).
    private val secondPart = "{\"title\": \"Basement shelves\", \"points\": [\"Paint the shelves in the basement.\", " +
        "\"Label the paint tins.\"], \"action_items\": [\"Buy wall hooks\"]}"

    private fun useCase(llm: FakeLlmRepository, settings: AppSettings = AppSettings()) =
        GenerateBatchInsightUseCase(llm, FakeSettingsRepository(settings), LocalizedResourceProvider())

    @Test
    fun `every part and the merge are held to the notes grammar of the output language`() = runBlocking {
        val llm = FakeLlmRepository(answers = listOf(partAnswer, secondPart, partAnswer))
        useCase(llm)(longTalk, "s1", RecordingMode.SHORT_MEETING, outputLanguage = "de")

        assertEquals(3, llm.grammars.size)
        llm.grammars.forEach { assertEquals(listOf("titel", "punkte", "aktionen"), it.answerKeys()) }
    }

    @Test
    fun `the merge reads each part's title and points, not its JSON or its action items`() = runBlocking {
        val llm = FakeLlmRepository(answers = listOf(partAnswer, secondPart, partAnswer))
        val parts = mutableListOf<LlmInsight>()
        val summary = useCase(llm)(
            longTalk, "s1", RecordingMode.SHORT_MEETING, outputLanguage = "en", onChunkInsight = { parts += it }
        ).getOrThrow()!!

        val part = "Workshop tidy-up\n- Move the old shelves to the basement.\n- Set up the new bench."
        val second = "Basement shelves\n- Paint the shelves in the basement.\n- Label the paint tins."
        assertEquals("$part\n\n---\n\n$second", llm.prompts.last().first)
        // The parts keep their own action items; the merge writes the whole recording's.
        assertEquals(listOf("[\"Buy wall hooks\"]", "[\"Buy wall hooks\"]"), parts.map { it.tasks })
        assertEquals("- Move the old shelves to the basement.\n- Set up the new bench.", summary.content)
    }

    @Test
    fun `a voice note's parts reach the merge as text too`() = runBlocking {
        val llm = FakeLlmRepository(
            answers = listOf(
                "{\"title\": \"Workshop tidy-up\", \"points\": [\"Set up the new bench.\"]}",
                "{\"title\": \"Basement shelves\", \"points\": [\"Paint the shelves in the basement.\"]}"
            )
        )
        useCase(llm)(longTalk, "s1", RecordingMode.SIMPLE_LISTENING, outputLanguage = "en")

        val merge = llm.prompts.last().first
        assertFalse(merge, merge.contains("{"))
        assertTrue(merge, merge.startsWith("Workshop tidy-up\n- Set up the new bench."))
        assertEquals(listOf("title", "points"), llm.grammars.last().answerKeys())
    }

    @Test
    fun `answers with nothing in them make no notes`() = runBlocking {
        val llm = FakeLlmRepository(answer = "{\"title\": \"...\", \"points\": [\"...\"], \"action_items\": []}")
        val parts = mutableListOf<LlmInsight>()
        val summary = useCase(llm)(
            longTalk, "s1", RecordingMode.SHORT_MEETING, outputLanguage = "en", onChunkInsight = { parts += it }
        )

        assertNull(summary.getOrThrow())
        assertTrue(parts.isEmpty())
        // Each part was asked once more (since 0.7.2), and no part had anything to merge.
        assertEquals(4, llm.prompts.size)
    }

    @Test
    fun `a translation is free text`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Die Werkstatt hat eine neue Werkbank.")
        val settings = AppSettings(translationSystemPrompt = "Translate into {target_language}.")
        GenerateBatchInsightUseCase(llm, FakeSettingsRepository(settings), FakeResourceProvider())(
            longTalk, "s1", RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de"
        )

        assertTrue(llm.grammars.isNotEmpty())
        assertTrue(llm.grammars.all { it == null })
    }
}
