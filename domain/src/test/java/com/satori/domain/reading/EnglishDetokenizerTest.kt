package com.satori.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output half against the export and the Python reference: the ids the export's int8 and fp32
 * models wrote for its 158 benchmark fixtures with the English it printed (316 pairs), and
 * synthetic outputs through placeholders and Moses' rules (golden.py).
 */
class EnglishDetokenizerTest {

    private val ids = Golden.pieceIds

    private fun english(row: Golden.Row): String {
        val pieces = if (row.has("ids")) ids.targetPieces(row.ints("ids")) else row.strings("pieces")
        return EnglishDetokenizer.detokenize(pieces, IndicPreprocessor.preprocess(row.string("source")).placeholders)
    }

    @Test
    fun `the export's 316 outputs read as the export printed them`() {
        val rows = Golden.rows("detok_fixtures.tsv")

        assertEquals(316, rows.size)
        val wrong = rows.filter { english(it) != it.string("text") }
        assertEquals(wrong.joinToString { it.name }, 0, wrong.size)
    }

    @Test
    fun `synthetic outputs read as the reference's postprocessing writes them`() {
        val rows = Golden.rows("detok_synthetic.tsv")

        assertTrue(rows.size > 600)
        for (row in rows) assertEquals(row.name, row.string("text"), english(row))
    }

    @Test
    fun `Moses' English rules`() {
        fun moses(text: String) = EnglishDetokenizer.moses(text)

        assertEquals("It's here, isn't it?", moses("It 's here , isn 't it ?"))
        assertEquals("The players' scores", moses("The players ' scores"))
        assertEquals("He said \"yes\" and left.", moses("He said \" yes \" and left ."))
        assertEquals("I paid $5 (cash).", moses("I paid $ 5 ( cash ) ."))
        assertEquals("a-b \"c\" & d", moses("a @-@ b &quot;c&quot; &amp; d"))
        assertEquals("中文测试 ok", moses("中文 测试 ok"))
        assertEquals("", moses("  "))
    }

    @Test
    fun `the pieces are joined at SentencePiece's word marks`() {
        assertEquals("The meeting is at ten.", EnglishDetokenizer.detokenize(listOf("▁The", "▁meet", "ing", "▁is", "▁at", "▁ten", "▁.")))
    }
}
