package com.satori.domain.language

import com.satori.domain.fakes.FakeSettingsRepository
import com.satori.domain.fakes.FakeTranscriptionRepository
import com.satori.domain.fakes.session
import com.satori.domain.model.AppSettings
import com.satori.domain.model.NotesLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notes language Auto writes English or German, as spoken, and English for any other language
 * (the owner's decision of 2026-10-16, question 2 of the 0.7.0 brief): so its line, "English or
 * German, as spoken; English otherwise", is true without naming Malayalam. The detector tells a
 * text clearly in another language as surely as it tells English from German, and says nothing of
 * a text it is unsure about. Synthetic sentences only.
 */
class OtherLanguageNotesTest {

    private companion object {
        /** Enough words for the detector to answer at all ([LanguageDetector.MIN_WORDS]). */
        val MIN_WORDS_FOR_TEST = LanguageDetector.MIN_WORDS + 2
    }

    private val french = "Nous avons décidé de reporter la réunion à mardi, et elle prépare le plan pour la semaine prochaine."
    private val spanish = "Hemos decidido mover la reunión al martes, y ella prepara el plan para la semana que viene."
    private val dutch = "We hebben besloten de vergadering niet naar dinsdag te verplaatsen, en zij maakt het plan ook voor de week."
    private val polish = "Postanowiliśmy przenieść spotkanie na wtorek, a ona przygotuje plan na przyszły tydzień, jak zawsze."
    private val russian = "Мы решили перенести встречу на вторник, и она подготовит план на следующую неделю."
    private val english = "We agreed to move the launch to March, and the team will update the plan before Friday."
    private val german = "Wir müssen den Termin mit dem Vermieter nicht verschieben, weil die Heizung noch kaputt ist."
    private val englishWithAPlace = "We met at the café de la Paix in the evening and we talked about the plan for the week."
    private val malayalam = "ഇന്ന് വൈകുന്നേരം കടയിൽ പോയി പാലും റൊട്ടിയും വാങ്ങണം"

    @Test
    fun `a text clearly in another language is told, in Latin letters and in other scripts`() {
        for (text in listOf(french, spanish, dutch, polish, russian)) {
            assertTrue(text, LanguageDetector.isOtherLanguage(text))
            assertEquals(text, LanguageDetector.OTHER, NotesLanguageChoice.detect(text))
        }
    }

    @Test
    fun `English, German, Malayalam and a short text are not another language`() {
        for (text in listOf(english, german, englishWithAPlace, malayalam, "Merci beaucoup", "Le plan est")) {
            assertFalse(text, LanguageDetector.isOtherLanguage(text))
        }
        assertEquals("en", NotesLanguageChoice.detect(english))
        assertEquals("de", NotesLanguageChoice.detect(german))
        assertEquals("ml", NotesLanguageChoice.detect(malayalam))
        assertNull(NotesLanguageChoice.detect("Okay, danke dir"))
    }

    @Test
    fun `spoken German the detector is unsure of is never another language`() {
        // "nie", "je", "Mai" and "Ei" are German as well as Polish, French, Romanian or Finnish, and
        // spoken German has "ne", "ma", "nu", "och" and "se"; a German recording keeps its fallbacks.
        val spoken = "Och ne, nie wieder, guck ma, je nachdem, im Mai, ein Ei, nu ja, hat se gesagt"

        assertFalse(LanguageDetector.isOtherLanguage(spoken))
        assertTrue(NotesLanguageChoice.detect(spoken) != LanguageDetector.OTHER)
        for (word in listOf("nie", "je", "mai", "ei", "ne", "ma", "nu", "och", "se", "dat", "wat", "ik")) {
            assertFalse(word, LanguageDetector.isOtherLanguage(List(MIN_WORDS_FOR_TEST) { word }.joinToString(" ")))
        }
    }

    @Test
    fun `Auto writes about another language in English, also on a German phone with German stored`() {
        assertTrue(NotesLanguageChoice.OTHER_LANGUAGES_IN_ENGLISH)
        assertEquals("en", NotesLanguageChoice.choose(NotesLanguage.AUTO, LanguageDetector.OTHER, stored = "de", device = "de"))
        // A chosen language still wins.
        assertEquals("de", NotesLanguageChoice.choose(NotesLanguage.DE, LanguageDetector.OTHER, stored = null, device = "en"))
        // Unsure, the fallbacks stay: the stored language, then the phone's.
        assertEquals("de", NotesLanguageChoice.choose(NotesLanguage.AUTO, null, stored = null, device = "de"))
    }

    @Test
    fun `a French recording on a German phone gets English notes, and nothing is stored`() = runBlocking {
        val repository = FakeTranscriptionRepository(session())
        val resolver = NotesLanguageResolver(FakeSettingsRepository(AppSettings(notesLanguage = NotesLanguage.AUTO)), repository) { "de" }

        assertEquals("en", resolver.resolve("s1", french))
        assertNull(repository.session?.outputLanguage)
    }

    @Test
    fun `the other language is never a language the app writes in`() {
        assertFalse(NotesLanguageChoice.writesIn(LanguageDetector.OTHER))
    }
}
