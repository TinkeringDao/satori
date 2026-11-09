package com.satori.domain.model

import com.satori.domain.repository.CategoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordTargetTest {

    private fun category(id: String, mode: RecordingMode = RecordingMode.SIMPLE_LISTENING, order: Int = 0) =
        RecordingCategory(id = id, name = id.replaceFirstChar { it.uppercase() }, mode = mode, sortOrder = order, createdAt = 0L)

    private val categories = listOf(
        category("voice-note", order = 0),
        category("meeting", RecordingMode.SHORT_MEETING, order = 1),
        category("idea", order = 2)
    )

    @Test
    fun `the home category is the one stored`() {
        val target = RecordTargets.resolve("meeting", categories)

        assertEquals(RecordTarget.Category(categories[1]), target)
    }

    @Test
    fun `the reserved id is Translate, whatever the categories`() {
        assertEquals(RecordTarget.Translate, RecordTargets.resolve(RecordTargets.TRANSLATE_ID, categories))
        assertEquals(RecordTarget.Translate, RecordTargets.resolve(RecordTargets.TRANSLATE_ID, emptyList()))
    }

    @Test
    fun `a category that no longer exists falls back to the first one`() {
        assertEquals(RecordTarget.Category(categories[0]), RecordTargets.resolve("deleted", categories))
    }

    @Test
    fun `without any category it is a plain voice note`() {
        assertEquals(RecordTarget.VoiceNote, RecordTargets.resolve("voice-note", emptyList()))
    }

    @Test
    fun `what is stored after recording as a target`() {
        assertEquals("meeting", RecordTargets.idOf(RecordTarget.Category(categories[1])))
        assertEquals(RecordTargets.TRANSLATE_ID, RecordTargets.idOf(RecordTarget.Translate))
        assertNull(RecordTargets.idOf(RecordTarget.VoiceNote))
    }

    @Test
    fun `a fresh install records as the first starter category`() {
        assertEquals(CategoryRepository.STARTER_IDS.first(), RecordTargets.DEFAULT_ID)
        assertEquals(RecordTargets.DEFAULT_ID, AppSettings().homeCategoryId)
    }

    @Test
    fun `a category starts a recording of its mode, filed under it, with the strategy chosen for that mode`() {
        val settings = AppSettings(shortMeetingDefaultStrategy = InsightStrategy.END_OF_SESSION)

        val request = RecordRequest.of(RecordTarget.Category(categories[1]), settings)

        assertEquals(
            RecordRequest(RecordingMode.SHORT_MEETING, "meeting", null, InsightStrategy.END_OF_SESSION),
            request
        )
    }

    @Test
    fun `Translate has no category and translates into the target of the settings`() {
        val settings = AppSettings(translationTargetLanguage = "de", translationDefaultStrategy = InsightStrategy.REAL_TIME)

        val request = RecordRequest.of(RecordTarget.Translate, settings)

        assertEquals(RecordRequest(RecordingMode.REAL_TIME_TRANSLATION, null, "de", InsightStrategy.REAL_TIME), request)
    }

    @Test
    fun `a target language the app does not offer becomes English`() {
        val request = RecordRequest.of(RecordTarget.Translate, AppSettings(translationTargetLanguage = "it"))

        assertEquals("en", request.outputLanguage)
    }

    @Test
    fun `a category in the translation mode translates too`() {
        val translating = category("translating", RecordingMode.REAL_TIME_TRANSLATION)

        val request = RecordRequest.of(RecordTarget.Category(translating), AppSettings(translationTargetLanguage = "de"))

        assertEquals("translating", request.categoryId)
        assertEquals("de", request.outputLanguage)
    }

    @Test
    fun `a voice note without category is simple listening`() {
        val request = RecordRequest.of(RecordTarget.VoiceNote, AppSettings(simpleListeningDefaultStrategy = InsightStrategy.END_OF_SESSION))

        assertEquals(RecordRequest(RecordingMode.SIMPLE_LISTENING, null, null, InsightStrategy.END_OF_SESSION), request)
    }

    @Test
    fun `every mode takes its own default strategy`() {
        val settings = AppSettings(
            simpleListeningDefaultStrategy = InsightStrategy.END_OF_SESSION,
            shortMeetingDefaultStrategy = InsightStrategy.REAL_TIME,
            longMeetingDefaultStrategy = InsightStrategy.END_OF_SESSION,
            translationDefaultStrategy = InsightStrategy.REAL_TIME
        )

        assertEquals(InsightStrategy.END_OF_SESSION, settings.insightStrategyFor(RecordingMode.SIMPLE_LISTENING))
        assertEquals(InsightStrategy.REAL_TIME, settings.insightStrategyFor(RecordingMode.SHORT_MEETING))
        assertEquals(InsightStrategy.END_OF_SESSION, settings.insightStrategyFor(RecordingMode.LONG_MEETING))
        assertEquals(InsightStrategy.REAL_TIME, settings.insightStrategyFor(RecordingMode.REAL_TIME_TRANSLATION))
    }
}
