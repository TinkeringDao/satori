package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * No string of the app says that a Malayalam recording's notes, name or to-dos are English (the
 * owner's rule, 2026-10-15; since 0.8.0 they are written from its lines' English readings, design
 * R6): no string names Malayalam beside English but the two about English words written in
 * Malayalam letters, the speech model's line and the order the AI gets when it reads Malayalam
 * itself, and neither of those speaks of notes, a name or to-dos. In English and in German.
 */
class MalayalamNotesStringsTest {

    private val lettersLines = setOf("strings_grille_ai.xml/prompt_malayalam_letters", "strings_grille_speech.xml/g_speech_ml_desc")

    private val english = listOf("English", "Englisch", "englisch")

    private val ofNotes = listOf("note", "Notiz", "name", "Name", "to-do", "Aufgabe", "Idee", "idea")

    @Test
    fun `no string names Malayalam beside English but the lines on English words in Malayalam letters, which speak of no notes`() {
        for (folder in listOf("values", "values-de")) {
            val both = StringsFiles.all(folder).filter { (_, text) -> "Malayalam" in text && english.any { it in text } }

            assertEquals(folder, emptySet<String>(), both.keys - lettersLines)
            for ((key, text) in both) assertFalse("$folder $key: $text", ofNotes.any { it in text })
        }
    }
}
