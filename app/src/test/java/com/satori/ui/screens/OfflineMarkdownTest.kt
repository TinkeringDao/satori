package com.satori.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineMarkdownTest {

    @Test
    fun `images and links become their text`() {
        assertEquals("See chart and the plan", offlineMarkdown("See ![chart](https://example.com/c.png) and [the plan](https://example.com)"))
        assertEquals("Mail me at mailto:a@b.c", offlineMarkdown("Mail me at <mailto:a@b.c>"))
    }

    @Test
    fun `everything else is left alone`() {
        val notes = "## Next steps\n- **Call** the bank [tomorrow]\n- Budget 12% (marketing)\n1 < 2 > 0"
        assertEquals(notes, offlineMarkdown(notes))
    }
}
