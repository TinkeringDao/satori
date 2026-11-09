package com.satori.ui.screens

import com.satori.domain.usecase.llm.GenerateFinalInsightUseCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A recording too short for notes says so on home with the number of words notes need (design 3.8,
 * E3), the notes' own threshold, in English and German.
 */
class HomeNotesTooShortTest {

    private val needed = GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT

    @Test
    fun `the line names the words notes need, as the design words it`() {
        assertEquals("Too short for notes: about 20 words are needed", String.format(Locale.ENGLISH, plural("values", "other"), needed))
        assertEquals("Zu kurz für Notizen: etwa 20 Wörter werden gebraucht", String.format(Locale.GERMAN, plural("values-de", "other"), needed))
    }

    @Test
    fun `one word reads as one, in both languages`() {
        assertEquals("Too short for notes: about 1 word is needed", String.format(Locale.ENGLISH, plural("values", "one"), 1))
        assertEquals("Zu kurz für Notizen: etwa 1 Wort wird gebraucht", String.format(Locale.GERMAN, plural("values-de", "one"), 1))
    }

    /** The [quantity] item of g_home_notes_too_short in res/[folder]; Gradle runs the tests in the module's folder. */
    private fun plural(folder: String, quantity: String): String {
        val plurals = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings_grille_home.xml"))
            .getElementsByTagName("plurals")
        val tooShort = (0 until plurals.length).map { plurals.item(it) as Element }.single { it.getAttribute("name") == "g_home_notes_too_short" }
        val items = tooShort.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.single { it.getAttribute("quantity") == quantity }.textContent
    }
}
