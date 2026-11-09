package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceholdersTest {

    @Test
    fun `the keys are the real map's, in its order, for serials of one and two digits`() {
        val golden = Golden.rows("placeholder_keys.tsv").single()
        val placeholders = IndicPreprocessor.preprocess(golden.string("input")).placeholders

        assertEquals(12, placeholders.matches.size)
        // A Python dict keeps a key's first place: 231 keys per serial of the 247 written.
        assertEquals(golden.strings("keys"), placeholders.entries.map { it.first })
        assertEquals(231 * 12, placeholders.entries.size)
    }

    @Test
    fun `restore puts each text back in the forms the model may write`() {
        val placeholders = Placeholders(listOf("10:30", "a.b@example.com"))

        assertEquals("At 10:30, mail a.b@example.com", placeholders.restore("At < ID1 >, mail [ID2]"))
        assertEquals("10:30 and a.b@example.com", placeholders.restore("<id1> and < ID2]"))
        assertEquals("nothing to restore", placeholders.restore("nothing to restore"))
    }

    @Test
    fun `a key that starts another serial's is replaced first, as in the reference`() {
        // "ID 1" in Devanagari letters is a key of the first placeholder and starts "ID 10" spelled
        // so; the reference replaces the first serial's keys first, and so does the port.
        val placeholders = Placeholders(listOf("first") + (2..9).map { "p$it" } + "tenth")

        assertEquals("tenth", placeholders.restore("<ID10>"))
        assertEquals("first0", placeholders.restore("आईडी 10"))
    }
}
