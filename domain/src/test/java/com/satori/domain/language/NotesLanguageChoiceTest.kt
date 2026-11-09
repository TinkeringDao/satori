package com.satori.domain.language

import com.satori.domain.model.NotesLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesLanguageChoiceTest {

    private fun auto(detected: String?, stored: String?, device: String?) =
        NotesLanguageChoice.choose(NotesLanguage.AUTO, detected, stored, device)

    @Test
    fun `a forced language wins over everything else`() {
        assertEquals("en", NotesLanguageChoice.choose(NotesLanguage.EN, "de", "de", "de"))
        assertEquals("de", NotesLanguageChoice.choose(NotesLanguage.DE, "en", "en", "en"))
    }

    @Test
    fun `auto goes by the text first`() {
        assertEquals("de", auto(detected = "de", stored = "en", device = "en"))
        assertEquals("en", auto(detected = "en", stored = "de", device = "de"))
    }

    @Test
    fun `auto keeps the stored language when the text says nothing`() {
        assertEquals("de", auto(detected = null, stored = "de", device = "en"))
    }

    @Test
    fun `auto falls back to the phone's language`() {
        assertEquals("de", auto(detected = null, stored = null, device = "de"))
        assertEquals("en", auto(detected = null, stored = null, device = "en"))
    }

    @Test
    fun `auto ends with English when nothing is German or English`() {
        assertEquals("en", auto(detected = null, stored = null, device = "fr"))
        assertEquals("en", auto(detected = null, stored = null, device = null))
    }

    @Test
    fun `a stored language the app does not write in is passed over`() {
        assertEquals("de", auto(detected = null, stored = "it", device = "de"))
        assertEquals("en", auto(detected = null, stored = "", device = "it"))
    }

    @Test
    fun `a Malayalam text is written about in English, whatever the phone's language and the setting`() {
        assertEquals("en", auto(detected = "ml", stored = null, device = "de"))
        assertEquals("en", auto(detected = "ml", stored = "de", device = "de"))
        // The owner reads a Malayalam recording in English, as the recorder tells him: German
        // chosen in the settings is for English and German recordings.
        assertEquals("en", NotesLanguageChoice.choose(NotesLanguage.DE, "ml", null, "de"))
        assertEquals("en", NotesLanguageChoice.choose(NotesLanguage.EN, "ml", null, "de"))
        assertEquals("de", NotesLanguageChoice.choose(NotesLanguage.DE, "en", null, "en"))
    }

    @Test
    fun `the app writes in English and German only`() {
        assertEquals(true, NotesLanguageChoice.writesIn("en"))
        assertEquals(true, NotesLanguageChoice.writesIn("DE"))
        assertEquals(false, NotesLanguageChoice.writesIn("ml"))
        assertEquals(false, NotesLanguageChoice.writesIn(null))
    }

    @Test
    fun `language codes are not case sensitive`() {
        assertEquals("de", auto(detected = null, stored = null, device = "DE"))
    }
}
