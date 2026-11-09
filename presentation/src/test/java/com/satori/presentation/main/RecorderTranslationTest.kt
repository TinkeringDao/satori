package com.satori.presentation.main

import com.satori.domain.model.AppSettings
import com.satori.domain.model.LlmInsight
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the recorder says of a Translate recording: which language goes in and which comes out, a
 * warning when they are the same, and on the cover the newest translation with the lines it came
 * from.
 */
class RecorderTranslationTest {

    private fun line(id: String, text: String, at: Long, complete: Boolean = true) =
        TranscriptionSegment(id = id, sessionId = "s1", text = text, timestamp = at, isComplete = complete)

    private fun translation(id: String, text: String, at: Long, vararg sources: String) =
        LlmInsight(id = id, sessionId = "s1", title = null, content = text, timestamp = at, sourceSegmentIds = sources.toList())

    // The synthetic German meeting feed's first line (de-besprechung.wav).
    private val german = line(
        "g1",
        "Guten Morgen zusammen, fangen wir mit der Wochenplanung an, erstens der Umzug ins neue Büro. Die Möbel werden am Montag geliefert.",
        at = 1
    )

    @Test
    fun `German going into English is said as such, once the app is sure of the German`() {
        val languages = TranslationLanguages.of(listOf(german), target = "en")

        assertEquals("de", languages.spoken)
        assertFalse(languages.same)
        // Before a finished line nothing is sure: only the language that comes out is said.
        assertNull(TranslationLanguages.of(listOf(line("p", "Guten Morgen zusammen", at = 1, complete = false)), "en").spoken)
    }

    @Test
    fun `English said into an English translation is the same language, which the recorder warns of`() {
        val english = line("e1", "Good morning everyone, let's get started with the weekly planning meeting and the budget.", at = 1)

        assertTrue(TranslationLanguages.of(listOf(english), target = "en").same)
        assertFalse(TranslationLanguages.of(listOf(english), target = "de").same)
    }

    @Test
    fun `the language is read from the newest finished lines, however long the recording`() {
        val english = line("e1", "Good morning everyone, let's get started with the weekly planning meeting and the budget.", at = 1)
        val hourOfGerman = (1..400).map { line("g$it", german.text, at = it.toLong()) }

        // An hour of German, then English: what is said now is what the recorder says.
        assertEquals("de", TranslationLanguages.of(hourOfGerman, target = "en").spoken)
        val switched = hourOfGerman + (401..440).map { line("e$it", english.text, at = it.toLong()) }
        assertTrue(TranslationLanguages.of(switched, target = "en").same)
        // An unfinished line is not read, nor more than the newest lines.
        val read = TranslationLanguages.of(hourOfGerman + line("p", english.text, at = 999, complete = false), "en")
        assertEquals("de", read.spoken)
        assertTrue(TranslationLanguages.MAX_CHARS < hourOfGerman.sumOf { it.text.length })
    }

    @Test
    fun `the cover shows the newest translation, with the lines it was translated from`() {
        val second = line("g2", "Das passt zum Plan.", at = 2)
        val third = line("g3", "Für die Messe brauchen wir zusätzlich 2000 Euro.", at = 3)
        val translations = listOf(
            translation("t2", "It fits the plan. We need another 2000 euros for the trade fair.", at = 30, "g2", "g3"),
            translation("t1", "Good morning everyone.", at = 10, "g1")
        )

        val latest = LatestTranslation.of(translations, listOf(german, second, third))

        assertEquals(
            LatestTranslation("It fits the plan. We need another 2000 euros for the trade fair.", "${second.text} ${third.text}"),
            latest
        )
        assertNull(LatestTranslation.of(emptyList(), listOf(german)))
        // A line no longer on screen leaves the source without it.
        assertEquals("", LatestTranslation.of(listOf(translation("t1", "Good morning.", at = 10, "gone")), listOf(german))?.source)
    }

    @Test
    fun `a Translate recording goes into its own target, else the settings' one, and no other recording translates`() {
        val translate = MainUiState(sessionMode = RecordingMode.REAL_TIME_TRANSLATION)

        assertEquals("de", translate.copy(translationTarget = "de").translatesInto)
        assertEquals("de", translate.copy(settings = AppSettings(translationTargetLanguage = "de")).translatesInto)
        // A target the app can't translate into is English, as a translation starts with it.
        assertEquals("en", translate.copy(settings = AppSettings(translationTargetLanguage = "it")).translatesInto)
        assertNull(MainUiState(sessionMode = RecordingMode.SIMPLE_LISTENING, translationTarget = "de").translatesInto)
    }
}
