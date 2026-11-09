package com.satori.domain.language

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WordClasses.NOT_A_WORD_CHAR] reads by Unicode on the JVM, as ICU's \W does on the phone; the
 * instrumented IcuRegexTest checks the phone's side.
 */
class WordClassesTest {

    private val notAWordChar = Regex(WordClasses.NOT_A_WORD_CHAR)

    @Test
    fun `letters of any script, their marks, digits, the connector and the joiners are a word's`() {
        // Ü, ß, a Malayalam letter, its vowel sign and its virama, a Malayalam digit, ZWNJ, ZWJ.
        for (c in listOf("a", "Z", "Ü", "ß", "ക", "ു", "്", "7", "൭", "_", "\u200C", "\u200D")) {
            assertFalse(c, notAWordChar.matches(c))
        }
    }

    @Test
    fun `spaces, punctuation and symbols are no word's`() {
        for (c in listOf(" ", "\u00A0", "-", "*", "•", ":", "„", "“", "'", "#", "\$")) assertTrue(c, notAWordChar.matches(c))
    }

    @Test
    fun `before a label it takes the bullet and leaves the whole word, as ICU's W does`() {
        val label = Regex("""^${WordClasses.NOT_A_WORD_CHAR}*([\p{L}\p{M}']+)\s*:""")
        assertEquals("Übersicht", label.find("- Übersicht: Plan")?.groupValues?.get(1))
        assertEquals("കുറിപ്പ്", label.find("* കുറിപ്പ്: Plan")?.groupValues?.get(1))
    }
}
