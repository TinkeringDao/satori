package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The character classes against the reference's own (char_classes.tsv: regex 2026.9.29 and
 * Python's str.isspace), over every code point. `\w` and `\d` come from the platform's Unicode
 * tables, so they are held to the reference wherever the JVM knows the code point.
 */
class PythonTextTest {

    private val table = Golden.rows("char_classes.tsv").associate { it.string("class") to it.string("ranges") }

    private fun ranges(name: String): List<IntRange> = table.getValue(name).split(' ').map { range ->
        val (start, end) = range.split('-').map { it.toInt(16) }
        start..end
    }

    private fun mismatches(name: String, assignedOnly: Boolean, test: (Int) -> Boolean): List<Int> {
        val inside = BooleanArray(0x110000)
        for (range in ranges(name)) for (cp in range) inside[cp] = true
        return (0 until 0x110000).filter { cp ->
            cp !in 0xD800..0xDFFF &&
                (!assignedOnly || Character.getType(cp) != Character.UNASSIGNED.toInt()) &&
                inside[cp] != test(cp)
        }
    }

    @Test
    fun `word characters are the reference's on every code point the JVM knows`() {
        assertEquals(emptyList<Int>(), mismatches("regex_word", assignedOnly = true, PythonText::isWord))
    }

    @Test
    fun `digits are the reference's on every code point the JVM knows`() {
        assertEquals(emptyList<Int>(), mismatches("regex_digit", assignedOnly = true, PythonText::isDigit))
    }

    @Test
    fun `white space is the reference's everywhere`() {
        assertEquals(emptyList<Int>(), mismatches("regex_space", assignedOnly = false, PythonText::isRegexSpace))
        assertEquals(emptyList<Int>(), mismatches("python_space", assignedOnly = false, PythonText::isPythonSpace))
    }

    @Test
    fun `strip and split take the separators that regex leaves alone`() {
        assertEquals("a b", PythonText.strip("\u001C\u00A0 a b\u3000\u001F"))
        assertEquals(listOf("a", "b", "c"), PythonText.split("\u001Ea\u0085b \u2028c "))
        assertEquals(emptyList<String>(), PythonText.split(" \t "))
        assertEquals("a b\u001Cc", PythonText.collapseSpace("a \u00A0\tb\u001Cc"))
        assertTrue(!PythonText.isWord(-1) && !PythonText.isDigit(-1))
    }
}
