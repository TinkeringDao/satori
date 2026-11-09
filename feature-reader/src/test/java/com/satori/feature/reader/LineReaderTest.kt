package com.satori.feature.reader

import com.satori.domain.reading.PieceIds
import com.satori.reader.Reading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One line's reading, R1's surface on R2's: the preprocessing, the pieces, the parts of at most
 * D15's pieces cut where a word starts, each part as the model's source ids, the English of what it
 * wrote with the line's placeholders put back, and a line too long for the preprocessing cut at a
 * space first. The tokenizer and the model are fakes; the text pipeline is the real one.
 */
class LineReaderTest {

    private val source = mapOf(
        "<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3, "eng_Latn" to 4, "mal_Mlym" to 13, "▁a" to 20, "▁b" to 21
    )
    private val target = mapOf(
        "<s>" to 0, "<pad>" to 1, "</s>" to 2, "<unk>" to 3, "▁Hello" to 10, "▁world" to 11, "." to 12, "▁<ID1>" to 13
    )
    private val ids = PieceIds(source, target)

    // SentencePiece as far as these lines need it: a piece for each word.
    private val pieces: (String) -> List<String> = { text -> text.split(' ').filter { it.isNotEmpty() }.map { "▁$it" } }

    private val sources = mutableListOf<IntArray>()

    private fun reader(maxPieces: Int = PieceIds.MAX_SOURCE_PIECES, maxChars: Int = LineReader.MAX_CHARS, answer: (Int) -> Reading) =
        LineReader(pieces, ids, { source -> sources += source; answer(sources.size) }, maxPieces, maxChars)

    @Test
    fun `a line is read as the model's source and comes back as English`() = runBlocking {
        val reading = reader { Reading(intArrayOf(10, 11, 12), -0.5, capped = false, steps = 4) }.read("a b")

        assertArrayEquals(intArrayOf(13, 4, 20, 21, 2), sources.single())
        assertEquals("Hello world.", reading.text)
        assertEquals(listOf(2), reading.parts.map { it.pieces })
        assertEquals(3, reading.tokens)
        assertEquals(4, reading.steps)
        assertEquals(-0.5, reading.meanLogProb!!, 0.0)
    }

    @Test
    fun `a line of more pieces than a reading takes is read in parts cut where words start`() = runBlocking {
        val means = listOf(-1.0, -2.0, -3.0)
        val reading = reader(maxPieces = 3) { n -> Reading(intArrayOf(10), means[n - 1], capped = n == 2, steps = 2) }.read("a b c d e f g")

        assertEquals(listOf(6, 6, 4), sources.map { it.size })
        // Each part framed alone: the two tags, its pieces, its own </s>; c to g are unknown.
        assertArrayEquals(intArrayOf(13, 4, 20, 21, 3, 2), sources[0])
        assertArrayEquals(intArrayOf(13, 4, 3, 2), sources[2])
        assertEquals("Hello Hello Hello", reading.text)
        assertEquals(listOf(3, 3, 1), reading.parts.map { it.pieces })
        assertTrue(reading.capped)
        // Each part's mean weighed by its tokens and its end.
        assertEquals(-2.0, reading.meanLogProb!!, 1e-12)
    }

    @Test
    fun `a line with no piece reads as empty, without the model`() = runBlocking {
        val reader = reader { error("the model ran") }

        assertSame(LineReading.EMPTY, reader.read(""))
        assertSame(LineReading.EMPTY, reader.read("   "))
        assertNull(LineReading.EMPTY.meanLogProb)
        assertTrue(sources.isEmpty())
    }

    @Test
    fun `the line's placeholders come back in the English`() = runBlocking {
        val reading = reader { Reading(intArrayOf(10, 13), -0.1, capped = false, steps = 3) }.read("a x@y.com")

        assertEquals("Hello x@y.com", reading.text)
    }

    @Test
    fun `a line longer than the preprocessing takes at once is cut at a space, each piece read`() = runBlocking {
        val reading = reader(maxChars = 10) { Reading(intArrayOf(11), -0.2, capped = false, steps = 2) }.read("aaaa bbbb cccc dddd")

        assertEquals(2, sources.size)
        assertEquals("world world", reading.text)
    }

    @Test
    fun `a line is cut at its last space within the bound, else at the bound, never inside a surrogate pair`() {
        assertEquals(listOf("aaaa bbbb", "cccc dddd"), LineReader.chunks("aaaa bbbb cccc dddd", 10))
        assertEquals(listOf("abcdefghij", "klmnopqrst", "uvwxy"), LineReader.chunks("abcdefghijklmnopqrstuvwxy", 10))
        val emoji = "😀"
        assertEquals(listOf("abcdefghi", "${emoji}klmnopq"), LineReader.chunks("abcdefghi${emoji}klmnopq", 10))
        assertEquals(listOf(emoji, "a", "b"), LineReader.chunks("${emoji}ab", 1))
        assertEquals(listOf("a b"), LineReader.chunks("a b", 10))
        assertEquals(emptyList<String>(), LineReader.chunks(" ".repeat(25), 10))
    }
}
