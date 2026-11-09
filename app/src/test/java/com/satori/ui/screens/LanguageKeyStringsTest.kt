package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The words of the recorder's language key: its labels, the same in both languages, Malayalam as
 * its letter ma and never "ML"; and what TalkBack says of it, the language in full and the other one
 * a tap switches to, in English and in German, German without du or Sie.
 */
class LanguageKeyStringsTest {

    private val language = "strings_grille_language.xml"
    private val recorder = "strings_grille_recorder.xml"

    @Test
    fun `the key's labels are the codes and Malayalam's letter, kept out of translation`() {
        assertEquals("EN/DE", text("values", language, "g_language_key_en_de"))
        assertEquals("EN", text("values", language, "g_language_key_en"))
        assertEquals("DE", text("values", language, "g_language_key_de"))
        assertEquals("\u0D2E", text("values", language, "g_language_key_ml"))
        val labels = listOf("g_language_key_en_de", "g_language_key_en", "g_language_key_de", "g_language_key_ml")
        for (name in labels) {
            assertEquals(name, "false", string("values", language, name).getAttribute("translatable"))
            assertFalse(name, names("values-de", language).contains(name))
        }
        assertFalse(labels.any { "ML" in text("values", language, it).uppercase() })
    }

    @Test
    fun `TalkBack says the language in full and what a tap switches to, in English and German`() {
        assertEquals("Language: %1\$s. Switch to %2\$s", text("values", language, "g_language_key_cd"))
        assertEquals("Sprache: %1\$s. Auf %2\$s umstellen", text("values-de", language, "g_language_key_cd"))
        // The names it is said with, both languages' own.
        assertEquals("English or German", text("values", language, "g_language_en_de"))
        assertEquals("Englisch oder Deutsch", text("values-de", language, "g_language_en_de"))
        assertEquals("Malayalam", text("values-de", language, "g_language_ml"))
        assertEquals("English", text("values", recorder, "g_rec_language_en"))
        assertEquals("Englisch", text("values-de", recorder, "g_rec_language_en"))
        assertEquals("German", text("values", recorder, "g_rec_language_de"))
        assertEquals("Deutsch", text("values-de", recorder, "g_rec_language_de"))
    }

    @Test
    fun `the German says it without du or Sie, and every key string the English has to translate is in German`() {
        val words = text("values-de", language, "g_language_key_cd").split(Regex("[^\\p{L}]+"))
        assertFalse(words.any { it in setOf("du", "Du", "dich", "dein", "Sie", "Ihnen", "Ihr", "Ihre") })
        val translatable = names("values", language).filter { string("values", language, it).getAttribute("translatable") != "false" }
        assertTrue(names("values-de", language).containsAll(translatable))
    }

    private fun strings(folder: String, file: String): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/$file"))
            .getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun names(folder: String, file: String): List<String> = strings(folder, file).map { it.getAttribute("name") }

    private fun string(folder: String, file: String, name: String): Element = strings(folder, file).single { it.getAttribute("name") == name }

    private fun text(folder: String, file: String, name: String): String = string(folder, file, name).textContent.replace("\\'", "'")
}
