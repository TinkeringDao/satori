package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Malayalam recording's notes, name and to-dos are written in English, and the app says so
 * nowhere (the owner's decision of 2026-10-15): no text of the app names Malayalam together with
 * English or German in a text about notes. A text that has to name all three for another reason
 * goes in [allowed], with that reason; there is none today.
 */
class NotesLanguageUnsaidTest {

    private val notes = Regex("\\b(notes?|Notiz|Notizen)\\b", RegexOption.IGNORE_CASE)
    private val englishOrGerman = Regex("English|German|Englisch|Deutsch|englisch|deutsch")

    /** Texts that name Malayalam, notes and English or German, but not the notes' language. */
    private val allowed = emptySet<String>()

    @Test
    fun `no text says in which language a Malayalam recording's notes are`() {
        for (folder in listOf("values", "values-de")) {
            val saying = StringsFiles.all(folder)
                .filterKeys { it.substringAfter('/').startsWith("g_") && it !in allowed }
                .filterValues { "Malayalam" in it && notes.containsMatchIn(it) && englishOrGerman.containsMatchIn(it) }
            assertTrue("$folder: $saying", saying.isEmpty())
        }
    }

    @Test
    fun `the check finds the line the recorder used to show`() {
        val old = "This recording is in Malayalam. Its notes are written in English when you stop."
        assertTrue("Malayalam" in old && notes.containsMatchIn(old) && englishOrGerman.containsMatchIn(old))
        assertEquals(
            "This recording is in Malayalam. Its notes are written when you stop.",
            StringsFiles.all("values")["strings_grille_recorder.xml/g_rec_notes_after_ml"]
        )
    }
}
