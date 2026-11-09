package com.satori.domain.usecase.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSnippetTest {

    @Test
    fun `a short text is kept whole`() {
        assertEquals("Engineering is under budget.", SearchSnippet.of("Engineering is under budget.", "budget"))
    }

    @Test
    fun `a long text is cut around the match at word boundaries`() {
        val before = "word ".repeat(40)
        val after = " word".repeat(40)
        val text = before + "budget" + after

        val snippet = SearchSnippet.of(text, "budget")

        assertTrue(snippet.contains("budget"))
        assertTrue(snippet.length <= 140)
        assertTrue(snippet.startsWith("word"))
        assertTrue(snippet.endsWith("word"))
        assertFalse(snippet.startsWith(" "))
    }

    @Test
    fun `the match is found whatever its case`() {
        assertTrue(SearchSnippet.of("${"x ".repeat(80)}Ärger im Büro ${"y ".repeat(80)}", "ärger").contains("Ärger"))
    }

    @Test
    fun `a cut never goes inside the match or leaves nothing`() {
        val longWord = "a".repeat(200) + "budget" + "b".repeat(200)

        val snippet = SearchSnippet.of(longWord, "budget")

        assertTrue(snippet.contains("budget"))
    }

    @Test
    fun `without the query the text starts the snippet`() {
        val text = "word ".repeat(60)

        assertEquals(text.trim().take(120), SearchSnippet.of(text, "budget"))
    }

    @Test
    fun `a note loses its Markdown marks`() {
        val note = "**Q4 budget** status\n- Check the licence renewals\n- Send the list by Friday"

        assertEquals(
            "Q4 budget status Check the licence renewals Send the list by Friday",
            SearchSnippet.of(note, "budget", markdown = true)
        )
    }

    @Test
    fun `a spoken line keeps its characters, an ordinal date at the start included`() {
        assertEquals("15. November ist der Umzug", SearchSnippet.of("15. November ist der Umzug", "Umzug"))
    }

    @Test
    fun `plain text turns lines into one line`() {
        assertEquals("one two", SearchSnippet.plain("# one\n\n  two  "))
    }
}
