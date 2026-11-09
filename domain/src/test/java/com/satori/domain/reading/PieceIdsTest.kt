package com.satori.domain.reading

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pieces to the model's ids and back, against the export's `tokenization_indictrans.py` on piece
 * lists drawn from `dict.SRC.json` (golden.py). The vocabularies are subsets of the pinned files,
 * every entry the cases need, unchanged.
 */
class PieceIdsTest {

    private val ids = Golden.pieceIds

    @Test
    fun `each piece list gets the reference's ids`() {
        val rows = Golden.rows("piece_ids.tsv")
        assertTrue(rows.size > 200)
        for (row in rows) assertArrayEquals(row.name, row.ints("ids"), ids.sourceIds(row.strings("pieces")))
    }

    @Test
    fun `the sequence is the two tags, the pieces and the end of the sentence`() {
        // mal_Mlym is 13 and eng_Latn 4 in dict.SRC.json, </s> 2, <unk> 3.
        assertArrayEquals(intArrayOf(13, 4, 2), ids.sourceIds(emptyList()))
        assertArrayEquals(intArrayOf(13, 4, 3, 2), ids.sourceIds(listOf("no such piece")))
    }

    @Test
    fun `at most 253 pieces fit the model's 256 positions`() {
        val pieces = List(PieceIds.MAX_SOURCE_PIECES) { "▁." }

        assertEquals(256, ids.sourceIds(pieces).size)
        val error = runCatching { ids.sourceIds(pieces + "▁.") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `the English pieces leave out the special tokens and ids past the vocabulary`() {
        val pieces = ids.targetPieces(intArrayOf(2, 15, 0, 5, 1, 3, 4, 2, 32_296, -1, Int.MAX_VALUE))

        // 15 is "▁The", 5 "▁the", 4 "▁." in dict.TGT.json.
        assertEquals(listOf("▁The", "▁the", "▁."), pieces)
    }

    @Test
    fun `a long line is split where a word starts, and a very long word where it must`() {
        val words = (1..5).flatMap { listOf("▁w$it", "a", "b") }

        assertEquals(listOf(words), PieceIds.splitAtWords(words, max = 15))
        assertEquals(
            listOf(words.subList(0, 6), words.subList(6, 12), words.subList(12, 15)),
            PieceIds.splitAtWords(words, max = 7)
        )
        val oneWord = listOf("▁long") + List(9) { "x" }
        assertEquals(listOf(oneWord.subList(0, 4), oneWord.subList(4, 8), oneWord.subList(8, 10)), PieceIds.splitAtWords(oneWord, max = 4))
        assertTrue(PieceIds.splitAtWords(List(600) { "▁w" }).all { it.size <= PieceIds.MAX_SOURCE_PIECES })
    }

    @Test
    fun `a damaged vocabulary is refused`() {
        for (json in listOf("", "[1, 2]", "{\"a\": \"b\"}", "{\"a\": 1.5}", "{\"a\": 1,}")) {
            assertTrue(json, runCatching { PieceIds.parse(json) }.isFailure)
        }
        val noTags = "{\"<unk>\": 3, \"</s>\": 2}"
        assertTrue(runCatching { PieceIds.of(noTags, "{}") }.exceptionOrNull() is IllegalArgumentException)
        val huge = "{\"<unk>\": 3, \"</s>\": 2, \"mal_Mlym\": 13, \"eng_Latn\": 4}"
        assertTrue(runCatching { PieceIds.of(huge, "{\"x\": 2000000000}") }.exceptionOrNull() is IllegalArgumentException)
        // Int.MAX_VALUE, whose size (one more) wraps to a negative number: refused, not a failed allocation.
        assertTrue(runCatching { PieceIds.of(huge, "{\"x\": 2147483647}") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PieceIds.of(huge, "{\"x\": -1}") }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf("x"), PieceIds.of(huge, "{\"x\": ${(1 shl 20) - 1}}").targetPieces(intArrayOf((1 shl 20) - 1)))
    }

    @Test
    fun `no part ends inside a placeholder, whichever way SentencePiece cut it`() {
        val words = List(10) { "▁w$it" }
        // "< ID1 >" as word pieces, and as SentencePiece may cut it, the serial a piece of its own.
        val placeholders = listOf(
            listOf("▁<", "▁ID1", "▁>"),
            listOf("▁<", "▁ID", "1", "▁>"),
            listOf("▁", "<", "▁I", "D", "12", "▁", ">")
        )
        for (placeholder in placeholders) {
            val pieces = words.subList(0, 4) + placeholder + words.subList(4, 10)
            for (max in placeholder.size..pieces.size - 1) {
                val parts = PieceIds.splitAtWords(pieces, max)
                assertEquals(pieces, parts.flatten())
                assertTrue(parts.all { it.size <= max })
                // The placeholder is whole in one part.
                assertTrue("$placeholder at $max", parts.any { part -> part.windowed(placeholder.size).contains(placeholder) })
            }
        }
        // At 6 the cut would fall before "▁ID"; it moves to before the placeholder's "▁<".
        val pieces = words.subList(0, 4) + listOf("▁<", "▁ID", "1", "▁>") + words.subList(4, 10)
        assertEquals(listOf(pieces.subList(0, 4), pieces.subList(4, 10), pieces.subList(10, 14)), PieceIds.splitAtWords(pieces, max = 6))
        // Text that only looks like a placeholder, without its serial, is cut as words.
        val open = listOf("▁<", "▁ID", "▁x", "▁>")
        assertEquals(listOf(open.subList(0, 2), open.subList(2, 4)), PieceIds.splitAtWords(open, max = 2))
        val noSerial = listOf("▁a", "▁<", "▁ID", "▁>")
        assertEquals(listOf(noSerial.subList(0, 2), noSerial.subList(2, 4)), PieceIds.splitAtWords(noSerial, max = 2))
    }

    @Test
    fun `a line's placeholders stay whole when it is split`() {
        // Word pieces of a preprocessed line whose placeholder straddles the 253rd piece.
        val line = "ഇത് ".repeat(251) + "a.b@example.com " + "ഇത് ".repeat(10)
        val pre = IndicPreprocessor.preprocess(line)
        val pieces = pre.text.split(' ').map { "▁$it" }
        val parts = PieceIds.splitAtWords(pieces)

        assertEquals(listOf("a.b@example.com"), pre.placeholders.matches)
        assertEquals(listOf(251, 13), parts.map { it.size })
        assertEquals(listOf("▁<", "▁ID1", "▁>"), parts[1].take(3))
    }

    @Test
    fun `the vocabularies parse as Python's json reads them, escapes and all`() {
        val parsed = PieceIds.parse("{\"▁\\\"\": 9, \"\\\\\": 10, \"\\u0d7b\": 11, \"\uD83D\uDE00\": 12}")

        assertEquals(mapOf("▁\"" to 9, "\\" to 10, "\u0D7B" to 11, "\uD83D\uDE00" to 12), parsed)
    }
}
