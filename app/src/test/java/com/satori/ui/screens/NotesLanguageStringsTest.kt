package com.satori.ui.screens

import com.satori.domain.model.NotesLanguage
import com.satori.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Notes language says only what is always true and names no language whose notes are English (the
 * owner, 2026-10-15): Auto is "English or German, as spoken; English otherwise", which holds for a
 * Malayalam recording too, and the German label reads "Automatisch", not the word for car.
 */
class NotesLanguageStringsTest {

    private val en = StringsFiles.all("values")
    private val de = StringsFiles.all("values-de")

    private val lines = listOf(
        "g_settings_notes_language_desc",
        "g_settings_notes_language_help",
        "g_settings_notes_language_auto_desc",
        "g_settings_notes_language_auto_desc_spoken",
        "g_settings_notes_language_en_desc",
        "g_settings_notes_language_de_desc"
    ).map { "strings_grille_settings.xml/$it" }

    @Test
    fun `Auto's line says English or German as spoken, and English otherwise`() {
        assertEquals("English or German, as spoken; English otherwise", en.getValue("strings_grille_settings.xml/g_settings_notes_language_auto_desc"))
        assertEquals("Englisch oder Deutsch, wie gesprochen; sonst Englisch", de.getValue("strings_grille_settings.xml/g_settings_notes_language_auto_desc"))
        assertEquals("Notes in English", en.getValue("strings_grille_settings.xml/g_settings_notes_language_en_desc"))
        assertEquals("Notizen auf Deutsch", de.getValue("strings_grille_settings.xml/g_settings_notes_language_de_desc"))
        assertEquals("For each recording's notes, name and to-dos.", en.getValue("strings_grille_settings.xml/g_settings_notes_language_help"))
        assertEquals("Automatisch", de.getValue("strings_grille_settings.xml/g_settings_notes_language_auto"))
    }

    @Test
    fun `no line of the sheet or the row names Malayalam, in either language`() {
        for (texts in listOf(en, de)) {
            for (key in lines) {
                val text = texts.getValue(key)
                assertFalse("$key: $text", "Malayalam" in text)
            }
        }
    }

    @Test
    fun `the Auto line follows the rule for other languages`() {
        assertEquals(R.string.g_settings_notes_language_auto_desc, notesLanguageLineRes(NotesLanguage.AUTO, otherLanguagesInEnglish = true))
        assertEquals(R.string.g_settings_notes_language_auto_desc_spoken, notesLanguageLineRes(NotesLanguage.AUTO, otherLanguagesInEnglish = false))
        assertEquals(R.string.g_settings_notes_language_en_desc, notesLanguageLineRes(NotesLanguage.EN))
        assertEquals(R.string.g_settings_notes_language_de_desc, notesLanguageLineRes(NotesLanguage.DE))
    }
}
