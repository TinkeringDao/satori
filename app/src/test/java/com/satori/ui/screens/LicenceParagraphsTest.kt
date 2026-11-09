package com.satori.ui.screens

import com.satori.domain.model.AppLicenses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A licence text is drawn as paragraphs that wrap to the screen: the source's line breaks inside a
 * paragraph go, a blank line between two stays, and so does a line the text sets apart.
 */
class LicenceParagraphsTest {

    @Test
    fun `the lines of a paragraph are joined and blank lines kept`() {
        val text = "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files.\n\nThe above copyright notice stays."
        assertEquals(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and " +
                "associated documentation files.\n\nThe above copyright notice stays.",
            LicenceParagraphs.of(text)
        )
    }

    @Test
    fun `headings, rules, addresses, indented lines and list items stay on their own lines`() {
        val text = "PREAMBLE\nThe goals of the licence\nare these.\n" +
            "-----\nSIL OPEN FONT LICENSE Version 1.1\n-----\n" +
            "available with a FAQ at:\nhttps://openfontlicense.org\n" +
            "You may obtain a copy at\n    http://www.apache.org/licenses/LICENSE-2.0\n" +
            "1) Neither the Font Software\nmay be sold by itself."
        assertEquals(
            "PREAMBLE\nThe goals of the licence are these.\n" +
                "-----\nSIL OPEN FONT LICENSE Version 1.1\n-----\n" +
                "available with a FAQ at:\nhttps://openfontlicense.org\n" +
                "You may obtain a copy at\n    http://www.apache.org/licenses/LICENSE-2.0\n" +
                "1) Neither the Font Software may be sold by itself.",
            LicenceParagraphs.of(text)
        )
    }

    @Test
    fun `a long sentence in capitals is no heading`() {
        val text = "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\nIMPLIED."
        assertEquals("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED.", LicenceParagraphs.of(text))
    }

    @Test
    fun `every licence of the page keeps its words and loses only line breaks`() {
        for (entry in AppLicenses.ALL.filter { it.fullText.isNotBlank() }) {
            val drawn = LicenceParagraphs.of(entry.fullText)
            assertEquals(entry.id, words(entry.fullText), words(drawn))
            // The MIT and Apache terms read as one paragraph each, not as 80-column lines.
            assertFalse(entry.id, "software\ndistributed" in drawn)
            assertFalse(entry.id, "a copy\nof this software" in drawn)
        }
        assertTrue(LicenceParagraphs.of(AppLicenses.ALL.first { it.id == "silero_vad" }.fullText).contains("obtaining a copy of this software"))
    }

    private fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
}
