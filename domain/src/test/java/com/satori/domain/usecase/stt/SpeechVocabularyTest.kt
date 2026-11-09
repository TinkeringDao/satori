package com.satori.domain.usecase.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVocabularyTest {

    @Test
    fun `one word or phrase per line, trimmed, as written`() {
        assertEquals(
            listOf("Ravi", "Lena", "Gemma 4", "cover screen"),
            SpeechVocabulary.clean("  Ravi \nLena\r\n\nGemma   4\ncover screen  ")
        )
    }

    @Test
    fun `commas and semicolons separate words too`() {
        assertEquals(listOf("Anna", "Satori", "Tasker"), SpeechVocabulary.clean("Anna, Satori; Tasker"))
    }

    @Test
    fun `letters of any language, digits and the marks of names stay`() {
        assertEquals(
            listOf("Jürgen", "Ruiz-Pérez", "O'Brien", "Dr. Weiß", "Łukasz"),
            SpeechVocabulary.clean("Jürgen\nRuiz-Pérez\nO’Brien\nDr. Weiß\nŁukasz")
        )
    }

    @Test
    fun `what sherpa-onnx could misread or log becomes a space`() {
        // A word starting with ':' is read as a boost score, '#' and '@' as other options.
        assertEquals(listOf("2.0 Lena", "tag", "home"), SpeechVocabulary.clean(":2.0 Lena\n#tag\n@home"))
        assertEquals(listOf("Anna Lena"), SpeechVocabulary.clean("Anna/Lena"))
    }

    @Test
    fun `empty lines, marks alone, repeats and overlong lines go`() {
        val long = "x".repeat(SpeechVocabulary.MAX_CHARS + 1)

        assertEquals(listOf("Lena"), SpeechVocabulary.clean("\n - \n...\nLena\nLena\n$long"))
    }

    @Test
    fun `case is kept, so a name and a word are two entries`() {
        assertEquals(listOf("Mark", "mark"), SpeechVocabulary.clean("Mark\nmark"))
    }

    @Test
    fun `at most the maximum is kept, the first ones`() {
        val many = (1..SpeechVocabulary.MAX_WORDS + 5).joinToString("\n") { "Name $it" }

        val words = SpeechVocabulary.clean(many)

        assertEquals(SpeechVocabulary.MAX_WORDS, words.size)
        assertEquals("Name 1", words.first())
    }

    @Test
    fun `the text field shows them one per line, and reads back the same`() {
        val words = listOf("Ravi", "Gemma 4")

        assertEquals("Ravi\nGemma 4", SpeechVocabulary.asText(words))
        assertEquals(words, SpeechVocabulary.clean(SpeechVocabulary.asText(words)))
        assertTrue(SpeechVocabulary.clean("").isEmpty())
    }
}
