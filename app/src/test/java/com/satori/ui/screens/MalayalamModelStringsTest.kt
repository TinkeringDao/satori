package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Malayalam model's row in Advanced settings: its name, "Malayalam (AI4Bharat)" in both
 * languages, what it is for and its size, in English and in German, German in the neutral
 * infinitive style, without du or Sie.
 */
class MalayalamModelStringsTest {

    private val speech = "strings_grille_speech.xml"

    @Test
    fun `the model is named Malayalam (AI4Bharat), the same in German`() {
        assertEquals("Malayalam (AI4Bharat)", text("values", speech, "g_speech_ml"))
        assertFalse("one name for both languages", names("values-de", speech).contains("g_speech_ml"))
        assertFalse(names("values-de", speech).contains("g_speech_size_ml"))
    }

    @Test
    fun `its line says what it is for, its redo after Stop too, and its size, in both languages`() {
        assertEquals(
            "For recordings in Malayalam: while recording, again after Stop, and with Transcribe again. " +
                "Writes English words in Malayalam letters. About %1\$s.",
            text("values", speech, "g_speech_ml_desc")
        )
        assertEquals(
            "Für Aufnahmen auf Malayalam: während der Aufnahme, nach dem Stoppen noch einmal und mit „Neu transkribieren“. " +
                "Schreibt englische Wörter in Malayalam-Schrift. Etwa %1\$s.",
            text("values-de", speech, "g_speech_ml_desc")
        )
        // Its size comes from the registry's bytes, as on the first run (ModelSizesTest): no written one.
        assertFalse(names("values", speech).contains("g_speech_size_ml"))
        for (folder in listOf("values", "values-de")) {
            assertEquals(folder, 1, text(folder, speech, "g_speech_ml_desc").split("%1\$s").size - 1)
        }
    }

    @Test
    fun `the help of the speech models names it, in both languages`() {
        assertTrue(text("values", speech, "g_speech_help").contains("Malayalam (AI4Bharat) the recordings in Malayalam"))
        assertTrue(text("values-de", speech, "g_speech_help").contains("Malayalam (AI4Bharat) die Aufnahmen auf Malayalam"))
    }

    @Test
    fun `German says it without du or Sie`() {
        for (name in listOf("g_speech_ml_desc", "g_speech_help")) {
            val words = text("values-de", speech, name).split(Regex("[^\\p{L}]+"))
            assertFalse(name, words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        }
    }

    private fun strings(folder: String, file: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun names(folder: String, file: String): Set<String> = strings(folder, file).map { it.getAttribute("name") }.toSet()

    /**
     * The text of the string [name] in the app's res/[folder]/[file], with its escapes read as
     * aapt2 reads them. Gradle runs the tests in the module's folder.
     */
    private fun text(folder: String, file: String, name: String): String =
        strings(folder, file).single { it.getAttribute("name") == name }
            .textContent.replace("\\'", "'").replace("\\u00A0", "\u00A0")
}
