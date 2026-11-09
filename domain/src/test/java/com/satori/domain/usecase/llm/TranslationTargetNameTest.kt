package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeLlmRepository
import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.segment
import com.satori.domain.model.AppSettings
import com.satori.domain.model.RecordingMode
import com.satori.domain.provider.ResourceProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The translation prompt names its target in its own language, the app's: on a phone set to
 * German the model is told "auf Englisch übersetzen", never "ins English".
 */
class TranslationTargetNameTest {

    private val germanPrompt = "Als professioneller Übersetzer den folgenden Text auf {target_language} übersetzen."

    /** The app in German: its translation prompt and its names of the languages. */
    private val germanApp = object : ResourceProvider by FakeResourceProvider() {
        override fun getTranslationTargetName(code: String): String = mapOf("en" to "Englisch", "de" to "Deutsch").getValue(code)
    }

    private val talk = "We agreed to move the launch to March, and the team will update the plan " +
        "before Friday so that everyone knows the new dates."

    @Test
    fun `a German app's final translation is asked for in German words`() = runBlocking {
        val llm = FakeLlmRepository(answer = "We move the launch to March.")
        val settings = FakeSettingsRepository(AppSettings(translationSystemPrompt = germanPrompt))
        GenerateFinalInsightUseCase(llm, settings, germanApp)(
            pendingText = talk, sessionId = "s1", mode = RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "en"
        ).getOrThrow()

        assertEquals(
            "Als professioneller Übersetzer den folgenden Text auf Englisch übersetzen.",
            llm.prompts.single().second
        )
    }

    @Test
    fun `a German app's batch translation is asked for in German words`() = runBlocking {
        val llm = FakeLlmRepository(answer = "Wir verschieben den Start auf März.")
        val settings = FakeSettingsRepository(AppSettings(translationSystemPrompt = germanPrompt))
        GenerateBatchInsightUseCase(llm, settings, germanApp)(
            listOf(segment(talk, at = 1)), "s1", RecordingMode.REAL_TIME_TRANSLATION, outputLanguage = "de"
        )

        assertEquals(
            "Als professioneller Übersetzer den folgenden Text auf Deutsch übersetzen.",
            llm.prompts.first().second
        )
    }

    @Test
    fun `without its own names a provider gives the English ones, and an unknown code as it is`() {
        val english = FakeResourceProvider()
        assertEquals("English", english.getTranslationTargetName("en"))
        assertEquals("German", english.getTranslationTargetName("de"))
        assertEquals("xx", english.getTranslationTargetName("xx"))
    }
}
