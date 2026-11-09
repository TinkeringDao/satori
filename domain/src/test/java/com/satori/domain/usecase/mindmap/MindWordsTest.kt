package com.satori.domain.usecase.mindmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MindWordsTest {

    private fun sameStem(vararg forms: String) {
        val stems = forms.map { MindWords.stem(MindWords.fold(it)) }.toSet()
        assertEquals(forms.joinToString(), 1, stems.size)
    }

    @Test
    fun `folding drops case, accents and umlauts, and writes sharp s as ss`() {
        assertEquals("grosse kuche uber", MindWords.fold("Größe Küche ÜBER"))
        assertEquals("cafe", MindWords.fold("Café"))
    }

    @Test
    fun `words are runs of letters and digits, and an apostrophe ends one`() {
        assertEquals(listOf("anna", "s", "garden", "shed", "2026"), MindWords.words("Anna's garden-shed, 2026!"))
        assertEquals(listOf("anna", "s"), MindWords.words("Anna’s"))
    }

    @Test
    fun `content words leave out the stop words of both languages and single letters`() {
        // Stems are for comparing: "Berlin" loses its "n" like a German plural would.
        assertEquals(listOf("trip", "berli"), MindWords.contentStems("A trip to Berlin"))
        assertEquals(listOf("termi", "arzt"), MindWords.contentStems("Der Termin beim Arzt"))
        assertEquals(listOf("anna", "gard"), MindWords.contentStems("Anna's garden"))
        assertTrue(MindWords.contentStems("the and of der die und mit über für").isEmpty())
    }

    @Test
    fun `words that are a subject in the other language are content words`() {
        for (word in listOf("war", "man", "kind", "hat", "bald")) {
            assertTrue(word, MindWords.isContentWord(word))
        }
        assertFalse(MindWords.isContentWord("x"))
    }

    @Test
    fun `German plural and case endings give one stem`() {
        sameStem("Termin", "Termine", "Terminen")
        sameStem("Haus", "Häuser", "Hauses", "Häusern")
        sameStem("Garten", "Gärten", "Gartens")
        sameStem("Kind", "Kinder", "Kindern")
        sameStem("Wohnung", "Wohnungen")
        sameStem("neu", "neue", "neuen", "neuer", "neues", "neuem")
        sameStem("Ergebnis", "Ergebnisse")
    }

    @Test
    fun `English plurals give one stem, those in ies too, whether the singular ends in y or ie`() {
        sameStem("car", "cars")
        sameStem("box", "boxes")
        sameStem("house", "houses")
        sameStem("computer", "computers")
        sameStem("city", "cities")
        sameStem("party", "parties", "Partys")
        sameStem("movie", "movies")
        sameStem("cookie", "cookies")
        sameStem("family", "families", "Familie", "Familien")
        sameStem("day", "days")
    }

    @Test
    fun `an ending is not stripped below three letters`() {
        assertEquals("bus", MindWords.stem("bus"))
        assertEquals("bus", MindWords.stem("buses"))
        assertEquals("tee", MindWords.stem("tee"))
        assertEquals("80er", MindWords.stem("80er"))
    }

    @Test
    fun `stemming a stem changes nothing`() {
        for (word in listOf("terminen", "hausern", "computers", "cities", "movies", "gartenhauses", "adressen")) {
            val stem = MindWords.stem(word)
            assertEquals(word, stem, MindWords.stem(stem))
        }
    }
}
