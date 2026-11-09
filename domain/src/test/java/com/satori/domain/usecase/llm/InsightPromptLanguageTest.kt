package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The insight use cases ask in the language of the output: its prompt, its JSON keys and its
 * grammar. The settings hold the prompts of the app language, used only while no language is
 * known.
 */
class InsightPromptLanguageTest {

    // A phone set to German: the settings hold the German prompts.
    private val germanPhone = AppSettings(shortMeetingSystemPrompt = "short_meeting prompt (de)")

    private val talk = "We agreed to move the launch to March, and the team will update the plan " +
        "before Friday so that everyone can review it on Monday morning."
    private val germanTalk = "Wir haben vereinbart, den Start auf März zu verschieben, damit das ganze Team " +
        "den Plan am Montagmorgen noch einmal in Ruhe prüfen kann."
    private val answer = "{\"title\": \"Launch moves to March\", \"points\": [\"The launch moves to March.\"]}"

    @Test
    fun `a final insight in English gets the English prompt on a German phone`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(germanPhone), FakeResourceProvider())(
            pendingText = talk, sessionId = "s1", mode = RecordingMode.SHORT_MEETING, outputLanguage = "en"
        )
        assertEquals("short_meeting prompt (en)", llm.prompts.single().second)
    }

    @Test
    fun `a final insight in no known language gets the app language's prompt`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        GenerateFinalInsightUseCase(llm, FakeSettingsRepository(germanPhone), FakeResourceProvider())(
            pendingText = talk, sessionId = "s1", mode = RecordingMode.SHORT_MEETING, outputLanguage = null
        )
        assertEquals("short_meeting prompt (de)", llm.prompts.single().second)
    }

    @Test
    fun `an end-of-session insight in English gets the English prompt on a German phone`() = runBlocking {
        val llm = FakeLlmRepository(answer = answer)
        GenerateBatchInsightUseCase(llm, FakeSettingsRepository(germanPhone), FakeResourceProvider())(
            segments = listOf(segment(talk, at = 1)),
            sessionId = "s1",
            mode = RecordingMode.SHORT_MEETING,
            outputLanguage = "en"
        )
        assertEquals("short_meeting prompt (en)", llm.prompts.single().second)
    }

    @Test
    fun `a final insight is held to the grammar of its language and read with its keys`() = runBlocking {
        val llm = FakeLlmRepository(
            answer = "{\"titel\": \"Start im März\", \"punkte\": [\"Start auf März verschieben.\"], " +
                "\"aktionen\": [\"Plan bis Freitag anpassen\"]}"
        )
        val note = GenerateFinalInsightUseCase(llm, FakeSettingsRepository(), LocalizedResourceProvider())(
            pendingText = germanTalk, sessionId = "s1", mode = RecordingMode.SHORT_MEETING, outputLanguage = "de"
        ).getOrThrow()!!

        assertEquals(listOf("titel", "punkte", "aktionen"), llm.grammars.single().answerKeys())
        assertEquals("Start im März", note.title)
        assertEquals("- Start auf März verschieben.", note.content)
        assertEquals("[\"Plan bis Freitag anpassen\"]", note.tasks)
    }

    @Test
    fun `a final translation is free text`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Wir verschieben den Start auf März.")
        val settings = FakeSettingsRepository(AppSettings(translationSystemPrompt = "Translate into {target_language}."))
        val note = GenerateFinalInsightUseCase(llm, settings, FakeResourceProvider())(
            pendingText = talk, sessionId = "s1", mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de"
        ).getOrThrow()!!

        assertNull(llm.grammars.single())
        assertEquals("Wir verschieben den Start auf März.", note.content)
    }
}
