package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Perl tables in the code against sacremoses 0.2.0's own (char_classes.tsv). */
class MosesTablesTest {

    private val table = Golden.rows("char_classes.tsv").associate { it.string("class") to it.string("ranges") }

    private fun flat(name: String): List<Int> = table.getValue(name).split(' ').flatMap { it.split('-').map { h -> h.toInt(16) } }

    @Test
    fun `the tables are sacremoses' own`() {
        assertEquals(flat("moses_alpha"), MosesTables.ALPHA.toList())
        assertEquals(flat("moses_currency"), MosesTables.CURRENCY.toList())
        assertEquals(flat("moses_cjk"), MosesTables.CJK.toList())
    }

    @Test
    fun `a range holds both of its ends and nothing beside them`() {
        assertTrue(MosesTables.contains(MosesTables.ALPHA, 'A'.code))
        assertTrue(MosesTables.contains(MosesTables.ALPHA, 'Z'.code))
        assertFalse(MosesTables.contains(MosesTables.ALPHA, '['.code))
        assertFalse(MosesTables.contains(MosesTables.ALPHA, '@'.code))
        assertTrue(MosesTables.contains(MosesTables.CJK, 0x1100))
        assertTrue(MosesTables.contains(MosesTables.CJK, 0x2FFFF))
        assertFalse(MosesTables.contains(MosesTables.CJK, 0x30000))
        assertFalse(MosesTables.contains(MosesTables.CURRENCY, 0x20BF))
        assertTrue(MosesTables.contains(MosesTables.CURRENCY, 0x20B9))
    }
}
